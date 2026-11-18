package com.example.myhelper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.orchestration.SpringToolExecutor;
import com.example.myhelper.world.model.ContextUnitType;
import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.FieldBinding;
import com.example.myhelper.world.model.UnitType;
import com.example.myhelper.world.model.WorldEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class WorldConditionsTest {
    /** 固定的动作完成时点。 */
    private static final Instant COMPLETED = Instant.parse("2026-11-17T10:00:00Z");
    /** 一次判断可以读取到的截止时点。 */
    private static final Instant AS_OF = COMPLETED.plusSeconds(30);
    /** 不同测试在同一个明确范围内检查灯的亮度。 */
    private static final Map<String, Object> SCOPE = Map.of("room", "卧室");
    /** 测试使用同一对象和单个明确属性。 */
    private static final WorldState.Key KEY = new WorldState.Key("device:lamp", "brightness");
    /** 只接受明确来源、最近一段时间内的观测。 */
    private static final WorldState.Policy POLICY = new WorldState.Policy(Set.of("test:sensor"), 0.8,
            Duration.ofMinutes(2), 100, 2, Duration.ofSeconds(1));

    @Test
    void comparesTypedNumericAndStateOperandsButDoesNotUseEvidenceFromAnotherScope() {
        var fixture = new Fixture();
        var sensed = fixture.observe("observation:brightness:1", new BigDecimal("35.000"), COMPLETED);
        var numeric = fixture.condition("condition:numeric", ContextUnitType.REQUIREMENT,
                Map.of("subjectId", KEY.subjectId(), "predicate", KEY.predicate(), "operator", "GTE", "value", 35),
                List.of());
        var exactState = fixture.condition("condition:state", ContextUnitType.REQUIREMENT,
                Map.of("stateId", sensed.state().id()), List.of(new FieldBinding("/data/state", sensed.state())));
        var wrongType = fixture.condition("condition:text", ContextUnitType.REQUIREMENT,
                Map.of("subjectId", KEY.subjectId(), "predicate", KEY.predicate(), "operator", "EQ", "value", "35"),
                List.of());

        assertEquals(WorldConditions.Status.PASS, fixture.conditions.requirement(request(numeric)).status());
        var decision = fixture.conditions.requirement(request(exactState));
        assertEquals(WorldConditions.Status.PASS, decision.status());
        assertEquals(sensed.observation(), decision.result().current().observation());
        assertEquals(WorldConditions.Status.FAIL, fixture.conditions.requirement(request(wrongType)).status());
        var otherRoom = new WorldConditions.Request(numeric, Map.of("room", "客厅"), AS_OF, POLICY);
        assertEquals(WorldConditions.Reason.OUTSIDE_SCOPE, fixture.conditions.requirement(otherRoom).reason());
    }

    @Test
    void verificationRequestsRefreshAndUsesOnlyObservationsAfterCompletion() {
        var fixture = new Fixture();
        fixture.observe("observation:brightness:1", 20, COMPLETED);
        var expectation = fixture.condition("expectation:brightness", ContextUnitType.EXPECTATION,
                Map.of("subjectId", KEY.subjectId(), "predicate", KEY.predicate(), "operator", "IN", "value", List.of(20, 25),
                        "refreshUnitId", "unit:sense-brightness"), List.of());
        var pending = fixture.conditions.verify(request(expectation), COMPLETED);
        assertEquals(WorldConditions.Status.NEEDS_REFRESH, pending.status());
        assertEquals(WorldConditions.Reason.POST_EXECUTION_EVIDENCE_MISSING, pending.reason());
        assertEquals(expectation, pending.refresh().condition());
        assertEquals(KEY, pending.refresh().key());
        assertEquals("unit:sense-brightness", pending.refresh().unitId());

        var recheck = new WorldConditions.Request(pending.refresh().condition(), SCOPE, AS_OF, POLICY, true);
        var stillMissing = fixture.conditions.verify(recheck, COMPLETED);
        assertEquals(WorldConditions.Status.UNKNOWN, stillMissing.status());
        assertNull(stillMissing.refresh());
        // 宿主根据已核对的刷新请求选择已有本地工具，并把实际返回值作为新观测写回。
        var executor = new SpringToolExecutor(List.of(new LocalBrightnessSensor()), new LampResource(), 1);
        var invocation = new AgentOrchestrator.CallContext("root", "run", "sensor",
                Instant.now().plusSeconds(10), new AtomicReference<>());
        var sensedValue = executor.execute("sense_brightness", Map.of("device", pending.refresh().key().subjectId()), invocation);
        assertEquals(AgentOrchestrator.Status.SUCCESS, sensedValue.status());
        var sensed = fixture.observe("observation:brightness:2", sensedValue.value(), COMPLETED.plusSeconds(1));
        var confirmed = fixture.conditions.verify(recheck, COMPLETED);
        assertEquals(WorldConditions.Status.PASS, confirmed.status());
        assertEquals(sensed.observation(), confirmed.result().current().observation());
    }

    private static final class LocalBrightnessSensor implements ToolCallback {
        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("sense_brightness").description("本地测试亮度传感器")
                    .inputSchema("{}").build();
        }

        @Override
        public String call(String input) {
            return "25";
        }
    }

    private static final class LampResource implements SpringToolExecutor.ResourceResolver {
        @Override
        public List<String> keys(String toolName, Map<String, Object> arguments) {
            return List.of(KEY.subjectId());
        }
    }

    @Test
    void missingPinnedRevisionAndHistoricalObservationNeverSatisfyNotEqual() {
        var fixture = new Fixture();
        var sensed = fixture.observe("observation:brightness:1", 20, COMPLETED.minusSeconds(200));
        EntityRef missing = new EntityRef(sensed.state().id(), 2L, null, ContextUnitType.STATE, null, null);
        var pinned = fixture.condition("requirement:pinned", ContextUnitType.REQUIREMENT,
                Map.of("operator", "NOT_EQ"), List.of(new FieldBinding("/data/state", missing)));
        var live = fixture.condition("requirement:live", ContextUnitType.REQUIREMENT,
                Map.of("subjectId", KEY.subjectId(), "predicate", KEY.predicate(), "operator", "NOT_EQ", "value", 30),
                List.of());
        var absentRevision = fixture.conditions.requirement(request(pinned));
        assertEquals(WorldConditions.Status.UNKNOWN, absentRevision.status());
        assertEquals(WorldConditions.Reason.REVISION_UNAVAILABLE, absentRevision.reason());
        var expired = fixture.conditions.requirement(request(live));
        assertEquals(WorldConditions.Status.UNKNOWN, expired.status());
        assertEquals(WorldState.Status.EXPIRED, expired.result().status());
        assertEquals(sensed.observation(), expired.result().lastKnown().observation());
    }

    @Test
    void preciseRefreshTargetsMustAgreeWithLegacyId() {
        var fixture = new Fixture();
        EntityRef sensor = new EntityRef("unit:sense-brightness", 3L, null, null, UnitType.TOOL, null);
        Map<String, Object> data = Map.of("subjectId", KEY.subjectId(), "predicate", KEY.predicate(),
                "operator", "EQ", "value", 20, "refreshUnitId", sensor.id());
        var condition = fixture.condition("requirement:refresh", ContextUnitType.REQUIREMENT,
                data, List.of(new FieldBinding("/data/refreshUnit", sensor)));
        var refresh = fixture.conditions.requirement(request(condition));
        assertEquals(WorldConditions.Status.NEEDS_REFRESH, refresh.status());
        assertEquals(sensor, refresh.refresh().unit());

        EntityRef otherSensor = new EntityRef("unit:unrelated", 3L, null, null, UnitType.TOOL, null);
        var inconsistent = fixture.condition("requirement:inconsistent", ContextUnitType.REQUIREMENT,
                data, List.of(new FieldBinding("/data/refreshUnit", otherSensor)));
        assertEquals(WorldConditions.Reason.INVALID_CONDITION,
                fixture.conditions.requirement(request(inconsistent)).reason());
    }

    private static WorldConditions.Request request(EntityRef condition) {
        return new WorldConditions.Request(condition, SCOPE, AS_OF, POLICY);
    }

    private static final class Fixture {
        /** 本地记录替身，只供条件行为检查。 */
        private final Memory storage = new Memory();
        /** 真实世界业务仓储，感知通过追加写入。 */
        private final WorldRepository repository = new WorldRepository(new DualWriteStore(storage, new Memory()),
                DualWriteStore.Database.NEO4J, Clock.fixed(AS_OF, ZoneOffset.UTC));
        /** 真实状态裁决与条件比较。 */
        private final WorldConditions conditions = new WorldConditions(repository, new ContextService(repository));

        WorldRepository.Written observe(String id, Object value, Instant observedAt) {
            return repository.append(new WorldState.Observation(id, KEY, value, observedAt, observedAt.plusSeconds(120),
                    "test:sensor", SCOPE, 0.95, List.of()));
        }

        EntityRef condition(String id, ContextUnitType type, Map<String, Object> data, List<FieldBinding> bindings) {
            var entity = new WorldEntity(id, null, type, null, null, null, null, data, bindings, null, 1,
                    "test:condition", List.of(), SCOPE, AS_OF, AS_OF);
            storage.entities.put(id, entity);
            return WorldRepository.reference(entity);
        }
    }

    private static final class Memory implements DualWriteStore.Participant {
        /** 当前测试存储的实体集合。 */
        private final Map<String, WorldEntity> entities = new LinkedHashMap<>();

        @Override
        public DualWriteStore.Change open(String operationId, List<WorldEntity> records) {
            throw new AssertionError("世界观测必须通过条件追加写入");
        }

        @Override
        public boolean writeConditionally(WorldEntity expected, WorldEntity replacement) {
            if (expected != null || entities.containsKey(replacement.id())) {
                return false;
            }
            entities.put(replacement.id(), replacement);
            return true;
        }

        @Override
        public Optional<WorldEntity> read(String id) {
            return Optional.ofNullable(entities.get(id));
        }

        @Override
        public StorageQuery.Page query(StorageQuery.PageRequest request) {
            List<WorldEntity> matches = new ArrayList<>();
            for (WorldEntity entity : entities.values()) {
                if (entity.contextUnitType() == ContextUnitType.OBSERVATION) {
                    matches.add(entity);
                }
            }
            return new StorageQuery.Page(matches, null);
        }
    }
}
