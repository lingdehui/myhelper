package com.example.myhelper.capability;

import com.example.myhelper.llm.LlmTexts;

import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.persistence.EntityJson;
import com.example.myhelper.world.model.UnitType;
import com.example.myhelper.world.model.WorldEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Qdrant 真实 HTTP 候选查询适配器，复用现有 payload.entity 格式。
 * 固定版本加载及执行资格由明确注入的权威实体读取器提供，不自行决定类型归属。
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

    /** 由调用方管理的 HTTP 客户端。 */
    private final HttpClient client;
    /** 检索集合、嵌入版本及有界查询配置。 */
    private final Config config;
    /** 查询文本嵌入；必须对应集合中向量的模型与维数。 */
    private final Function<String, List<Float>> embed;
    /** 权威读取：由调用方明确数据库后按实体 ID 读取。 */
    private final Function<String, Optional<WorldEntity>> read;
    /** 与存储模块一致的实体解析。 */
    private final EntityJson json = new EntityJson();
    /** 组合定义不含时间值，使用专用转换器。 */
    private final ObjectMapper mapper = new ObjectMapper();

    // 装配 HTTP 客户端、查询配置、嵌入服务和明确来源的实体读取器。
    public QdrantCapabilityRepository(HttpClient client, Config config, Function<String, List<Float>> embed,
                                      Function<String, Optional<WorldEntity>> read) {
        // 保存调用方管理的 HTTP 客户端，不为每个查询重复建连接池。
        this.client = Objects.requireNonNull(client);
        // 固定本适配器的服务、集合及嵌入合同。
        this.config = Objects.requireNonNull(config);
        // 保存文本转向量函数，其模型和维数必须与索引一致。
        this.embed = Objects.requireNonNull(embed);
        // 保存权威实体读取函数；本模块不擅自按实体类型选择库。
        this.read = Objects.requireNonNull(read);
    }

    @Override
    // 按查询目标访问 Qdrant，返回有限候选而非执行资格。
    public List<CapabilityLookup.Candidate> search(CapabilityLookup.Query query) {
        // 将用户目标或 LLM 子目标转为查询向量，并固定列表。
        List<Float> vector = List.copyOf(embed.apply(query.goal()));
        // 维数必须符合配置，向量也不能含 NaN 或无穷数。
        boolean invalidVector = vector.size() != config.dimensions();
        // 维数已经错误时不再检查元素，与原来的短路判断一致。
        if (!invalidVector) {
            for (Float value : vector) {
                if (!Float.isFinite(value)) {
                    invalidVector = true;
                    break;
                }
            }
        }
        if (invalidVector) {
            // 参数或协议不符合约定，立即报错，不继续执行。
            throw new IllegalArgumentException(LlmTexts.INVALID_QUERY_VECTOR);
        }
        // 下面是 Qdrant 固定 JSON 协议字段：向量、数量上限和实体快照开关。
        Map<String, Object> body = Map.of("query", vector, "limit", config.limit(), "with_payload", true,
                // 召回阈值只用于筛选候选；过滤器限定嵌入模型及版本。
                "score_threshold", config.scoreThreshold(), "filter", Map.of("must", List.of(
                        // 只在相同嵌入模型的向量间比较，防止不同空间混检。
                        Map.of("key", "model", "match", Map.of("value", config.embeddingModel())),
                        // 同模型的不同版本也不应混用向量。
                        Map.of("key", "model_version", "match", Map.of("value", config.embeddingVersion())))));
        // 将集合名编码为 URL 路径；路径空格应为 %20 而不是表单的加号。
        String collection = URLEncoder.encode(config.collection(), StandardCharsets.UTF_8).replace("+", "%20");
        // 按 Qdrant 查询接口拼接地址；先去掉服务地址末尾多余斜线。
        URI endpoint = URI.create(config.baseUri().toString().replaceAll("/+$", "") + "/collections/" + collection + "/points/query");
        // 按配置设置单次 HTTP 超时，声明请求正文为 JSON。
        var builder = HttpRequest.newBuilder(endpoint).timeout(config.timeout()).header("Content-Type", "application/json")
                // 用现有实体 JSON 工具编码查询请求，不在日志中输出密钥。
                .POST(HttpRequest.BodyPublishers.ofString(json.encode(body)));
        // 只有配置了 API key 时才添加认证头。
        if (config.apiKey() != null && !config.apiKey().isBlank()) {
            // 认证头名由 Qdrant 协议规定，不是可任意改名的内部标签。
            builder.header("api-key", config.apiKey());
        }
        try {
            // 发出同步 HTTP 请求；主流程已经把此查询提交到工作池。
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            // HTTP 协议规定 2xx 为成功；除以 100 是取状态码百位，不是业务阈值。
            if (response.statusCode() / 100 != 2) {
                // 报告当前运行/服务状态异常，交给上层处理。
                throw new IllegalStateException(LlmTexts.CAPABILITY_HTTP_STATUS_PREFIX + response.statusCode());
            }
            // 解析服务返回的 JSON，不把 HTTP 成功直接当作有效结果。
            var decoded = json.parse(response.body());
            // result.points 是 Qdrant Query points 的响应路径。
            var points = decoded.path("result").path("points");
            // 同时检查协议状态 ok 和候选数组形状。
            if (!decoded.path("status").asText().equals("ok") || !points.isArray()) {
                // 报告当前运行/服务状态异常，交给上层处理。
                throw new IllegalStateException(LlmTexts.INVALID_CAPABILITY_RESPONSE);
            }
            // 收集本适配器支持的已发布计划候选。
            List<CapabilityLookup.Candidate> result = new ArrayList<>();
            // 逐个解析返回的向量点及附带实体快照。
            for (var point : points) {
                // 客户端同样限制候选数量，避免服务异常返回过量数据。
                if (result.size() >= config.limit()) {
                    // 候选已足够，停止解析剩余结果。
                    break;
                }
                // payload.entity 与当前存储模块保存的完整实体 JSON 字符串一致。
                var encoded = point.path("payload").path("entity");
                // 缺少完整实体快照的点无法按此合同解析，跳过该点。
                if (!encoded.isTextual()) {
                    // 当前候选不满足读取合同，继续检查其他候选。
                    continue;
                }
                // 还原统一实体，保留既有类型、版本和 data。
                WorldEntity entity = json.entity(encoded.asText());
                // 索引中也先筛选已发布计划；这不能代替执行前权威核验。
                if (!published(entity)) {
                    // 当前候选不满足读取合同，继续检查其他候选。
                    continue;
                }
                // 索引实体只提供候选；执行前重新查权威实体，不信任向量索引中的旧发布状态。
                result.add(new CapabilityLookup.Candidate(ref(entity), entity.text() == null ? entity.name() : entity.text(), entity.data()));
            }
            // 返回只读候选列表，不向调用方暴露构建中的集合。
            return List.copyOf(result);
        } catch (InterruptedException interrupted) {
            // 保留线程中断标志，让上层识别停止请求。
            Thread.currentThread().interrupt();
            // 报告当前运行/服务状态异常，交给上层处理。
            throw new IllegalStateException(LlmTexts.CAPABILITY_SEARCH_INTERRUPTED, interrupted);
        } catch (IOException failure) {
            // 报告当前运行/服务状态异常，交给上层处理。
            throw new IllegalStateException(LlmTexts.CAPABILITY_CONNECTION_FAILED, failure);
        }
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
        Optional<WorldEntity> loaded = read.apply(ref.id());
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        WorldEntity entity = loaded.get();
        if (entity.revision() != ref.revision() || !published(entity)) {
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
        Optional<WorldEntity> loaded = read.apply(ref.id());
        if (loaded.isEmpty()) {
            return false;
        }
        WorldEntity entity = loaded.get();
        return entity.revision() == ref.revision() && published(entity);
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

    public record Config(
            /** 仓储来源身份，参与缓存键。 */
            String namespace,
            /** Qdrant 服务地址，不包含集合路径。 */
            URI baseUri,
            /** 建议使用能力检索集合，避免非能力点挤占候选。 */
            String collection,
            /** 可选认证，不写入执行图。 */
            String apiKey,
            /** 单次 HTTP 时限。 */
            Duration timeout,
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
            // 命名空间不能为空，参与缓存隔离。
            TaskPlan.requireText(namespace, "namespace");
            // 集合名不能为空，不能默默查询错误集合。
            TaskPlan.requireText(collection, "collection");
            // 明确查询向量使用的嵌入模型。
            TaskPlan.requireText(embeddingModel, "embeddingModel");
            // 向量模型的版本必须与过滤条件一致。
            TaskPlan.requireText(embeddingVersion, "embeddingVersion");
            // 必须指定明确服务地址。
            Objects.requireNonNull(baseUri);
            // 仅接受 HTTP(S)，向量维数/候选数必须为正，数量不能超过本地保护上限。
            if (!List.of("http", "https").contains(baseUri.getScheme()) || dimensions < 1 || limit < 1 || limit > MAX_CANDIDATES_PER_QUERY
                    // 阈值必须是有限数，单次 HTTP 时限必须为正。
                    || !Double.isFinite(scoreThreshold) || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("无效检索配置");
        }
    }
}
