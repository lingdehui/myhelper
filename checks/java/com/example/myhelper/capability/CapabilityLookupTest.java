package com.example.myhelper.capability;

import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.persistence.EntityJson;
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

    @Test void realHttpQueryUsesGeneratedGoalAndLoadsAuthoritativeEntity() throws Exception {
        EntityJson json = new EntityJson();
        var planData = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(plan(), Object.class);
        WorldEntity entity = new WorldEntity("unit:test", null, null, UnitType.PLAN_STEP, null, "候选", "模型子任务", Map.of(
                "publicationStatus", "PUBLISHED", "executionPlan", planData), List.of(), null, 1, "test", List.of(), Map.of(), null, null);
        AtomicReference<WorldEntity> current = new AtomicReference<>(entity);
        AtomicReference<String> embedded = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections/units/points/query", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requests.incrementAndGet();
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = json.encode(Map.of("status", "ok", "result", Map.of("points", List.of(Map.of(
                        "payload", Map.of("entity", json.encode(entity)), "score", 0.9))))).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            }
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            var config = new QdrantCapabilityRepository.Config("test", URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "units", null, Duration.ofSeconds(2), "embed", "v1", 2, 5, 0.5);
            var repo = new QdrantCapabilityRepository(client, config, new Function<String,List<Float>>() {
                @Override
                public List<Float> apply(String text) {
                    embedded.set(text); return List.of(1f, 0f);
                }
            }, new Function<String,Optional<WorldEntity>>() {
                @Override
                public Optional<WorldEntity> apply(String id) {
                    return Optional.ofNullable(current.get());
                }
            });
            var query = new CapabilityLookup.Query("r", 2, "child", "模型新子任务", Map.of());
            var found = repo.search(query);
            assertEquals("模型新子任务", embedded.get());
            assertEquals(1, requests.get());
            assertEquals(5, json.parse(body.get()).path("limit").asInt());
            assertEquals("embed", json.parse(body.get()).path("filter").path("must").get(0).path("match").path("value").asText());
            assertEquals("unit:test", found.getFirst().ref().id());
            assertEquals(plan(), repo.load(found.getFirst().ref()).orElseThrow().plan());
            assertTrue(repo.isAvailable(found.getFirst().ref()));
            current.set(null);
            assertFalse(repo.isAvailable(found.getFirst().ref()));
            assertTrue(repo.load(found.getFirst().ref()).isEmpty());
        } finally { server.stop(0); }
    }

    private static TaskPlan plan() {
        return new TaskPlan("goal", List.of(new TaskPlan.Step("s", "leaf", TaskPlan.Kind.TOOL, "echo", Map.of(), List.of(), Map.of())));
    }
}
