package com.example.myhelper.capability;

import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.world.model.Embedding;
import com.example.myhelper.world.model.UnitType;
import com.example.myhelper.world.model.WorldEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

/** 实际仓储经统一读接口展开意图；存储边界替身不生成链级可靠度。 */
class IntentCapabilityRepositoryTest {
    /** 场景构造复用同一个 JSON 转换器，不在大分页检查中重复建映射器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 固定查询身份，不将文本相似度当成可执行许可。 */
    private static final CapabilityLookup.Query QUERY =
            new CapabilityLookup.Query("root", 0, "$entry", "读取设备状态", Map.of("device", "ac"));

    @Test
    void oneIntentExpandsIndependentChainVersionsAndSeesNewChainsWithoutNewIndexPoints() {
        Fixture fixture = new Fixture();
        WorldEntity intent = intent("intent", 7, "PUBLISHED");
        WorldEntity first = chain("chain-a", "intent", 2, "PUBLISHED");
        WorldEntity second = chain("chain-b", "intent", 4, "PUBLISHED");
        fixture.graph.put(intent, first, second);
        fixture.vector.hits = List.of(new StorageQuery.Hit(intent, 0.82));
        var repository = fixture.repository(DualWriteStore.Database.NEO4J);

        List<CapabilityLookup.Candidate> found = repository.search(QUERY);
        assertEquals(List.of("chain-a@2", "chain-b@4"), identities(found));
        assertEquals(0.82, found.getFirst().semanticScore());
        assertEquals(plan(), repository.load(found.getFirst().ref()).orElseThrow().plan());
        assertTrue(repository.isAvailable(found.getLast().ref()));

        fixture.graph.put(chain("chain-c", "intent", 1, "PUBLISHED"));
        assertEquals(List.of("chain-a@2", "chain-b@4", "chain-c@1"), identities(repository.search(QUERY)));
        assertEquals(1, fixture.vector.hits.size(), "新增链无需新增意图索引点");
        assertEquals(2, fixture.embeddings.calls, "每次用户检索仍只嵌入一次查询文本");
        fixture.graph.put(chain("chain-a", "intent", 3, "PUBLISHED"));
        assertFalse(repository.isAvailable(found.getFirst().ref()), "旧链版本不得借稳定意图身份继续执行");
        assertTrue(repository.load(found.getFirst().ref()).isEmpty());
    }

    @Test
    void rejectsStaleOrDisabledEntrancesAndFiltersInvalidAssociatedChains() {
        Fixture fixture = new Fixture();
        WorldEntity live = intent("live", 1, "PUBLISHED");
        WorldEntity stale = intent("stale", 1, "PUBLISHED");
        WorldEntity disabled = intent("disabled", 1, "PUBLISHED");
        fixture.graph.put(live, intent("stale", 2, "PUBLISHED"), intent("disabled", 1, "DISABLED"),
                chain("valid", "live", 3, "PUBLISHED"), chain("revoked", "live", 1, "DISABLED"),
                chain("wrong-intent", "unrecalled", 1, "PUBLISHED"),
                chain("stale-child", "stale", 1, "PUBLISHED"),
                entity("no-plan", UnitType.PLAN_STEP, 1, Map.of("intentId", "live", "publicationStatus", "PUBLISHED")));
        fixture.vector.hits = List.of(new StorageQuery.Hit(stale, 0.99), new StorageQuery.Hit(disabled, 0.98),
                new StorageQuery.Hit(intent("missing", 1, "PUBLISHED"), 0.97), new StorageQuery.Hit(live, 0.9));

        assertEquals(List.of("valid@3"), identities(fixture.repository(DualWriteStore.Database.NEO4J).search(QUERY)));
        assertEquals(List.of("live"), fixture.graph.requests.getFirst().filter().matches().getLast().values());
        assertEquals(1, fixture.graph.batchReads);
    }

    @Test
    void batchesIntentExpansionAcrossPagesAndDeduplicatesLegacyDirectHits() {
        Fixture fixture = new Fixture();
        WorldEntity firstIntent = intent("first", 1, "PUBLISHED");
        WorldEntity secondIntent = intent("second", 1, "PUBLISHED");
        fixture.graph.put(firstIntent, secondIntent);
        for (int number = 0; number < 105; number++) {
            String intentId = number < 100 ? "first" : "second";
            fixture.graph.put(chain(String.format("chain-%03d", number), intentId, 1, "PUBLISHED"));
        }
        WorldEntity legacy = fixture.graph.entities.get("chain-001");
        fixture.vector.hits = List.of(new StorageQuery.Hit(firstIntent, 0.8),
                new StorageQuery.Hit(legacy, 0.95), new StorageQuery.Hit(secondIntent, 0.7),
                new StorageQuery.Hit(firstIntent, 0.6));
        List<CapabilityLookup.Candidate> found = fixture.repository(DualWriteStore.Database.NEO4J).search(QUERY);

        assertEquals(105, found.size());
        assertEquals(2, fixture.graph.requests.size(), "按整个意图集合分页，不按意图分别查询");
        assertEquals(List.of("first", "second"), fixture.graph.requests.getFirst().filter().matches().getLast().values());
        assertNotNull(fixture.graph.requests.getLast().cursor());
        assertEquals("chain-001", found.getFirst().ref().id());
        assertEquals(0.95, found.getFirst().semanticScore(), "同固定链使用较高召回分，不能重复返回");
        assertEquals(0.7, found.getLast().semanticScore());
    }

    @Test
    void legacyDirectIndexNeedsNoIntentQueryAndKeepsAuthoritativeDescription() {
        Fixture fixture = new Fixture();
        WorldEntity indexed = chain("legacy", null, 2, "PUBLISHED");
        WorldEntity current = new WorldEntity(indexed.id(), null, null, UnitType.PLAN_STEP, null,
                "权威名称", "权威描述", indexed.data(), List.of(), null, 2, "test", List.of(), Map.of(), null, null);
        fixture.graph.put(current);
        fixture.vector.hits = List.of(new StorageQuery.Hit(indexed, 0.91));
        List<CapabilityLookup.Candidate> found = fixture.repository(DualWriteStore.Database.NEO4J).search(QUERY);
        assertEquals(List.of("legacy@2"), identities(found));
        assertEquals("权威描述", found.getFirst().description());
        assertTrue(fixture.graph.requests.isEmpty());
        assertEquals(List.of("PLAN_STEP", "INTENT"), fixture.vector.lastRequest.filter().matches().getFirst().values());
    }

    @Test
    void excessiveExpansionFailsInsteadOfSilentlyReturningAnIncompleteBestSet() {
        Fixture fixture = new Fixture();
        WorldEntity intent = intent("many", 1, "PUBLISHED");
        fixture.graph.put(intent);
        for (int number = 0; number < 1001; number++) {
            fixture.graph.put(chain(String.format("chain-%04d", number), "many", 1, "PUBLISHED"));
        }
        fixture.vector.hits = List.of(new StorageQuery.Hit(intent, 0.9));
        assertThrows(IllegalStateException.class, new Search(fixture.repository(DualWriteStore.Database.NEO4J)));
        assertEquals(10, fixture.graph.requests.size(), "读取一千条后停止，不无界扫描");
    }

    @Test
    void intentIndexRequiresNeo4jAuthorityWithoutSilentlyDroppingIt() {
        Fixture fixture = new Fixture();
        fixture.vector.hits = List.of(new StorageQuery.Hit(intent("intent", 1, "PUBLISHED"), 0.9));
        assertThrows(IllegalStateException.class, new Search(fixture.repository(DualWriteStore.Database.QDRANT)));
        assertTrue(fixture.graph.requests.isEmpty());
    }

    private static List<String> identities(List<CapabilityLookup.Candidate> candidates) {
        List<String> result = new ArrayList<>();
        for (CapabilityLookup.Candidate candidate : candidates) {
            result.add(candidate.ref().id() + "@" + candidate.ref().revision());
        }
        return result;
    }

    private static WorldEntity intent(String id, long revision, String status) {
        return entity(id, UnitType.INTENT, revision, Map.of("publicationStatus", status));
    }

    private static WorldEntity chain(String id, String intentId, long revision, String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("publicationStatus", status);
        data.put("executionPlan", MAPPER.convertValue(plan(), Map.class));
        if (intentId != null) {
            data.put("intentId", intentId);
        }
        return entity(id, UnitType.PLAN_STEP, revision, data);
    }

    private static WorldEntity entity(String id, UnitType type, long revision, Map<String, Object> data) {
        return new WorldEntity(id, null, null, type, null, id, id, data, List.of(), null,
                revision, "test", List.of(), Map.of(), null, null);
    }

    private static TaskPlan plan() {
        return new TaskPlan("读取设备状态", List.of(new TaskPlan.Step("read", "读取", TaskPlan.Kind.TOOL,
                "read", Map.of(), List.of(), Map.of())));
    }

    private record Search(
            /** 每次检查使用独立的生产查询仓储。 */
            QdrantCapabilityRepository repository) implements Executable {
        @Override
        public void execute() {
            repository.search(QUERY);
        }
    }

    private static final class Fixture {
        /** 权威实体参与者，不代表真实 Neo4j 联调。 */
        private final Graph graph = new Graph();
        /** 向量命中只负责意图和旧链召回。 */
        private final Vector vector = new Vector();
        /** 明确计数本次查询文本的嵌入。 */
        private final QueryEmbedding embeddings = new QueryEmbedding();

        private QdrantCapabilityRepository repository(DualWriteStore.Database authority) {
            return QdrantCapabilityRepository.withTextEmbedding(new DualWriteStore(graph, vector),
                    new QdrantCapabilityRepository.SearchConfig("skills", authority, 2, 10, 0), embeddings);
        }
    }

    private static final class QueryEmbedding implements Function<String, Embedding> {
        /** 每次搜索至多生成一个查询向量。 */
        private int calls;
        @Override
        public Embedding apply(String text) {
            calls++;
            return new Embedding("embed", "v1", List.of(0.0, 1.0));
        }
    }

    private abstract static class ReadOnlyParticipant implements DualWriteStore.Participant {
        @Override
        public DualWriteStore.Change open(String operationId, List<WorldEntity> entities) {
            throw new AssertionError("查询不得写入或重新嵌入已有实体");
        }
    }

    private static final class Vector extends ReadOnlyParticipant {
        /** 由场景给定的向量索引快照。 */
        private List<StorageQuery.Hit> hits = List.of();
        /** 观察生产请求的发布状态和类型过滤。 */
        private StorageQuery.VectorRequest lastRequest;
        @Override
        public List<StorageQuery.Hit> search(StorageQuery.VectorRequest request) {
            lastRequest = request;
            return hits;
        }
        @Override
        public Optional<WorldEntity> read(String id) {
            throw new AssertionError("新意图链不能从向量副本读取权威定义");
        }
    }

    private static final class Graph extends ReadOnlyParticipant {
        /** 按 ID 分页的内存权威快照。 */
        private final Map<String, WorldEntity> entities = new TreeMap<>();
        /** 收集页请求，核对条件和分页数量。 */
        private final List<StorageQuery.PageRequest> requests = new ArrayList<>();
        /** 核对向量命中使用一次批量回源。 */
        private int batchReads;

        private void put(WorldEntity... values) {
            for (WorldEntity value : values) {
                entities.put(value.id(), value);
            }
        }

        @Override
        public Optional<WorldEntity> read(String id) {
            return Optional.ofNullable(entities.get(id));
        }

        @Override
        public Map<String, WorldEntity> readBatch(List<String> ids) {
            batchReads++;
            Map<String, WorldEntity> found = new LinkedHashMap<>();
            for (String id : ids) {
                if (entities.containsKey(id)) {
                    found.put(id, entities.get(id));
                }
            }
            return found;
        }

        @Override
        public StorageQuery.Page query(StorageQuery.PageRequest request) {
            requests.add(request);
            List<WorldEntity> page = new ArrayList<>();
            for (WorldEntity entity : entities.values()) {
                if (request.cursor() != null && entity.id().compareTo(request.cursor().position()) <= 0) {
                    continue;
                }
                if (!matches(entity, request.filter())) {
                    continue;
                }
                if (page.size() == request.limit()) {
                    return new StorageQuery.Page(page,
                            new StorageQuery.Cursor(DualWriteStore.Database.NEO4J, page.getLast().id()));
                }
                page.add(entity);
            }
            return new StorageQuery.Page(page, null);
        }

        private boolean matches(WorldEntity entity, StorageQuery.Filter filter) {
            for (StorageQuery.Match match : filter.matches()) {
                Object actual;
                if ("/unitType".equals(match.pointer())) {
                    actual = entity.unitType().name();
                } else {
                    actual = entity.data().get(match.pointer().substring("/data/".length()));
                }
                if (!match.values().contains(actual)) {
                    return false;
                }
            }
            return true;
        }
    }
}
