package com.example.myhelper.persistence;

import com.example.myhelper.world.model.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static com.example.myhelper.persistence.DualWriteStore.Database.QDRANT;

/** 通过本地 HTTP 服务验证实际 Qdrant 客户端的请求协议与失败补偿。 */
public final class QdrantEntityStoreCheck {
    public static void main(String[] args) throws Exception {
        EntityJson json = new EntityJson();
        Map<String, String> points = new HashMap<>();
        AtomicBoolean failAfterWrite = new AtomicBoolean();
        AtomicReference<WriteReply> nextWriteReply = new AtomicReference<>(WriteReply.COMPLETED);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections/test", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                int status = 200;
                Object result;
                try {
                    var body = json.parse(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    String path = exchange.getRequestURI().getPath();
                    if (exchange.getRequestMethod().equals("GET")) {
                        result = Map.of("config", Map.of("params", Map.of("vectors", Map.of("size", 2, "distance", "Cosine"))));
                    } else if (exchange.getRequestMethod().equals("PUT")) {
                        if (!exchange.getRequestURI().getQuery().contains("wait=true")) throw new AssertionError();
                        WriteReply reply = nextWriteReply.getAndSet(WriteReply.COMPLETED);
                        if (reply != WriteReply.REJECTED) {
                            for (var point : body.path("points")) points.put(point.path("id").asText(), point.toString());
                        }
                        if (reply == WriteReply.MALFORMED) {
                            byte[] response = "{".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            exchange.sendResponseHeaders(200, response.length);
                            exchange.getResponseBody().write(response);
                            return;
                        }
                        if (failAfterWrite.getAndSet(false) || reply == WriteReply.SERVER_ERROR) status = 500;
                        if (reply == WriteReply.REJECTED) status = 400;
                        result = Map.of("status", reply == WriteReply.ACKNOWLEDGED ? "acknowledged" : "completed");
                    } else if (path.endsWith("/delete")) {
                        for (var id : body.path("points")) points.remove(id.asText());
                        result = Map.of("status", "completed");
                    } else {
                        List<Object> found = new ArrayList<>();
                        // 引用中包含其他 ID，但适配器只允许读取明确请求的实体自身。
                        if (body.path("ids").size() != 1 || !body.path("ids").get(0).asText().equals(QdrantEntityStore.pointId("a"))) {
                            throw new AssertionError("不应展开引用");
                        }
                        for (var id : body.path("ids")) if (points.containsKey(id.asText())) found.add(json.parse(points.get(id.asText())));
                        result = found;
                    }
                    byte[] response = json.encode(Map.of("status", "ok", "result", result)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, response.length);
                    exchange.getResponseBody().write(response);
                } finally { exchange.close(); }
            }
        });
        server.start();
        try {
            QdrantEntityStore vector = new QdrantEntityStore(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test", null, Duration.ofSeconds(3), json);
            vector.initialize(2, "Cosine");
            DualWriteStore.Participant unusedGraph = new DualWriteStore.Participant() {
                public DualWriteStore.Change open(String operationId, List<WorldEntity> entities) {
                    throw new AssertionError("Qdrant 单写不应访问 Neo4j");
                }
                public Optional<WorldEntity> read(String id) {
                    throw new AssertionError("Qdrant 读取不应回退到 Neo4j");
                }
            };
            DualWriteStore store = new DualWriteStore(unusedGraph, vector);
            // 无向量实体也必须能独立写入 Qdrant，正文与多类型引用都要保留。
            WorldEntity payloadOnly = entity(1, false);
            store.write(QDRANT, payloadOnly);
            if (!store.read(QDRANT, "a").orElseThrow().equals(payloadOnly)) throw new AssertionError("无向量实体往返");
            var pointWithoutVector = json.parse(points.get(QdrantEntityStore.pointId("a")));
            if (!pointWithoutVector.path("vector").isObject() || !pointWithoutVector.path("vector").isEmpty()) {
                throw new AssertionError("无向量必须使用空映射");
            }
            WorldEntity first = entity(1, true);
            if (!json.entity(json.encode(first)).equals(first)) throw new AssertionError("实体往返");
            store.write(QDRANT, first);
            if (!vector.read("a").orElseThrow().equals(first)) throw new AssertionError("读取");
            var update = vector.open("update", List.of(entity(2, true)));
            update.prepare(); failAfterWrite.set(true);
            try { update.commit(); throw new AssertionError("应失败"); } catch (IllegalStateException expected) { update.rollback(); }
            if (!vector.read("a").orElseThrow().equals(first)) throw new AssertionError("失败后恢复旧值");
            // 投影必须和被恢复的完整实体在同一个 upsert 中恢复。
            List<String> revisionTerms = new ArrayList<>();
            for (var term : json.parse(points.get(QdrantEntityStore.pointId("a"))).path("payload").path("query_terms")) {
                revisionTerms.add(term.asText());
            }
            if (!revisionTerms.contains("[\"/revision\",\"number\",\"1\"]") || revisionTerms.contains("[\"/revision\",\"number\",\"2\"]")) {
                throw new AssertionError("补偿必须同步恢复查询投影");
            }
            var clear = vector.open("clear", List.of(entity(2, false)));
            clear.prepare(); clear.commit();
            if (!vector.read("a").orElseThrow().equals(entity(2, false))) throw new AssertionError("清除向量仍须保留正文和引用");
            if (!json.parse(points.get(QdrantEntityStore.pointId("a"))).path("vector").isEmpty()) throw new AssertionError("旧向量应移除");
            clear.rollback();
            if (!vector.read("a").orElseThrow().equals(first)) throw new AssertionError("移除后恢复");
            // 补偿也必须保留原先无向量实体，而不是把旧实体当作不存在。
            store.write(QDRANT, payloadOnly);
            var addVector = vector.open("add-vector", List.of(entity(3, true)));
            addVector.prepare(); failAfterWrite.set(true);
            try { addVector.commit(); throw new AssertionError("应失败"); } catch (IllegalStateException expected) { addVector.rollback(); }
            if (!vector.read("a").orElseThrow().equals(payloadOnly)) throw new AssertionError("应恢复无向量快照");
            for (WriteReply reply : List.of(WriteReply.ACKNOWLEDGED, WriteReply.MALFORMED, WriteReply.SERVER_ERROR, WriteReply.REJECTED)) {
                nextWriteReply.set(reply);
                try {
                    store.write(QDRANT, entity(4, true));
                    throw new AssertionError("只有完成回执才能提交成功");
                } catch (DualWriteStore.WriteFailure failure) {
                    if (failure.rollbackCompleted() != (reply == WriteReply.REJECTED) || failure.getSuppressed().length != 0) {
                        throw new AssertionError("补偿完成回执不能消除原操作结果未知: " + reply, failure);
                    }
                }
                if (!vector.read("a").orElseThrow().equals(payloadOnly)) throw new AssertionError("仍应尽力恢复写前状态");
            }
            System.out.println("Qdrant HTTP checks passed: explicit source/target, full references, payload-only insert/update, partial failure compensation, uncertain acknowledgements, no expansion");
        } finally { server.stop(0); }
    }

    private enum WriteReply { COMPLETED, ACKNOWLEDGED, MALFORMED, SERVER_ERROR, REJECTED }

    /** 供两个适配器的字段往返检查复用同一完整实体。 */
    static WorldEntity entity(long revision, boolean vector) {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("missing", null);
        nested.put("values", List.of("值", true, 7));
        // 模块仅往返保存这些元数据，不解释“候选”等业务含义。
        nested.put("referenceMeaning", "候选，非确定事实");
        return new WorldEntity("a", null, null, null, SemanticType.CONTENT, "名称", "内容",
                Map.of("nested", nested), List.of(
                    new FieldBinding("/text", new EntityRef("device", null, WorldObjectType.DEVICE, null, null, null)),
                    new FieldBinding("/text", new EntityRef("b", 2L, null, null, null, SemanticType.CONTENT)),
                    new FieldBinding("/data/nested", new EntityRef("tool", 3L, null, null, UnitType.TOOL, null)),
                    new FieldBinding("/text", new EntityRef("a", null, null, null, null, SemanticType.CONTENT))),
                vector ? new Embedding("model", "v1", List.of(0.1, 0.2)) : null,
                revision, "test", List.of(new EntityRef("observation", 1L, null, ContextUnitType.OBSERVATION, null, null)),
                Map.of("environment", "test"), Instant.parse("2026-11-09T00:00:00Z"), Instant.parse("2026-11-09T01:00:00Z"));
    }
}
