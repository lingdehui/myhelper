package com.example.myhelper.capability;

import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.persistence.EntityJson;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.QdrantEntityStore;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.world.model.Embedding;
import com.example.myhelper.world.model.UnitType;
import com.example.myhelper.world.model.WorldEntity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityLookupTest {
    @Test void cacheIsBoundedSearchRemainsFreshAndRevocationIsNotCached() {
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<String> id = new AtomicReference<>("one");
        AtomicReference<Boolean> published = new AtomicReference<>(true);
        CapabilityLookup.Repository repo = new CapabilityLookup.Repository() {
            public List<CapabilityLookup.Candidate> search(CapabilityLookup.Query query) {
                searches.incrementAndGet();
                return List.of(new CapabilityLookup.Candidate(new CapabilityLookup.Ref("test", id.get(), 1), "goal", Map.of()));
            }
            public Optional<CapabilityLookup.Definition> load(CapabilityLookup.Ref ref) {
                loads.incrementAndGet(); return Optional.of(new CapabilityLookup.Definition(ref, plan()));
            }
            public boolean isAvailable(CapabilityLookup.Ref ref) { return published.get(); }
        };
        CapabilityLookup lookup = new CapabilityLookup(repo, new CapabilityLookup.Matcher() {
            @Override
            public boolean matches(CapabilityLookup.Query q, CapabilityLookup.Candidate c) {
                return true;
            }
        }, 1, Duration.ofMinutes(1));
        var query = new CapabilityLookup.Query("r", 0, "$entry", "goal", Map.of());
        var first = lookup.searchAtEntry(query).selected().orElseThrow();
        lookup.searchAtEntry(query);
        assertEquals(2, searches.get());
        assertEquals(1, loads.get());
        published.set(false);
        assertFalse(lookup.isAvailable(first.ref()));
        id.set("two");
        lookup.searchAtEntry(query);
        assertEquals(1, lookup.cachedCount());
        id.set("one");
        lookup.searchAtEntry(query);
        assertEquals(3, loads.get());
    }

    @Test void refusesDifferentRevisionInsteadOfSilentlyReplacingCachedDefinition() {
        CapabilityLookup.Ref requested = new CapabilityLookup.Ref("test", "id", 1);
        CapabilityLookup lookup = new CapabilityLookup(new CapabilityLookup.Repository() {
            public List<CapabilityLookup.Candidate> search(CapabilityLookup.Query q) { return List.of(new CapabilityLookup.Candidate(requested, "goal", Map.of())); }
            public Optional<CapabilityLookup.Definition> load(CapabilityLookup.Ref ref) { return Optional.of(new CapabilityLookup.Definition(new CapabilityLookup.Ref("test", "id", 2), plan())); }
            public boolean isAvailable(CapabilityLookup.Ref ref) { return true; }
        }, new CapabilityLookup.Matcher() {
            @Override
            public boolean matches(CapabilityLookup.Query q, CapabilityLookup.Candidate c) {
                return true;
            }
        }, 1, Duration.ofMinutes(1));
        assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                lookup.searchAtEntry(new CapabilityLookup.Query("r", 0, "$entry", "goal", Map.of()));
            }
        });
    }

    @Test void candidateThatDisappearsDuringLoadDoesNotHideOtherMatches() {
        var stale = new CapabilityLookup.Candidate(new CapabilityLookup.Ref("test", "stale", 1), "stale", Map.of());
        var available = new CapabilityLookup.Candidate(new CapabilityLookup.Ref("test", "available", 1), "available", Map.of());
        AtomicReference<List<CapabilityLookup.Candidate>> candidates = new AtomicReference<>(List.of(stale, available));
        CapabilityLookup lookup = new CapabilityLookup(new CapabilityLookup.Repository() {
            public List<CapabilityLookup.Candidate> search(CapabilityLookup.Query query) { return candidates.get(); }
            public Optional<CapabilityLookup.Definition> load(CapabilityLookup.Ref ref) {
                return ref.equals(available.ref()) ? Optional.of(new CapabilityLookup.Definition(ref, plan())) : Optional.empty();
            }
            public boolean isAvailable(CapabilityLookup.Ref ref) { return true; }
        }, new CapabilityLookup.Matcher() {
            public boolean matches(CapabilityLookup.Query query, CapabilityLookup.Candidate candidate) { return true; }
        }, 1, Duration.ofMinutes(1));
        var query = new CapabilityLookup.Query("r", 0, "$entry", "goal", Map.of());
        var match = lookup.searchAtEntry(query);
        assertEquals(available.ref(), match.selected().orElseThrow().ref());
        assertEquals(List.of(available), match.candidates());

        candidates.set(List.of(stale));
        var noMatch = lookup.searchAtEntry(query);
        assertTrue(noMatch.selected().isEmpty());
        assertTrue(noMatch.candidates().isEmpty());
    }

    @Test void vectorCandidatesAreCheckedAgainstOneAuthoritativeBatchInRankOrder() {
        var staleIndex = published("stale", "旧", 1, "PUBLISHED");
        var revokedIndex = published("revoked", "停用前", 1, "PUBLISHED");
        var missingIndex = published("missing", "已删除", 1, "PUBLISHED");
        var firstIndex = published("z", "索引描述", 1, "PUBLISHED");
        var secondIndex = published("a", "另一个索引描述", 1, "PUBLISHED");
        var currentFirst = published("z", "权威描述", 1, "PUBLISHED");
        var currentSecond = published("a", "权威描述二", 1, "PUBLISHED");
        var graph = new SearchParticipant() {
            @Override
            public Map<String, WorldEntity> readBatch(List<String> ids) {
                assertEquals(List.of("stale", "revoked", "missing", "z", "a"), ids);
                return Map.of("stale", published("stale", "新", 2, "PUBLISHED"),
                        "revoked", published("revoked", "已停用", 1, "DISABLED"),
                        "a", currentSecond, "z", currentFirst);
            }
        };
        var vector = new SearchParticipant() {
            @Override
            public List<StorageQuery.Hit> search(StorageQuery.VectorRequest request) {
                assertEquals(2, request.filter().matches().size());
                return List.of(new StorageQuery.Hit(staleIndex, 0.99), new StorageQuery.Hit(revokedIndex, 0.98),
                        new StorageQuery.Hit(missingIndex, 0.97), new StorageQuery.Hit(firstIndex, 0.96),
                        new StorageQuery.Hit(secondIndex, 0.95));
            }
        };
        var adapter = QdrantCapabilityRepository.withTextEmbedding(new DualWriteStore(graph, vector),
                new QdrantCapabilityRepository.SearchConfig("test", DualWriteStore.Database.NEO4J, 2, 5, 0),
                new Function<String, Embedding>() {
                    public Embedding apply(String text) { return new Embedding("example", "v1", List.of(0.0, 1.0)); }
                });
        var found = adapter.search(new CapabilityLookup.Query("r", 0, "$entry", "goal", Map.of()));
        assertEquals(2, found.size());
        assertEquals("z", found.getFirst().ref().id());
        assertEquals("权威描述", found.getFirst().description());
        assertEquals("a", found.getLast().ref().id());
    }

    @Test void realHttpQueryUsesSharedEmbeddingAndLoadsAuthoritativeEntity() throws Exception {
        EntityJson json = new EntityJson();
        var planData = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(plan(), Object.class);
        WorldEntity entity = new WorldEntity("unit:test", null, null, UnitType.PLAN_STEP, null, "候选", "模型子任务", Map.of(
                "publicationStatus", "PUBLISHED", "executionPlan", planData), List.of(), null, 1, "test", List.of(), Map.of(), null, null);
        AtomicReference<WorldEntity> current = new AtomicReference<>(entity);
        AtomicReference<String> embedded = new AtomicReference<>();
        AtomicReference<Embedding> embedding = new AtomicReference<>(new Embedding("embed", "v1", List.of(1.0, 0.0)));
        AtomicReference<String> body = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections/units/points/query", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requests.incrementAndGet();
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = json.encode(Map.of("status", "ok", "result", Map.of("points", List.of(Map.of(
                        "id", QdrantEntityStore.pointId(entity.id()), "payload", Map.of("entity", json.encode(entity)), "score", 0.9))))).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient();
             AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            var vector = new QdrantEntityStore(client, URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "units", null, Duration.ofSeconds(2), json, 2, "Cosine");
            var graph = new DualWriteStore.Participant() {
                public DualWriteStore.Change open(String operationId, List<WorldEntity> entities) {
                    throw new AssertionError("查询不得写库");
                }
                public Optional<WorldEntity> read(String id) {
                    assertEquals(entity.id(), id);
                    return Optional.ofNullable(current.get());
                }
            };
            var store = new DualWriteStore(graph, vector);
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("local-check", Map.of(
                    "qdrant.collection", "units",
                    "qdrant.vector-size", 2,
                    "myhelper.capability.limit", 5,
                    "myhelper.capability.score-threshold", 0.5)));
            context.getBeanFactory().registerSingleton("dualWriteStore", store);
            context.getBeanFactory().registerSingleton("textEmbedding", new Function<String, Embedding>() {
                @Override
                public Embedding apply(String text) {
                    embedded.set(text);
                    return embedding.get();
                }
            });
            context.register(CapabilityRepositoryConfiguration.class);
            context.refresh();
            var repo = context.getBean(QdrantCapabilityRepository.class);
            assertNull(embedded.get());
            assertEquals(0, requests.get());
            var query = new CapabilityLookup.Query("r", 2, "child", "模型新子任务", Map.of());
            var found = repo.search(query);
            assertEquals("模型新子任务", embedded.get());
            assertEquals(1, requests.get());
            assertEquals(5, json.parse(body.get()).path("limit").asInt());
            assertEquals("embed", json.parse(body.get()).path("filter").path("must").get(0).path("match").path("value").asText());
            assertEquals("v1", json.parse(body.get()).path("filter").path("must").get(1).path("match").path("value").asText());
            var conditions = json.parse(body.get()).path("filter").path("must");
            assertEquals("query_terms", conditions.get(2).path("key").asText());
            assertEquals("[\"/unitType\",\"string\",\"PLAN_STEP\"]", conditions.get(2).path("match").path("any").get(0).asText());
            assertEquals("[\"/data/publicationStatus\",\"string\",\"PUBLISHED\"]", conditions.get(3).path("match").path("any").get(0).asText());
            assertEquals(1.0, json.parse(body.get()).path("query").get(0).asDouble());
            assertEquals(0.0, json.parse(body.get()).path("query").get(1).asDouble());
            assertEquals("unit:test", found.getFirst().ref().id());
            assertEquals("units", found.getFirst().ref().namespace());
            assertEquals(plan(), repo.load(found.getFirst().ref()).orElseThrow().plan());
            assertTrue(repo.isAvailable(found.getFirst().ref()));
            current.set(null);
            assertFalse(repo.isAvailable(found.getFirst().ref()));
            assertTrue(repo.load(found.getFirst().ref()).isEmpty());

            embedding.set(new Embedding("upgraded-embed", "v2", List.of(0.0, 1.0)));
            repo.search(query);
            assertEquals(2, requests.get());
            var filter = json.parse(body.get()).path("filter").path("must");
            assertEquals("upgraded-embed", filter.get(0).path("match").path("value").asText());
            assertEquals("v2", filter.get(1).path("match").path("value").asText());

            List<Embedding> incompatible = List.of(
                    new Embedding("embed", "v1", List.of(1.0)),
                    new Embedding("embed", "v1", List.of(Double.MAX_VALUE, 0.0)));
            for (Embedding invalid : incompatible) {
                embedding.set(invalid);
                assertThrows(IllegalArgumentException.class, new Executable() {
                    @Override
                    public void execute() {
                        repo.search(query);
                    }
                });
                assertEquals(2, requests.get(), "不兼容的向量不得发给数据库");
            }
        } finally { server.stop(0); }
    }

    private static TaskPlan plan() {
        return new TaskPlan("goal", List.of(new TaskPlan.Step("s", "leaf", TaskPlan.Kind.TOOL, "echo", Map.of(), List.of(), Map.of())));
    }

    private static WorldEntity published(String id, String text, long revision, String status) {
        return new WorldEntity(id, null, null, UnitType.PLAN_STEP, null, id, text,
                Map.of("publicationStatus", status, "executionPlan", Map.of("goal", "goal")),
                List.of(), null, revision, "test", List.of(), Map.of(), null, null);
    }

    private abstract static class SearchParticipant implements DualWriteStore.Participant {
        public DualWriteStore.Change open(String operationId, List<WorldEntity> entities) {
            throw new AssertionError("能力查询只读");
        }

        public Optional<WorldEntity> read(String id) {
            throw new AssertionError("召回应批量读取候选权威版本");
        }
    }
}
