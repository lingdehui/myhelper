package com.example.myhelper.gate;

import static com.example.myhelper.persistence.DualWriteStore.Database.NEO4J;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.EntityJson;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import com.example.myhelper.world.ContextService;
import com.example.myhelper.world.WorldDeviceDirectory;
import com.example.myhelper.world.WorldDeviceStatus;
import com.example.myhelper.world.WorldRepository;
import com.example.myhelper.world.WorldState;
import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.UnitType;
import com.example.myhelper.world.model.WorldEntity;
import com.example.myhelper.world.model.WorldObjectType;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.junit.jupiter.api.Test;

class DeviceReadGateTest {
    /** 初查和复查使用本地固定时点。 */
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    /** 请求授权的精确范围。 */
    private static final Map<String, Object> HOME = Map.of("tenant", "home");
    /** 与请求无关的另一个范围。 */
    private static final Map<String, Object> OTHER_HOME = Map.of("tenant", "other");
    /** 业务授权选定的房间。 */
    private static final String ROOM_ID = "room:living";
    /** 唯一目标的固定设备身份。 */
    private static final EntityRef DEVICE = new EntityRef("device:living-ac", 2L, WorldObjectType.DEVICE, null, null, null);
    /** 越权测试用的另一台设备。 */
    private static final EntityRef OTHER_DEVICE = new EntityRef("device:other-ac", 1L, WorldObjectType.DEVICE, null, null, null);
    /** 当前任务的对象属性对。 */
    private static final WorldState.Key POWER = new WorldState.Key(DEVICE.id(), "power");
    /** 获准测量的来源。 */
    private static final String SOURCE = "sensor:local-ac";
    /** 世界侧保存固定修订的感知工具。 */
    private static final WorldDeviceStatus.Sensor SENSOR = new WorldDeviceStatus.Sensor(
            new EntityRef("unit:local-ac-sensor", 3L, null, null, UnitType.TOOL, null), SOURCE);
    /** 宿主登记的实际感知工具版本。 */
    private static final ToolRef TOOL = new ToolRef("read_ac", "local-ac", "3");
    /** 仅接受登记来源的一分钟内观测。 */
    private static final WorldState.Policy POLICY = new WorldState.Policy(Set.of(SOURCE), 0.8,
            Duration.ofMinutes(1), 50, 2, Duration.ofSeconds(2));
    /** 给定房间与设备线索的一次定位预算。 */
    private static final WorldDeviceDirectory.Budget BUDGET = new WorldDeviceDirectory.Budget(4, 12, Duration.ofSeconds(2));
    /** 本轮任务、根运行与步骤的身份来自宿主。 */
    private static final String TASK_ID = "task:read-ac";
    /** 同一个任务的新根运行必须另行授权。 */
    private static final String ROOT_RUN_ID = "root:read-ac";
    /** 已绑定设备及属性的工具参数。 */
    private static final Map<String, Object> ARGUMENTS = Map.of("subjectId", DEVICE.id(), "predicate", "power", "scope", HOME);

    @Test
    void deniedTaskOrScopeNeverObtainsEvidenceOrARefreshDispatch() {
        Fixture fixture = new Fixture();
        fixture.append("obs:known", true, NOW.minusSeconds(5));
        DeviceReadGate denied = fixture.gate(null);
        RuntimeGate.ToolCall call = call(TOOL, ARGUMENTS, ROOT_RUN_ID, "denied");

        assertTrue(denied.read(TASK_ID, ROOT_RUN_ID).isEmpty());
        assertFalse(denied.allowTool(call));
        assertFalse(denied.beforeToolDispatch(call));
        assertFalse(denied.verifyCompletion(completion(ROOT_RUN_ID), answer(true, "obs:known"), Map.of()));

        DeviceReadGate wrongScope = fixture.gate(fixture.authorization(OTHER_HOME));
        var unavailable = wrongScope.read(TASK_ID, ROOT_RUN_ID).orElseThrow();
        assertEquals(WorldDeviceStatus.Status.UNKNOWN, unavailable.status());
        assertEquals(WorldDeviceStatus.Reason.ACCESS_DENIED, unavailable.reason());
        assertTrue(unavailable.candidates().isEmpty());
        assertFalse(wrongScope.allowTool(call));
    }

    @Test
    void freshPowerStillRequiresTheSameDeviceValueAndObservationInTheAnswer() {
        Fixture fixture = new Fixture();
        fixture.append("obs:known", false, NOW.minusSeconds(5));
        DeviceReadGate gate = fixture.gate(fixture.authorization(HOME));
        var known = gate.read(TASK_ID, ROOT_RUN_ID).orElseThrow();

        assertEquals(WorldDeviceStatus.Status.KNOWN, known.status());
        assertEquals(List.of(DEVICE), known.candidates());
        assertFalse(gate.allowTool(call(TOOL, ARGUMENTS, ROOT_RUN_ID, "unnecessary-sensor")));
        var completion = completion(ROOT_RUN_ID);
        assertTrue(gate.verifyCompletion(completion, answer(false, "obs:known"), Map.of()));
        assertFalse(gate.verifyCompletion(completion, answer(true, "obs:known"), Map.of()));
        assertFalse(gate.verifyCompletion(completion, answer(false, "obs:missing"), Map.of()));
        assertFalse(gate.verifyCompletion(completion, Map.of("subjectId", OTHER_DEVICE.id(), "predicate", "power",
                "value", false, "observationId", "obs:known"), Map.of()));
        assertFalse(gate.verifyCompletion(completion, Map.of("subjectId", DEVICE.id(), "predicate", "power",
                "value", false, "observationId", "obs:known", "text", "空调开着"), Map.of()));
    }

    @Test
    void onlyTheAuthorizedVersionBoundArgumentsAndRootCanDispatchAnExpiredReadingOnce() {
        Fixture fixture = new Fixture();
        fixture.append("obs:expired", false, NOW.minusSeconds(120));
        DeviceReadGate gate = fixture.gate(fixture.authorization(HOME));
        var pending = gate.read(TASK_ID, ROOT_RUN_ID).orElseThrow();
        assertEquals(WorldDeviceStatus.Status.NEEDS_REFRESH, pending.status());
        assertEquals(WorldState.Status.EXPIRED, pending.state().status());
        assertFalse(gate.verifyCompletion(completion(ROOT_RUN_ID), answer(false, "obs:expired"), Map.of()));

        assertDeniedDispatch(gate, call(new ToolRef(TOOL.toolName(), TOOL.sourceInstance(), "2"), ARGUMENTS,
                ROOT_RUN_ID, "old-version"));
        assertDeniedDispatch(gate, call(new ToolRef(TOOL.toolName(), "other-sensor", TOOL.implementationVersion()), ARGUMENTS,
                ROOT_RUN_ID, "wrong-source"));
        assertDeniedDispatch(gate, call(TOOL, Map.of("subjectId", OTHER_DEVICE.id(), "predicate", "power", "scope", HOME),
                ROOT_RUN_ID, "wrong-device"));
        assertDeniedDispatch(gate, call(TOOL, Map.of("subjectId", DEVICE.id(), "predicate", "temperature", "scope", HOME),
                ROOT_RUN_ID, "wrong-property"));
        assertDeniedDispatch(gate, call(TOOL, Map.of("subjectId", DEVICE.id(), "predicate", "power", "scope", OTHER_HOME),
                ROOT_RUN_ID, "wrong-scope"));
        assertDeniedDispatch(gate, new RuntimeGate.ToolCall(TOOL.toolName(), null, "读取客厅空调电源", ARGUMENTS,
                TASK_ID, ROOT_RUN_ID, ROOT_RUN_ID, "unbound-tool"));
        assertDeniedDispatch(gate, call(TOOL, ARGUMENTS, "root:other", "wrong-run"));

        var allowed = call(TOOL, ARGUMENTS, ROOT_RUN_ID, "first-read");
        assertTrue(gate.allowTool(allowed));
        assertTrue(gate.beforeToolDispatch(allowed));
        assertDeniedDispatch(gate, call(TOOL, ARGUMENTS, ROOT_RUN_ID, "second-read"));
    }

    @Test
    void trustedToolReceiptMustMatchTheAuthorizedReadingBeforeWritingEvidence() {
        Fixture fixture = new Fixture();
        DeviceReadGate gate = fixture.gate(fixture.authorization(HOME));
        assertEquals(WorldDeviceStatus.Status.NEEDS_REFRESH, gate.read(TASK_ID, ROOT_RUN_ID).orElseThrow().status());
        var allowed = call(TOOL, ARGUMENTS, ROOT_RUN_ID, "read-with-wrong-receipt");
        assertTrue(gate.allowTool(allowed));
        assertTrue(gate.beforeToolDispatch(allowed));
        var wrongReading = reading("obs:wrong-reading", new WorldState.Key(OTHER_DEVICE.id(), "power"),
                true, NOW, HOME, SOURCE);

        assertFalse(gate.verifyTool(allowed, wrongReading, Map.of()));
        assertTrue(fixture.graph.read(wrongReading.id()).isEmpty());
        assertFalse(gate.verifyCompletion(completion(ROOT_RUN_ID), answer(true, wrongReading.id()), Map.of()));
    }

    @Test
    void usesWrittenAndRecheckedObservationToAcceptTheFinalAnswer() {
        Fixture fixture = new Fixture();
        DeviceReadGate gate = fixture.gate(fixture.authorization(HOME));
        assertEquals(WorldDeviceStatus.Status.NEEDS_REFRESH, gate.read(TASK_ID, ROOT_RUN_ID).orElseThrow().status());
        var allowed = call(TOOL, ARGUMENTS, ROOT_RUN_ID, "read-with-valid-receipt");
        assertTrue(gate.allowTool(allowed));
        assertTrue(gate.beforeToolDispatch(allowed));
        var actual = reading("obs:measured", POWER, true, NOW, HOME, SOURCE);

        var unrelated = call(TOOL, ARGUMENTS, ROOT_RUN_ID, "unrelated-receipt");
        assertFalse(gate.verifyTool(unrelated, actual, Map.of()));
        assertTrue(fixture.graph.read(actual.id()).isEmpty());
        assertTrue(gate.verifyTool(allowed, actual, Map.of()));
        assertTrue(fixture.graph.read(actual.id()).isPresent());
        var result = gate.read(TASK_ID, ROOT_RUN_ID).orElseThrow();
        assertEquals(WorldDeviceStatus.Status.KNOWN, result.status());
        String id = result.state().current().observation().id();
        assertTrue(gate.verifyCompletion(completion(ROOT_RUN_ID), answer(true, id), Map.of()));
        assertFalse(gate.verifyCompletion(completion(ROOT_RUN_ID), answer(false, id), Map.of()));
    }

    private static void assertDeniedDispatch(DeviceReadGate gate, RuntimeGate.ToolCall call) {
        assertFalse(gate.allowTool(call));
        assertFalse(gate.beforeToolDispatch(call));
    }

    private static RuntimeGate.ToolCall call(ToolRef ref, Map<String, Object> args, String rootRunId, String operationId) {
        return new RuntimeGate.ToolCall(ref.toolName(), ref, "读取客厅空调电源", args,
                TASK_ID, rootRunId, rootRunId, operationId);
    }

    private static RuntimeGate.CompletionContext completion(String rootRunId) {
        return new RuntimeGate.CompletionContext("查客厅空调电源", Map.of(), TASK_ID, rootRunId, rootRunId, "complete");
    }

    private static Map<String, Object> answer(boolean value, String observationId) {
        return Map.of("subjectId", DEVICE.id(), "predicate", "power", "value", value, "observationId", observationId);
    }

    private static WorldDeviceStatus.Request request(Map<String, Object> scope) {
        return new WorldDeviceStatus.Request(new WorldDeviceDirectory.Query(null, "空调", "客厅", scope, BUDGET),
                POWER.predicate(), POLICY);
    }

    private static WorldState.Observation reading(String id, WorldState.Key key, boolean value,
            Instant observedAt, Map<String, Object> scope, String source) {
        return new WorldState.Observation(id, key, value, observedAt, observedAt.plusSeconds(60), source,
                scope, 0.95, List.of());
    }

    private static WorldEntity entry(String id, long revision, WorldObjectType type, String name,
            Map<String, Object> data, Map<String, Object> scope) {
        return new WorldEntity(id, type, null, null, null, name, null, data,
                List.of(), null, revision, "test:household", List.of(), scope, NOW, NOW);
    }

    private record TestHost(
            /** 宿主对根任务发放的授权。 */
            DeviceReadGate.Authorization authorization) implements DeviceReadGate.Host {
        @Override
        public DeviceReadGate.Authorization authorization(String taskId, String rootRunId) {
            return TASK_ID.equals(taskId) && ROOT_RUN_ID.equals(rootRunId) ? authorization : null;
        }

        @Override
        public boolean isDeviceReadTask(String taskId, String rootRunId) {
            return TASK_ID.equals(taskId);
        }
    }

    private static final class OtherRuntimePolicy implements RuntimeGate {
        @Override
        public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) {
            return true;
        }

        @Override
        public boolean verify(String goal, Map<String, Object> args, Object output) {
            return true;
        }
    }

    private static final class SensorArguments implements DeviceReadGate.SensorArguments {
        @Override
        public boolean matches(Map<String, Object> args, WorldState.Key key, Map<String, Object> scope) {
            return args.equals(Map.of("subjectId", key.subjectId(), "predicate", key.predicate(), "scope", scope));
        }
    }

    private static final class SensorReading implements DeviceReadGate.SensorReading {
        @Override
        public WorldState.Observation decode(RuntimeGate.ToolCall call, WorldDeviceStatus.Refresh refresh, Object output) {
            if (output instanceof WorldState.Observation reading) {
                return reading;
            }
            return null;
        }
    }

    private static final class Permission implements WorldDeviceStatus.Access {
        @Override
        public boolean allowsScope(Map<String, Object> scope) {
            return HOME.equals(scope);
        }

        @Override
        public boolean allows(WorldEntity entity, Map<String, Object> scope) {
            return allowsScope(scope) && (ROOM_ID.equals(entity.id()) || DEVICE.id().equals(entity.id()));
        }

        @Override
        public boolean allowsRead(WorldState.Key key, Map<String, Object> scope) {
            return allowsScope(scope) && POWER.equals(key);
        }

        @Override
        public Optional<WorldDeviceStatus.Sensor> sensorFor(WorldState.Key key, Map<String, Object> scope) {
            return allowsRead(key, scope) ? Optional.of(SENSOR) : Optional.empty();
        }
    }

    private static final class Fixture {
        /** 设备目录和状态证据读取同一个权威库。 */
        private final Memory graph = new Memory();
        /** 固定复查时点用于区分当前、历史和未来观测。 */
        private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        /** 世界模块使用的统一持久化入口。 */
        private final DualWriteStore store = new DualWriteStore(graph, new Memory());
        /** 通过真实业务接口构建状态定义及观测记录。 */
        private final WorldRepository repository = new WorldRepository(store, NEO4J, clock);
        /** 真实目录定位、证据裁决与观测复查。 */
        private final WorldDeviceStatus world = new WorldDeviceStatus(
                new WorldDeviceDirectory(store, NEO4J), repository, new ContextService(repository), clock);

        private Fixture() {
            graph.save(entry(ROOM_ID, 1, WorldObjectType.ROOM, "会客区", Map.of("aliases", List.of("客厅")), HOME));
            graph.save(entry(DEVICE.id(), DEVICE.revision(), WorldObjectType.DEVICE, "柜式空调",
                    Map.of("aliases", List.of("空调"), "roomId", ROOM_ID), HOME));
            graph.save(entry(OTHER_DEVICE.id(), OTHER_DEVICE.revision(), WorldObjectType.DEVICE, "空调",
                    Map.of("roomId", ROOM_ID), OTHER_HOME));
        }

        private void append(String id, boolean value, Instant at) {
            repository.append(reading(id, POWER, value, at, HOME, SOURCE));
        }

        private DeviceReadGate.Authorization authorization(Map<String, Object> scope) {
            var sensor = new DeviceReadGate.SensorBinding(TOOL, SENSOR, new SensorArguments(), new SensorReading());
            return new DeviceReadGate.Authorization(TASK_ID, ROOT_RUN_ID, request(scope), new Permission(), sensor);
        }

        private DeviceReadGate gate(DeviceReadGate.Authorization authorization) {
            return new DeviceReadGate(new OtherRuntimePolicy(), world, Set.of(TOOL.toolName()), new TestHost(authorization));
        }
    }

    private static final class Memory implements DualWriteStore.Participant {
        /** 用统一实体合同持久化，避免共享可变实体绕开真实字段匹配。 */
        private final TreeMap<String, String> entities = new TreeMap<>();
        /** 真实实体编解码器。 */
        private final EntityJson json = new EntityJson();

        private void save(WorldEntity entity) {
            entities.put(entity.id(), json.encode(entity));
        }

        @Override
        public DualWriteStore.Change open(String operationId, List<WorldEntity> batch) {
            throw new AssertionError("设备观测只通过条件写追加");
        }

        @Override
        public boolean writeConditionally(WorldEntity expected, WorldEntity replacement) {
            if (expected != null || entities.containsKey(replacement.id())) {
                return false;
            }
            save(replacement);
            return true;
        }

        @Override
        public Optional<WorldEntity> read(String id) {
            String value = entities.get(id);
            return value == null ? Optional.empty() : Optional.of(json.entity(value));
        }

        @Override
        public StorageQuery.Page query(StorageQuery.PageRequest request) {
            List<WorldEntity> results = new ArrayList<>();
            String after = request.cursor() == null ? "" : request.cursor().position();
            for (String body : entities.tailMap(after, false).values()) {
                if (!matches(json.parse(body), request.filter())) {
                    continue;
                }
                if (results.size() == request.limit()) {
                    return new StorageQuery.Page(results, new StorageQuery.Cursor(NEO4J, results.getLast().id()));
                }
                results.add(json.entity(body));
            }
            return new StorageQuery.Page(results, null);
        }

        private static boolean matches(JsonNode entity, StorageQuery.Filter filter) {
            for (StorageQuery.Match condition : filter.matches()) {
                JsonNode value = entity.at(condition.pointer());
                if (value.isArray()) {
                    if (!includes(value, condition.values())) {
                        return false;
                    }
                } else if (!condition.values().contains(value.asText())) {
                    return false;
                }
            }
            return true;
        }

        private static boolean includes(JsonNode array, List<?> expected) {
            for (JsonNode value : array) {
                if (expected.contains(value.asText())) {
                    return true;
                }
            }
            return false;
        }
    }
}
