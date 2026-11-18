package com.example.myhelper.world;

import com.example.myhelper.agent.AgentCaller;
import com.example.myhelper.orchestration.AgentCallerPlanner;
import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.orchestration.WorldContextPort;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.EntityJson;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.world.model.ContextUnitType;
import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.WorldEntity;
import com.example.myhelper.world.model.WorldObjectType;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

class WorldRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-11-17T02:00:00Z");
    private static final WorldState.Key POWER = new WorldState.Key("device:lamp", "power");
    private static final WorldState.Key CONNECTION = new WorldState.Key("device:lamp", "connectivity");
    private static final Map<String, Object> ROOM = Map.of("tenant", "A");
    private static final WorldState.Policy POLICY = new WorldState.Policy(Set.of("sensor-a", "sensor-b"),
            0.8, Duration.ofMinutes(5), 20, 3, Duration.ofSeconds(1));
    private final MemoryStore memory = new MemoryStore();
    private final DualWriteStore store = new DualWriteStore(memory, memory);
    private final WorldRepository repo = new WorldRepository(store, DualWriteStore.Database.NEO4J, Clock.fixed(NOW, ZoneOffset.UTC));
    private final ContextService context = new ContextService(repo);

    @Test
    void jsonRoundTripSharesStateAndPreservesIndependentObservations() {
        var key = new WorldState.Key("device:lamp", "sample");
        var exact = new BigDecimal("12.123456789012345678901234567");
        var first = observation("obs:a", key, Map.of("z", List.of(exact, true), "a", 1), NOW.minusSeconds(10), "sensor-a", ROOM);
        var second = observation("obs:b", key, Map.of("a", 1.0, "z", List.of(exact, true)), NOW.minusSeconds(1), "sensor-a", ROOM);
        var saved = repo.append(first);
        assertEquals(saved, repo.append(first));
        assertEquals(saved.state(), repo.append(second).state());
        assertNotEquals(WorldRepository.stateId(key, 1), WorldRepository.stateId(key, "1"));
        assertThrows(IllegalStateException.class, new DifferentEvent());
        assertEquals(3, memory.documents.size());
        var history = context.history(new WorldState.Query(key, ROOM, NOW, POLICY));
        assertTrue(history.complete());
        assertEquals(List.of("obs:b", "obs:a"), List.of(history.observations().get(0).observation().id(),
                history.observations().get(1).observation().id()));
        assertEquals(exact, ((List<?>) ((Map<?, ?>) history.observations().getFirst().value()).get("z")).getFirst());
    }

    @Test
    void exactScopeExcludesOtherModulesAndReportsTrustedConflicts() {
        repo.append(observation("obs:trusted", POWER, "ON", NOW.minusSeconds(4), "sensor-a", ROOM));
        repo.append(observation("obs:other-tenant", POWER, "OFF", NOW.minusSeconds(1), "sensor-b", Map.of("tenant", "B")));
        memory.save(new WorldEntity("record:task", null, ContextUnitType.OBSERVATION, null, null, null, null,
                Map.of("kind", "task-event", "subjectId", POWER.subjectId(), "predicate", POWER.predicate(),
                        "scopeKey", WorldValues.canonical(ROOM)), List.of(), null, 1, "sensor-b", List.of(), ROOM, NOW, NOW));
        assertEquals("ON", query(POWER, POLICY).current().value());
        repo.append(observation("obs:conflict", POWER, "OFF", NOW.minusSeconds(2), "sensor-b", ROOM));
        assertEquals(WorldState.Status.CONFLICT, query(POWER, POLICY).status());
        assertEquals(2, query(POWER, POLICY).conflicting().size());
    }

    @Test
    void latestBySourceCannotFallBackAndTruncatedPageCannotClaimCurrent() {
        repo.append(observation("obs:a", POWER, "ON", NOW.minusSeconds(8), "sensor-a", ROOM));
        repo.append(new WorldState.Observation("obs:z", POWER, "OFF", NOW.minusSeconds(6), NOW.minusSeconds(5),
                "sensor-a", ROOM, 0.9, List.of()));
        assertEquals(WorldState.Status.EXPIRED, query(POWER, POLICY).status());
        assertEquals("OFF", query(POWER, POLICY).lastKnown().value());
        assertEquals(WorldState.Status.INCOMPLETE,
                query(POWER, new WorldState.Policy(Set.of(), null, Duration.ofMinutes(5), 1, 1, Duration.ofSeconds(1))).status());
    }

    @Test
    void disconnectedPowerRemainsUnknownAndUnavailableStateRevisionIsExplicit() {
        var stored = repo.append(observation("obs:power", POWER, "ON", NOW.minusSeconds(10), "sensor-a", ROOM));
        repo.append(observation("obs:disconnect", CONNECTION, "DISCONNECTED", NOW.minusSeconds(2), "sensor-a", ROOM));
        assertEquals("DISCONNECTED", query(CONNECTION, POLICY).current().value());
        var unknown = query(POWER, POLICY);
        assertEquals(WorldState.Status.UNKNOWN, unknown.status());
        assertEquals("ON", unknown.lastKnown().value());
        var old = repo.read(stored.state()).orElseThrow();
        memory.save(new WorldEntity(old.id(), null, old.contextUnitType(), null, null, null, null, old.data(),
                old.bindings(), null, 2, null, List.of(), Map.of(), NOW, NOW));
        assertTrue(repo.read(stored.state()).isEmpty());
        assertEquals(WorldState.Status.REVISION_UNAVAILABLE, query(POWER, POLICY).status());
        assertFalse(context.history(new WorldState.Query(POWER, ROOM, NOW, POLICY)).complete());
    }

    @Test
    void springWorldBeanUsesStoredEvidenceInActualPlannerJson() {
        try (var spring = new AnnotationConfigApplicationContext()) {
            spring.getBeanFactory().registerSingleton("store", store);
            spring.getBeanFactory().registerSingleton("selector", new OnlyAuthorizedPower());
            spring.register(WorldModelConfiguration.class);
            spring.refresh();
            var deployed = spring.getBean(WorldRepository.class);
            var now = Instant.now();
            memory.save(new WorldEntity(POWER.subjectId(), WorldObjectType.DEVICE, null, null, null, "灯", null,
                    Map.of(), List.of(), null, 1, null, List.of(), ROOM, now, now));
            deployed.append(observation("obs:authorized", POWER, "ON", now.minusSeconds(2), "sensor-a", ROOM));
            deployed.append(observation("obs:private", POWER, "PRIVATE", now.minusSeconds(1), "sensor-b", Map.of("tenant", "B")));
            var model = new PromptReader();
            var planner = new AgentCallerPlanner(new AgentCaller(new FixedModel(model), List.of()));
            var port = spring.getBean(WorldContextPort.class);
            var planning = new AgentOrchestrator.PlanningContext("run-a", "read authorized", Map.of(),
                    "test", List.of(), List.of(), List.of());
            planner.decide(new AgentOrchestrator.PlanningContext(planning.runId(), planning.goal(), planning.arguments(),
                    planning.modelRef(), planning.toolNames(), planning.candidates(), planning.history(),
                    planning.taskId(), planning.phase(), planning.recovery(), planning.instructions(),
                    planning.toolReliability(), port.snapshot(planning)));
            JsonNode json = model.last;
            assertEquals(1, json.at("/worldContext/observations").size());
            assertEquals("obs:authorized", json.at("/worldContext/observations/0/evidence/id").asText());
            assertEquals(1, json.at("/worldContext/observations/0/evidence/revision").asLong());
            assertEquals("ON", json.at("/worldContext/observations/0/value").asText());
            assertEquals("灯", json.at("/worldContext/references/0/detail/value").asText());
            assertEquals("REFERENCE", json.at("/worldContext/references/0/use").asText());
            assertTrue(json.at("/task").isObject());
            assertTrue(json.at("/availableTools").isArray());
            assertTrue(json.at("/task/worldContext").isMissingNode());
        }
    }

    private WorldState.Result query(WorldState.Key key, WorldState.Policy policy) {
        return context.current(new WorldState.Query(key, ROOM, NOW, policy));
    }

    private static WorldState.Observation observation(String id, WorldState.Key key, Object value, Instant at,
                                                       String source, Map<String, Object> scope) {
        return new WorldState.Observation(id, key, value, at, at.plusSeconds(360), source, scope, 0.9, List.of());
    }

    private final class DifferentEvent implements org.junit.jupiter.api.function.Executable {
        @Override
        public void execute() {
            repo.append(observation("obs:a", new WorldState.Key("device:lamp", "sample"),
                    false, NOW.minusSeconds(10), "sensor-a", ROOM));
        }
    }

    private static final class OnlyAuthorizedPower implements WorldPlanningContext.Selector {
        @Override
        public List<WorldPlanningContext.Selection> select(AgentOrchestrator.PlanningContext context) {
            assertEquals("run-a", context.runId());
            return List.of(new WorldPlanningContext.Selection(POWER, ROOM, POLICY, false));
        }

        @Override
        public List<WorldPlanningContext.ReferenceSelection> references(AgentOrchestrator.PlanningContext context) {
            EntityRef ref = new EntityRef(POWER.subjectId(), 1L, WorldObjectType.DEVICE, null, null, null);
            return List.of(new WorldPlanningContext.ReferenceSelection(DualWriteStore.Database.NEO4J, ref,
                    List.of(List.of("/name")), new WorldReferenceExpander.Budget(0, 2, Duration.ofSeconds(1)),
                    new DeviceNameOnly()));
        }
    }

    private static final class DeviceNameOnly implements WorldReferenceExpander.ReadAccess {
        @Override
        public boolean allows(WorldReferenceExpander.LocatedRef located, String slot) {
            return POWER.subjectId().equals(located.ref().id()) && (slot == null || "/name".equals(slot));
        }
    }

    private record FixedModel(ChatModel model) implements Function<String, ChatModel> {
        @Override
        public ChatModel apply(String name) {
            return model;
        }
    }

    private static final class PromptReader implements ChatModel {
        /** 实际发送给模型替身的 JSON。 */
        private JsonNode last;

        @Override
        public ChatResponse call(Prompt prompt) {
            last = new EntityJson().parse(prompt.getInstructions().get(1).getText());
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("finish-1", "function", "finish_task",
                            "{\"kind\":\"COMPLETE\",\"output\":\"done\"}"))).build())));
        }
    }

    private static final class MemoryStore implements DualWriteStore.Participant {
        /** 按存储协议保存 JSON 文档，读取时重新建立实体。 */
        private final TreeMap<String, String> documents = new TreeMap<>();
        /** 使用真实的公共实体编解码器。 */
        private final EntityJson json = new EntityJson();

        void save(WorldEntity entity) {
            documents.put(entity.id(), json.encode(entity));
        }

        @Override
        public boolean writeConditionally(WorldEntity expected, WorldEntity replacement) {
            if (expected != null || documents.containsKey(replacement.id())) {
                return false;
            }
            save(replacement);
            return true;
        }

        @Override
        public Optional<WorldEntity> read(String id) {
            String body = documents.get(id);
            return body == null ? Optional.empty() : Optional.of(json.entity(body));
        }

        @Override
        public StorageQuery.Page query(StorageQuery.PageRequest request) {
            List<WorldEntity> results = new ArrayList<>();
            String after = request.cursor() == null ? "" : request.cursor().position();
            for (var entry : documents.tailMap(after, false).entrySet()) {
                JsonNode row = json.parse(entry.getValue());
                boolean matches = true;
                for (var match : request.filter().matches()) {
                    if (!match.values().contains(row.at(match.pointer()).asText())) {
                        matches = false;
                        break;
                    }
                }
                if (!matches) {
                    continue;
                }
                if (results.size() == request.limit()) {
                    return new StorageQuery.Page(results, new StorageQuery.Cursor(DualWriteStore.Database.NEO4J,
                            results.getLast().id()));
                }
                results.add(json.entity(entry.getValue()));
            }
            return new StorageQuery.Page(results, null);
        }

        @Override
        public DualWriteStore.Change open(String operationId, List<WorldEntity> entities) {
            throw new UnsupportedOperationException("本检查仅使用条件写");
        }
    }
}
