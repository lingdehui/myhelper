package com.example.myhelper.capability;

import com.example.myhelper.llm.LlmTexts;

import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.world.model.Embedding;
import com.example.myhelper.world.model.UnitType;
import com.example.myhelper.world.model.WorldEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 能力查询业务适配器，Qdrant 候选和权威实体读取均通过统一存储入口。
 * 只处理固定版本和发布资格，不创建数据库客户端或解释实体存储归属。
 */
public final class QdrantCapabilityRepository implements CapabilityLookup.Repository {
    /** 本适配器的候选数量保护上限；不是 Qdrant 服务的硬性限制。 */
    private static final int MAX_CANDIDATES_PER_QUERY = 1000;
    /** 能力 data 中的发布状态字段，须与发布模块使用相同协议。 */
    private static final String PUBLICATION_STATUS_FIELD = "publicationStatus";
    /** 当前支持的可执行发布状态；不能随意改名，否则已有数据无法识别。 */
    private static final String PUBLISHED_STATUS = "PUBLISHED";
    /** 能力 data 中保存 TaskPlan JSON 的字段名。 */
    private static final String EXECUTION_PLAN_FIELD = "executionPlan";
    /** 在向量候选限额生效前按类型与索引发布状态筛选。 */
    private static final StorageQuery.Filter PUBLISHED_PLANS = new StorageQuery.Filter(List.of(
            new StorageQuery.Match("/unitType", List.of(UnitType.PLAN_STEP.name())),
            new StorageQuery.Match("/data/" + PUBLICATION_STATUS_FIELD, List.of(PUBLISHED_STATUS))));

    /** 所有数据库读取都通过同一个存储入口。 */
    private final DualWriteStore store;
    /** 上层指定的权威来源和搜索范围。 */
    private final SearchConfig config;
    /** 将查询文本转换为带有模型及版本的统一存储请求。 */
    private final Function<String, StorageQuery.VectorRequest> queryVector;
    /** 组合定义不含时间值，使用专用转换器。 */
    private final ObjectMapper mapper = new ObjectMapper();

    // 注入已按 application.yml 装配的存储入口，不另建数据库连接。
    public QdrantCapabilityRepository(DualWriteStore store, Config config, Function<String, List<Float>> embed) {
        this(store, config.searchConfig(), new LegacyQueryEmbedding(config, Objects.requireNonNull(embed)));
    }

    /** 复用发布和检索共用的文本嵌入客户端，查询使用其实际返回的模型与版本。 */
    public static QdrantCapabilityRepository withTextEmbedding(
            DualWriteStore store, SearchConfig config, Function<String, Embedding> embed) {
        return new QdrantCapabilityRepository(store, config,
                new QueryEmbedding(config, Objects.requireNonNull(embed)));
    }

    private QdrantCapabilityRepository(DualWriteStore store, SearchConfig config,
                                       Function<String, StorageQuery.VectorRequest> queryVector) {
        this.store = Objects.requireNonNull(store);
        this.config = Objects.requireNonNull(config);
        this.queryVector = queryVector;
    }

    @Override
    // 查询只提供候选；发布资格仍由能力模块负责。
    public List<CapabilityLookup.Candidate> search(CapabilityLookup.Query query) {
        // 候选来源由本 Qdrant 仓储明确指定，不依赖实体类型。
        StorageQuery.VectorRequest request = queryVector.apply(query.goal());
        // 存储模块负责 HTTP、配置、完整实体还原和数据库错误。
        List<StorageQuery.Hit> hits = store.search(DualWriteStore.Database.QDRANT, request);
        // 本次召回以索引的固定引用为准，从指定的权威来源一次取得实体。
        Set<String> ids = new LinkedHashSet<>();
        for (StorageQuery.Hit hit : hits) {
            ids.add(hit.entity().id());
        }
        Map<String, WorldEntity> authoritative = store.readBatch(config.authoritativeSource(), List.copyOf(ids));
        List<CapabilityLookup.Candidate> result = new ArrayList<>();
        for (StorageQuery.Hit hit : hits) {
            WorldEntity indexed = hit.entity();
            WorldEntity current = authoritative.get(indexed.id());
            // 保留召回顺序，但过期索引不能用新修订或已停用实体充当旧候选。
            if (published(indexed) && matchesPublishedRevision(current, indexed.revision())) {
                result.add(new CapabilityLookup.Candidate(ref(current), current.text() == null ? current.name() : current.text(), current.data()));
            }
        }
        // 每次调用都重新查库，不增加候选缓存。
        return List.copyOf(result);
    }

    @Override
    // 按固定引用读取权威实体，而不是直接执行索引中的旧快照。
    public Optional<CapabilityLookup.Definition> load(CapabilityLookup.Ref ref) {
        // 拒绝从另一个来源误读同名能力。
        if (!config.namespace().equals(ref.namespace())) {
            // 参数或协议不符合约定，立即报错，不继续执行。
            throw new IllegalArgumentException(LlmTexts.CAPABILITY_SOURCE_MISMATCH);
        }
        // 读取权威实体，要求精确修订且当前仍是已发布计划。
        WorldEntity entity = store.read(config.authoritativeSource(), ref.id()).orElse(null);
        if (!matchesPublishedRevision(entity, ref.revision())) {
            return Optional.empty();
        }
        // 将 executionPlan JSON 转为经过结构校验的 TaskPlan。
        TaskPlan plan = mapper.convertValue(entity.data().get(EXECUTION_PLAN_FIELD), TaskPlan.class);
        return Optional.of(new CapabilityLookup.Definition(ref, plan));
    }

    @Override
    // 每次能力执行前核验权威状态，不使用内容缓存的旧资格。
    public boolean isAvailable(CapabilityLookup.Ref ref) {
        // 拒绝从另一个来源误读同名能力。
        if (!config.namespace().equals(ref.namespace())) {
            // 无法确认当前来源匹配时，不允许执行该引用。
            return false;
        }
        // 读取权威实体，要求精确修订且当前仍是已发布计划。
        WorldEntity entity = store.read(config.authoritativeSource(), ref.id()).orElse(null);
        return matchesPublishedRevision(entity, ref.revision());
    }

    // 把统一实体转换为明确来源的固定版本引用。
    private CapabilityLookup.Ref ref(WorldEntity entity) {
        // 缓存身份同时包含来源、实体 ID 和 revision。
        return new CapabilityLookup.Ref(config.namespace(), entity.id(), entity.revision());
    }

    // 当前读取合同只支持已发布且含计划内容的 PLAN_STEP。
    private static boolean published(WorldEntity entity) {
        // 统一类型与已发布状态必须同时满足。
        return entity.unitType() == UnitType.PLAN_STEP && PUBLISHED_STATUS.equals(entity.data().get(PUBLICATION_STATUS_FIELD))
                // 还必须实际包含执行定义，不能把空能力当成功流程。
                && entity.data().get(EXECUTION_PLAN_FIELD) != null;
    }

    private static boolean matchesPublishedRevision(WorldEntity entity, long revision) {
        return entity != null && entity.revision() == revision && published(entity);
    }

    private record QueryEmbedding(
            /** 本仓储需要遵守的查询范围。 */
            SearchConfig config,
            /** 同时返回向量及其来源的共享客户端。 */
            Function<String, Embedding> client) implements Function<String, StorageQuery.VectorRequest> {
        @Override
        public StorageQuery.VectorRequest apply(String text) {
            Embedding embedding = client.apply(text);
            if (embedding == null || embedding.values().size() != config.dimensions()) {
                throw new IllegalArgumentException(LlmTexts.INVALID_QUERY_VECTOR);
            }
            List<Float> vector = new ArrayList<>(embedding.values().size());
            for (double coordinate : embedding.values()) {
                float narrowed = (float) coordinate;
                if (!Float.isFinite(narrowed)) {
                    throw new IllegalArgumentException(LlmTexts.INVALID_QUERY_VECTOR);
                }
                vector.add(narrowed);
            }
            return new StorageQuery.VectorRequest(vector, embedding.model(), embedding.modelVersion(),
                    PUBLISHED_PLANS, config.limit(), config.scoreThreshold());
        }
    }

    private record LegacyQueryEmbedding(
            /** 旧的向量函数不携带已生成向量的模型与版本，由调用方明确配置。 */
            Config config,
            /** 返回浮点坐标的旧接口。 */
            Function<String, List<Float>> client) implements Function<String, StorageQuery.VectorRequest> {
        @Override
        public StorageQuery.VectorRequest apply(String text) {
            List<Float> vector = List.copyOf(client.apply(text));
            if (vector.size() != config.dimensions()) {
                throw new IllegalArgumentException(LlmTexts.INVALID_QUERY_VECTOR);
            }
            return new StorageQuery.VectorRequest(vector, config.embeddingModel(), config.embeddingVersion(),
                    PUBLISHED_PLANS, config.limit(), config.scoreThreshold());
        }
    }

    public record SearchConfig(
            /** 仓储来源身份，参与缓存键。 */
            String namespace,
            /** 权威实体读取来源，由上层装配明确指定。 */
            DualWriteStore.Database authoritativeSource,
            /** 与存储集合一致的精确维数。 */
            int dimensions,
            /** 候选上限。 */
            int limit,
            /** 召回阈值，仅用于召回。 */
            double scoreThreshold) {
        public SearchConfig {
            validateSearchConfig(namespace, authoritativeSource, dimensions, limit, scoreThreshold);
        }
    }

    public record Config(
            /** 仓储来源身份，参与缓存键。 */
            String namespace,
            /** 权威实体读取来源，由上层装配明确指定。 */
            DualWriteStore.Database authoritativeSource,
            /** 查询嵌入模型名。 */
            String embeddingModel,
            /** 查询嵌入版本。 */
            String embeddingVersion,
            /** 精确维数。 */
            int dimensions,
            /** 候选上限。 */
            int limit,
            /** 召回阈值，仅用于召回，不作为执行资格。 */
            double scoreThreshold) {
        // 装配时校验查询配置，避免到运行阶段才发现必填项缺失。
        public Config {
            // 明确查询向量使用的嵌入模型。
            TaskPlan.requireText(embeddingModel, "embeddingModel");
            // 向量模型的版本必须与过滤条件一致。
            TaskPlan.requireText(embeddingVersion, "embeddingVersion");
            validateSearchConfig(namespace, authoritativeSource, dimensions, limit, scoreThreshold);
        }

        private SearchConfig searchConfig() {
            return new SearchConfig(namespace, authoritativeSource, dimensions, limit, scoreThreshold);
        }
    }

    private static void validateSearchConfig(String namespace, DualWriteStore.Database authoritativeSource,
                                             int dimensions, int limit, double scoreThreshold) {
        // 来源、数量、维数和阈值属于检索合同，数据库连接由存储模块提供。
        TaskPlan.requireText(namespace, "namespace");
        Objects.requireNonNull(authoritativeSource);
        if (dimensions < 1 || limit < 1 || limit > MAX_CANDIDATES_PER_QUERY || !Double.isFinite(scoreThreshold)) {
            throw new IllegalArgumentException("无效检索配置");
        }
    }
}
