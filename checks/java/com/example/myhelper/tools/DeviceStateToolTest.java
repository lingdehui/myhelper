package com.example.myhelper.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.gate.DeviceReadGate;
import com.example.myhelper.gate.RuntimeGate;
import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.orchestration.SpringToolExecutor;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.EntityJson;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.world.ContextService;
import com.example.myhelper.world.WorldDeviceDirectory;
import com.example.myhelper.world.WorldDeviceStatus;
import com.example.myhelper.world.WorldRepository;
import com.example.myhelper.world.WorldState;
import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.UnitType;
import com.example.myhelper.world.model.WorldEntity;
import com.example.myhelper.world.model.WorldObjectType;
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

class DeviceStateToolTest {
    /** 初查、测量和复查使用同一固定世界时钟。 */
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    /** 获准读取的家庭范围。 */
    private static final Map<String, Object> HOME = Map.of("tenant", "home");
    /** 世界目录定位到的空调及其电源状态属性。 */
    private static final WorldState.Key POWER = new WorldState.Key("device:living-room-ac", "power");
    /** 仅由宿主映射到同一设备的 Home Assistant 实体。 */
    private static final String EXTERNAL_ENTITY = "climate.living_room_ac";
    /** 本次批准的观测来源。 */
    private static final String SOURCE = "ha:home";
    /** 感知回执的重试保持同一个观测 ID。 */
    private static final String OBSERVATION_ID = "obs:ac-power";
    /** 世界状态认可的传感器 Unit 及其修订。 */
    private static final WorldDeviceStatus.Sensor SENSOR = new WorldDeviceStatus.Sensor(
            new EntityRef("unit:ha-ac-read", 3L, null, null, UnitType.TOOL, null), SOURCE);
    /** 执行器实际绑定的接入实例和版本。 */
    private static final ToolDirectory.ToolRef TOOL = new ToolDirectory.ToolRef("ha_device_state", "home", "3");
    /** 查找和裁决采用固定范围，不接受模型指定的来源或外部实体。 */
    private static final WorldState.Policy POLICY = new WorldState.Policy(Set.of(SOURCE), null,
            Duration.ofMinutes(1), 10, 2, Duration.ofSeconds(2));

    @Test
    void fixedBindingReturnsAnObservationThatWorldCanAppendAndRecheck() {
        Fixture world = new Fixture();
        var refresh = world.refresh(SENSOR);
        var reader = new LocalReader(new HomeAssistantStateReader.Reading(EXTERNAL_ENTITY, "cool", NOW));
        var tool = tool(reader);
        var executor = executor(tool);
        var toolRef = executor.resolveTool(TOOL.toolName()).orElseThrow();
        var call = callContext();

        var outcome = executor.execute(toolRef, arguments(POWER.subjectId()), call);

        assertEquals(TOOL, toolRef);
        assertEquals(AgentOrchestrator.Status.SUCCESS, outcome.status());
        assertEquals(EXTERNAL_ENTITY, reader.entityId);
        assertEquals(call.deadline(), reader.deadline);
        assertEquals(1, reader.calls);
        Map<?, ?> output = assertInstanceOf(Map.class, outcome.value());
        assertEquals("OBSERVED", output.get("status"));
        assertEquals(POWER.subjectId(), output.get("deviceId"));
        assertEquals(POWER.predicate(), output.get("predicate"));
        assertEquals(EXTERNAL_ENTITY, output.get("entityId"));
        assertEquals(NOW.toString(), output.get("observedAt"));
        assertEquals(SOURCE, output.get("source"));
        assertEquals(Boolean.TRUE, output.get("value"));

        var observation = tool.observation(toolRef, refresh, OBSERVATION_ID, output).orElseThrow();
        assertEquals(POWER, observation.key());
        assertEquals(HOME, observation.scope());
        var complete = world.status.complete(refresh, SENSOR.unit(), observation, new Access(SENSOR));
        assertEquals(WorldDeviceStatus.Status.KNOWN, complete.status());
        var current = complete.state().current();
        assertEquals(Boolean.TRUE, current.value());
        assertEquals(OBSERVATION_ID, current.observation().id());
        assertEquals(NOW, current.observedAt());
        assertEquals(SOURCE, current.source());

        var retry = world.status.complete(refresh, SENSOR.unit(), observation, new Access(SENSOR));
        assertEquals(current, retry.state().current());
        var history = world.context.history(new WorldState.Query(POWER, HOME, NOW, POLICY));
        assertTrue(history.complete());
        assertEquals(1, history.observations().size());
        assertEquals(1, reader.calls);
    }

    @Test
    void existingGateCanAuthorizeTheBindingAndHandTheActualReceiptBackToWorld() {
        Fixture world = new Fixture();
        var tool = tool(new LocalReader(new HomeAssistantStateReader.Reading(EXTERNAL_ENTITY, "cool", NOW)));
        var context = callContext();
        var authorization = new DeviceReadGate.Authorization(context.taskId(), context.rootRunId(), world.request(),
                new Access(tool.sensorFor(POWER).orElseThrow()), tool.sensorBinding());
        var gate = new DeviceReadGate(new RejectOrdinaryTasks(), world.status, Set.of(TOOL.toolName()),
                new DeviceReadHost(authorization));
        var call = new RuntimeGate.ToolCall(TOOL.toolName(), TOOL, "客厅空调现在开着吗", arguments(POWER.subjectId()),
                context.taskId(), context.rootRunId(), context.runId(), context.nodeId());
        var wrong = new RuntimeGate.ToolCall(TOOL.toolName(), TOOL, call.goal(), arguments("device:elsewhere"),
                call.taskId(), call.rootRunId(), call.runId(), call.operationId());
        assertFalse(gate.allowTool(wrong));
        assertTrue(gate.allowTool(call));
        assertTrue(gate.beforeToolDispatch(call));

        var outcome = executor(tool).execute(TOOL, call.args(), context);
        assertEquals(AgentOrchestrator.Status.SUCCESS, outcome.status());
        assertTrue(gate.verifyTool(call, outcome.value(), Map.of()));
        assertFalse(gate.verifyTool(call, outcome.value(), Map.of()));
        var state = world.status.read(world.request(), new Access(SENSOR));
        assertEquals(WorldDeviceStatus.Status.KNOWN, state.status());
        assertEquals("device-state:root:read-ac", state.state().current().observation().id());
    }

    @Test
    void staleToolVersionAndDifferentWorldSensorCannotSupplyTheObservation() {
        var reader = new LocalReader(new HomeAssistantStateReader.Reading(EXTERNAL_ENTITY, "cool", NOW));
        var tool = tool(reader);
        var executor = executor(tool);
        var old = new ToolDirectory.ToolRef(TOOL.toolName(), TOOL.sourceInstance(), "2");
        var args = arguments(POWER.subjectId());
        assertEquals(AgentOrchestrator.Status.FAILED, executor.execute(old, args, callContext()).status());
        assertEquals(0, reader.calls);

        var output = executor.execute(TOOL, args, callContext()).value();
        var world = new Fixture();
        var refresh = world.refresh(SENSOR);
        var oldSensor = new WorldDeviceStatus.Sensor(
                new EntityRef(SENSOR.unit().id(), 2L, null, null, UnitType.TOOL, null), SOURCE);
        assertTrue(tool.observation(old, refresh, OBSERVATION_ID, output).isEmpty());
        assertTrue(tool.observation(TOOL, world.refresh(oldSensor), OBSERVATION_ID, output).isEmpty());
    }

    @Test
    void rejectsOldReadingAndReceiptForAnotherEntityOrSource() {
        var world = new Fixture();
        var refresh = world.refresh(SENSOR);
        var stale = tool(new LocalReader(new HomeAssistantStateReader.Reading(EXTERNAL_ENTITY, "off", NOW.minusSeconds(1))));
        var staleResult = executor(stale).execute(TOOL, arguments(POWER.subjectId()), callContext());
        assertEquals("OBSERVED", assertInstanceOf(Map.class, staleResult.value()).get("status"));
        assertTrue(stale.observation(TOOL, refresh, OBSERVATION_ID, staleResult.value()).isEmpty());

        var wrongEntity = tool(new LocalReader(new HomeAssistantStateReader.Reading("climate.study", "off", NOW)));
        var wrongResult = executor(wrongEntity).execute(TOOL, arguments(POWER.subjectId()), callContext());
        assertTrue(wrongEntity.observation(TOOL, refresh, OBSERVATION_ID, wrongResult.value()).isEmpty());

        var valid = tool(new LocalReader(new HomeAssistantStateReader.Reading(EXTERNAL_ENTITY, "off", NOW)));
        var result = executor(valid).execute(TOOL, arguments(POWER.subjectId()), callContext());
        Map<?, ?> receipt = assertInstanceOf(Map.class, result.value());
        assertEquals(Boolean.FALSE, valid.observation(TOOL, refresh, OBSERVATION_ID, receipt).orElseThrow().value());
        var otherSource = new LinkedHashMap<String, Object>();
        for (Object key : receipt.keySet()) {
            otherSource.put((String) key, receipt.get(key));
        }
        otherSource.put("source", "ha:other-home");
        assertTrue(valid.observation(TOOL, refresh, OBSERVATION_ID, otherSource).isEmpty());
        assertTrue(world.graph.read(OBSERVATION_ID).isEmpty());
    }

    @Test
    void unavailableStateAndUnmappedDeviceCannotBeRecordedAsPowerOff() {
        var reader = new LocalReader(new HomeAssistantStateReader.Reading(EXTERNAL_ENTITY, "unavailable", NOW));
        var tool = tool(reader);
        var executor = executor(tool);
        var refresh = new Fixture().refresh(SENSOR);

        var unknown = executor.execute(TOOL, arguments(POWER.subjectId()), callContext());
        assertFalse(assertInstanceOf(Map.class, unknown.value()).containsKey("value"));
        assertTrue(tool.observation(TOOL, refresh, OBSERVATION_ID, unknown.value()).isEmpty());
        assertEquals(1, reader.calls);

        var unmapped = executor.execute(TOOL, arguments("device:unmapped"), callContext());
        assertTrue(tool.observation(TOOL, refresh, OBSERVATION_ID, unmapped.value()).isEmpty());
        assertEquals(1, reader.calls);
    }

    private static DeviceStateTool tool(DeviceStateTool.Reader reader) {
        return new DeviceStateTool(TOOL, SENSOR, List.of(new DeviceStateTool.Mapping(POWER.subjectId(), EXTERNAL_ENTITY)), reader);
    }

    private static SpringToolExecutor executor(DeviceStateTool tool) {
        return SpringToolExecutor.withBindings(List.of(tool.binding()), new DeviceResources(), 1);
    }

    private static AgentOrchestrator.CallContext callContext() {
        return new AgentOrchestrator.CallContext("root", "run", "read-ac", Instant.now().plusSeconds(30), new AtomicReference<>());
    }

    private static Map<String, Object> arguments(String deviceId) {
        return Map.of("deviceId", deviceId, "predicate", POWER.predicate());
    }

    private static final class LocalReader implements DeviceStateTool.Reader {
        /** 模拟外部实际读取的回包，不访问设备服务。 */
        private final HomeAssistantStateReader.Reading reading;
        /** 核对实际派发的设备。 */
        private String entityId;
        /** 核对调用截止时刻被传给读取器。 */
        private Instant deadline;
        /** 防止错误版本、未映射目标和观测重放读设备。 */
        private int calls;

        LocalReader(HomeAssistantStateReader.Reading reading) {
            this.reading = reading;
        }

        @Override
        public HomeAssistantStateReader.Reading read(String entityId, Instant deadline) {
            this.entityId = entityId;
            this.deadline = deadline;
            calls++;
            return reading;
        }
    }

    private static final class DeviceResources implements SpringToolExecutor.ResourceResolver {
        @Override
        public List<String> keys(String tool, Map<String, Object> arguments) {
            return List.of((String) arguments.get("deviceId"));
        }
    }

    private record DeviceReadHost(
            /** 测试只为一个运行提供实际授权。 */
            DeviceReadGate.Authorization grant) implements DeviceReadGate.Host {
        @Override
        public DeviceReadGate.Authorization authorization(String taskId, String rootRunId) {
            return grant;
        }

        @Override
        public boolean isDeviceReadTask(String taskId, String rootRunId) {
            return true;
        }
    }

    private static final class RejectOrdinaryTasks implements RuntimeGate {
        @Override
        public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) {
            return false;
        }

        @Override
        public boolean verify(String goal, Map<String, Object> args, Object output) {
            return false;
        }
    }

    private record Access(
            /** 本次宿主允许使用的世界传感器。 */
            WorldDeviceStatus.Sensor sensor) implements WorldDeviceStatus.Access {
        @Override
        public boolean allowsScope(Map<String, Object> scope) {
            return HOME.equals(scope);
        }

        @Override
        public boolean allows(WorldEntity entity, Map<String, Object> scope) {
            return allowsScope(scope) && HOME.equals(entity.scope());
        }

        @Override
        public boolean allowsRead(WorldState.Key key, Map<String, Object> scope) {
            return allowsScope(scope) && POWER.equals(key);
        }

        @Override
        public Optional<WorldDeviceStatus.Sensor> sensorFor(WorldState.Key key, Map<String, Object> scope) {
            return allowsRead(key, scope) ? Optional.of(sensor) : Optional.empty();
        }
    }

    private static final class Fixture {
        /** 图存储替身用来验证真实观测写入和读取。 */
        private final Memory graph = new Memory();
        /** 查询追加的观测和时效裁决。 */
        private final ContextService context;
        /** 使用真实世界设备状态逻辑取得不可伪造的刷新句柄。 */
        private final WorldDeviceStatus status;

        Fixture() {
            var clock = Clock.fixed(NOW, ZoneOffset.UTC);
            var persistence = new DualWriteStore(graph, new Memory());
            var repository = new WorldRepository(persistence, DualWriteStore.Database.NEO4J, clock);
            context = new ContextService(repository);
            status = new WorldDeviceStatus(new WorldDeviceDirectory(persistence, DualWriteStore.Database.NEO4J), repository, context, clock);
            var device = new WorldEntity(POWER.subjectId(), WorldObjectType.DEVICE, null, null, null,
                    "客厅空调", null, Map.of(), List.of(), null, 1, SOURCE, List.of(), HOME, NOW, NOW);
            assertTrue(graph.writeConditionally(null, device));
        }

        WorldDeviceStatus.Request request() {
            var query = new WorldDeviceDirectory.Query(POWER.subjectId(), null, null, HOME,
                    new WorldDeviceDirectory.Budget(10, 2, Duration.ofSeconds(2)));
            return new WorldDeviceStatus.Request(query, POWER.predicate(), POLICY);
        }

        WorldDeviceStatus.Refresh refresh(WorldDeviceStatus.Sensor sensor) {
            var pending = status.read(request(), new Access(sensor));
            assertEquals(WorldDeviceStatus.Status.NEEDS_REFRESH, pending.status());
            return pending.refresh();
        }
    }

    private static final class Memory implements DualWriteStore.Participant {
        /** 本场景只需单页和按 ID 条件追加。 */
        private final Map<String, WorldEntity> byId = new LinkedHashMap<>();
        /** 读取实际实体合同的 JSON Pointer 字段。 */
        private final EntityJson json = new EntityJson();

        @Override
        public DualWriteStore.Change open(String operationId, List<WorldEntity> entities) {
            throw new AssertionError("观测使用条件追加");
        }

        @Override
        public Optional<WorldEntity> read(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public boolean writeConditionally(WorldEntity expected, WorldEntity replacement) {
            assertNull(expected);
            return byId.putIfAbsent(replacement.id(), replacement) == null;
        }

        @Override
        public StorageQuery.Page query(StorageQuery.PageRequest request) {
            List<WorldEntity> matching = new ArrayList<>();
            for (WorldEntity entity : byId.values()) {
                if (matches(request.filter(), entity)) {
                    matching.add(entity);
                }
            }
            assertTrue(matching.size() <= request.limit());
            return new StorageQuery.Page(matching, null);
        }

        private boolean matches(StorageQuery.Filter filter, WorldEntity entity) {
            var row = json.parse(json.encode(entity));
            for (var term : filter.matches()) {
                if (!term.values().contains(row.at(term.pointer()).asText())) {
                    return false;
                }
            }
            return true;
        }
    }
}
