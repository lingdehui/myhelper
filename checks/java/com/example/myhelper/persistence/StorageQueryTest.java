package com.example.myhelper.persistence;

import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.UnitType;
import com.example.myhelper.world.model.WorldEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import static com.example.myhelper.persistence.DualWriteStore.Database.*;
import static org.junit.jupiter.api.Assertions.*;

/** 公共查询合同、真实 HTTP 边界及参数化 Driver 检查，不冒充真实数据库联调。 */
class StorageQueryTest {
    private final EntityJson json = new EntityJson();

    @Test void explicitSourceBatchOrderMissingAndReferenceIdentity() {
        Memory graph = new Memory();
        Memory vector = new Memory();
        WorldEntity first = entity("first", 1);
        WorldEntity second = entity("second", 2);
        graph.values.put(first.id(), first);
        vector.values.put(second.id(), second);
        DualWriteStore store = new DualWriteStore(graph, vector);
        assertTrue(store.read(QDRANT, first.id()).isEmpty());
        assertEquals(0, graph.reads);
        assertEquals(List.of(second.id()), new ArrayList<>(store.readBatch(QDRANT, List.of("missing", second.id(), second.id())).keySet()));
        assertEquals(0, graph.reads);
        var correct = new EntityRef(first.id(), 1L, null, null, UnitType.PLAN_STEP, null);
        assertEquals(first, store.read(NEO4J, correct).orElseThrow());
        assertTrue(store.read(NEO4J, new EntityRef(first.id(), 2L, null, null, UnitType.PLAN_STEP, null)).isEmpty());
        assertTrue(store.read(NEO4J, new EntityRef(first.id(), null, null, null, UnitType.TOOL, null)).isEmpty());
        int reads = vector.reads;
        assertEquals(Map.of(), store.readBatch(QDRANT, List.of()));
        assertEquals(reads, vector.reads);
        assertThrows(IllegalArgumentException.class, new Executable() {
            public void execute() { store.readBatch(QDRANT, List.of("valid", " ")); }
        });
        assertEquals(reads, vector.reads);
    }

    @Test void rejectsWrongCursorUnsupportedSearchAndInvalidQueriesBeforeDatabaseAccess() {
        Memory graph = new Memory();
        Memory vector = new Memory();
        DualWriteStore store = new DualWriteStore(graph, vector);
        assertThrows(IllegalArgumentException.class, new Executable() {
            public void execute() {
                store.query(NEO4J, new StorageQuery.PageRequest(StorageQuery.Filter.all(), 2, new StorageQuery.Cursor(QDRANT, "id")));
            }
        });
        assertThrows(UnsupportedOperationException.class, new Executable() {
            public void execute() { store.search(NEO4J, vectorRequest()); }
        });
        assertThrows(IllegalArgumentException.class, new Executable() {
            public void execute() { new StorageQuery.PageRequest(StorageQuery.Filter.all(), 1001, null); }
        });
        assertThrows(IllegalArgumentException.class, new Executable() {
            public void execute() { new StorageQuery.Match("/embedding/values", List.of(1)); }
        });
        assertThrows(IllegalArgumentException.class, new Executable() {
            public void execute() { new StorageQuery.Match("/data/x", List.of(Double.NaN)); }
        });
        assertEquals(0, graph.reads + vector.reads);
    }

    @Test void fieldProjectionPreservesTypesEscapedPathsAndScalarArrayMembership() {
        String encoded = "{\"data\":{\"taskId\":\"task-a\",\"tags\":[\"java\",\"java\",\"db\"],\"a/b~c\":true,\"count\":1.0,\"unknown\":null},\"embedding\":{\"values\":[0.1,0.2]}}";
        List<String> terms = QueryTerms.project(json, encoded);
        assertEquals(List.of("[\"/data/taskId\",\"string\",\"task-a\"]", "[\"/data/tags\",\"string\",\"java\"]",
                "[\"/data/tags\",\"string\",\"db\"]", "[\"/data/a~1b~0c\",\"boolean\",true]", "[\"/data/count\",\"number\",\"1\"]"), terms);
        var filter = new StorageQuery.Filter(List.of(new StorageQuery.Match("/data/count", List.of(1, new BigDecimal("1.00"), "1"))));
        List<String> group = QueryTerms.groups(json, filter).getFirst();
        assertEquals(group.get(0), group.get(1));
        assertNotEquals(group.get(0), group.get(2));
    }

    @Test void qdrantBatchUsesSingleRequestAndRestoresFullEntityInCallerOrder() throws Exception {
        try (HttpFixture fixture = new HttpFixture()) {
            WorldEntity a = entity("a", 1);
            WorldEntity b = entity("b", 2);
            fixture.result = List.of(point(b), point(a));
            Map<String, WorldEntity> result = fixture.store.readBatch(QDRANT, List.of("a", "missing", "b", "a"));
            assertEquals(List.of("a", "b"), new ArrayList<>(result.keySet()));
            assertEquals(a, result.get("a"));
            assertEquals(b, result.get("b"));
            assertEquals(1, fixture.calls);
            assertEquals("/collections/entities/points", fixture.path);
            assertEquals(3, fixture.request.path("ids").size());
            assertFalse(fixture.request.path("with_vector").asBoolean());
            assertEquals("secret-test", fixture.key);
            assertThrows(UnsupportedOperationException.class, new Executable() {
                public void execute() { result.clear(); }
            });
        }
    }

    @Test void qdrantScrollFiltersAtServerAndUsesReturnedNextOffset() throws Exception {
        try (HttpFixture fixture = new HttpFixture()) {
            String nextPoint = QdrantEntityStore.pointId("next");
            fixture.result = Map.of("points", List.of(point(entity("a", 1))), "next_page_offset", nextPoint);
            var first = fixture.store.query(QDRANT, new StorageQuery.PageRequest(taskFilter(), 1, null));
            assertEquals(new StorageQuery.Cursor(QDRANT, nextPoint), first.nextCursor());
            assertEquals("/collections/entities/points/scroll", fixture.path);
            assertEquals(1, fixture.request.path("limit").asInt());
            assertEquals("query_terms", fixture.request.at("/filter/must/0/key").asText());
            assertEquals("[\"/data/taskId\",\"string\",\"task-a\"]", fixture.request.at("/filter/must/0/match/any/0").asText());
            assertFalse(fixture.request.has("offset"));
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("points", List.of());
            end.put("next_page_offset", null);
            fixture.result = end;
            var last = fixture.store.query(QDRANT, new StorageQuery.PageRequest(taskFilter(), 1, first.nextCursor()));
            assertEquals(nextPoint, fixture.request.path("offset").asText());
            assertTrue(last.entities().isEmpty());
            assertNull(last.nextCursor());
        }
    }

    @Test void vectorSearchCarriesSpaceFilterScoreAndDoesNotRewriteReferences() throws Exception {
        try (HttpFixture fixture = new HttpFixture()) {
            WorldEntity entity = QdrantEntityStoreCheck.entity(1, true);
            Map<String, Object> point = new LinkedHashMap<>(point(entity));
            point.put("score", 0.87);
            fixture.result = Map.of("points", List.of(point));
            var hits = fixture.store.search(QDRANT, vectorRequest());
            assertEquals(entity, hits.getFirst().entity());
            assertEquals(0.87, hits.getFirst().score());
            assertEquals("/collections/entities/points/query", fixture.path);
            assertEquals("embed", fixture.request.at("/filter/must/0/match/value").asText());
            assertEquals("v1", fixture.request.at("/filter/must/1/match/value").asText());
            assertEquals("query_terms", fixture.request.at("/filter/must/2/key").asText());
            assertEquals(0.5, fixture.request.path("score_threshold").asDouble());
            int calls = fixture.calls;
            assertThrows(IllegalArgumentException.class, new Executable() {
                public void execute() {
                    fixture.store.search(QDRANT, new StorageQuery.VectorRequest(List.of(1f), "embed", "v1", StorageQuery.Filter.all(), 1, null));
                }
            });
            assertEquals(calls, fixture.calls);
        }
    }

    @Test void qdrantProtocolAndHttpFailuresAreNotEmptySuccessfulQueries() throws Exception {
        try (HttpFixture fixture = new HttpFixture()) {
            fixture.status = 503;
            assertThrows(IllegalStateException.class, new Executable() {
                public void execute() { fixture.store.search(QDRANT, vectorRequest()); }
            });
            fixture.status = 200;
            fixture.result = Map.of("points", List.of(point(entity("a", 1))));
            assertThrows(IllegalStateException.class, new Executable() {
                public void execute() { fixture.store.search(QDRANT, vectorRequest()); }
            });
            fixture.result = List.of(Map.of("id", QdrantEntityStore.pointId("wrong"), "payload", Map.of("entity", json.encode(entity("a", 1)))));
            assertThrows(IllegalStateException.class, new Executable() {
                public void execute() { fixture.store.readBatch(QDRANT, List.of("a")); }
            });
            fixture.result = Map.of("points", List.of());
            assertThrows(IllegalStateException.class, new Executable() {
                public void execute() { fixture.store.query(QDRANT, new StorageQuery.PageRequest(StorageQuery.Filter.all(), 1, null)); }
            });
        }
    }

    @Test void neo4jBatchAndPageUseBoundParametersAndNoReferenceExpansion() {
        GraphFixture fixture = new GraphFixture();
        var store = new DualWriteStore(new Neo4jEntityStore(fixture.driver(), "neo4j", json), new Memory());
        fixture.entities = List.of(entity("b", 2), entity("a", 1));
        var batch = store.readBatch(NEO4J, List.of("a", "b", "absent"));
        assertEquals(List.of("a", "b"), new ArrayList<>(batch.keySet()));
        assertEquals(List.of("a", "b", "absent"), fixture.params.get("ids"));
        assertTrue(fixture.query.contains("e.id IN $ids"));
        fixture.entities = List.of(entity("a", 1), entity("b", 2));
        var page = store.query(NEO4J, new StorageQuery.PageRequest(taskFilter(), 1, null));
        assertEquals(List.of(entity("a", 1)), page.entities());
        assertEquals(new StorageQuery.Cursor(NEO4J, "a"), page.nextCursor());
        assertEquals(2, fixture.params.get("limit"));
        assertEquals(List.of(List.of("[\"/data/taskId\",\"string\",\"task-a\"]")), fixture.params.get("groups"));
        assertTrue(fixture.query.contains("ORDER BY e.id LIMIT $limit"));
        assertFalse(fixture.query.contains("task-a"));
        assertFalse(fixture.query.contains("WORLD_REF"));
        fixture.entities = List.of(entity("b", 2));
        var last = store.query(NEO4J, new StorageQuery.PageRequest(taskFilter(), 1, page.nextCursor()));
        assertNull(last.nextCursor());
        assertEquals("a", fixture.params.get("after"));
        assertEquals(3, fixture.closed);
    }

    private StorageQuery.Filter taskFilter() {
        return new StorageQuery.Filter(List.of(new StorageQuery.Match("/data/taskId", List.of("task-a"))));
    }

    private StorageQuery.VectorRequest vectorRequest() {
        return new StorageQuery.VectorRequest(List.of(1f, 0f), "embed", "v1", taskFilter(), 5, 0.5);
    }

    private WorldEntity entity(String id, long revision) {
        return new WorldEntity(id, null, null, UnitType.PLAN_STEP, null, "name", "text", Map.of("taskId", "task-a"),
                List.of(), null, revision, "check", List.of(), Map.of(), null, null);
    }

    private Map<String, Object> point(WorldEntity entity) {
        return Map.of("id", QdrantEntityStore.pointId(entity.id()), "payload", Map.of("entity", json.encode(entity)));
    }

    private static final class Memory implements DualWriteStore.Participant {
        private final Map<String, WorldEntity> values = new LinkedHashMap<>();
        private int reads;
        public DualWriteStore.Change open(String operationId, List<WorldEntity> entities) {
            throw new AssertionError("只读检查不能写入");
        }
        public Optional<WorldEntity> read(String id) {
            reads++;
            return Optional.ofNullable(values.get(id));
        }
    }

    private final class HttpFixture implements AutoCloseable, HttpHandler {
        private final HttpServer server;
        private final HttpClient client;
        private final DualWriteStore store;
        private volatile Object result = List.of();
        private volatile int status = 200;
        private volatile int calls;
        private volatile JsonNode request;
        private volatile String path;
        private volatile String key;

        private HttpFixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this);
            server.start();
            client = HttpClient.newHttpClient();
            var vector = new QdrantEntityStore(client, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "entities", "secret-test", Duration.ofSeconds(2), json, 2, "Cosine");
            store = new DualWriteStore(new Memory(), vector);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            calls++;
            path = exchange.getRequestURI().getPath();
            key = exchange.getRequestHeaders().getFirst("api-key");
            request = json.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = json.encode(Map.of("status", "ok", "result", result)).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            client.close();
            server.stop(0);
        }
    }

    private final class GraphFixture implements InvocationHandler {
        private List<WorldEntity> entities = List.of();
        private String query;
        private Map<?, ?> params;
        private int closed;

        private Driver driver() {
            return proxy(Driver.class, this);
        }

        @Override
        public Object invoke(Object object, Method method, Object[] arguments) {
            switch (method.getName()) {
                case "session": return proxy(Session.class, this);
                case "close": closed++; return null;
                case "executeRead": return ((TransactionCallback<?>) arguments[0]).execute(proxy(TransactionContext.class, this));
                case "run":
                    query = (String) arguments[0];
                    params = (Map<?, ?>) arguments[1];
                    return proxy(Result.class, this);
                case "list":
                    List<Record> records = new ArrayList<>();
                    for (WorldEntity entity : entities) {
                        records.add(proxy(Record.class, new JsonRecord(json.encode(entity))));
                    }
                    return records;
                default: throw new AssertionError(method);
            }
        }
    }

    private record JsonRecord(String encoded) implements InvocationHandler {
        @Override
        public Object invoke(Object object, Method method, Object[] arguments) {
            if (method.getName().equals("get") && arguments[0].equals("json")) {
                return proxy(Value.class, new StringValue(encoded));
            }
            throw new AssertionError(method);
        }
    }

    // 只替代 Value 接口，检查环境不需要加载真实 Bolt 传输实现。
    private record StringValue(String encoded) implements InvocationHandler {
        @Override
        public Object invoke(Object object, Method method, Object[] arguments) {
            if (method.getName().equals("asString")) {
                return encoded;
            }
            throw new AssertionError(method);
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
