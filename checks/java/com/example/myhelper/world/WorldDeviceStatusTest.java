package com.example.myhelper.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.orchestration.SpringToolExecutor;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.EntityJson;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.tools.ToolDirectory;
import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.UnitType;
import com.example.myhelper.world.model.WorldEntity;
import com.example.myhelper.world.model.WorldObjectType;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class WorldDeviceStatusTest {
    /** 初查和本地测量以同一固定时刻为准。 */
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    /** 仅查询测试家庭中的客厅设备。 */
    private static final Map<String, Object> HOME = Map.of("tenant", "home");
    /** 已登记的客厅空调。 */
    private static final WorldState.Key POWER = new WorldState.Key("device:living-room-ac", "power");
    /** 本地替身对应的观测来源。 */
    private static final String SENSOR_SOURCE = "sensor:local-ac";
    /** 世界证据保存的固定工具实体修订。 */
    private static final EntityRef SENSOR_REF = new EntityRef("unit:local-ac-sensor", 3L, null, null, UnitType.TOOL, null);
    /** 检查宿主使用同次登记的回调及执行实现版本。 */
    private static final ToolDirectory.ToolRef ACTUAL_TOOL = new ToolDirectory.ToolRef("local_ac_read", "local-ac", "3");
    /** 状态查询接受的来源、置信度、有效年龄和分页预算。 */
    private static final WorldState.Policy POLICY = new WorldState.Policy(Set.of(SENSOR_SOURCE), 0.8,
            Duration.ofMinutes(1), 50, 2, Duration.ofSeconds(2));
    /** 设备定位和别名合并的查询预算。 */
    private static final WorldDeviceDirectory.Budget DIRECTORY_BUDGET = new WorldDeviceDirectory.Budget(1, 20, Duration.ofSeconds(2));
    /** 允许在当前范围定位并读取目标属性，也提供感知授权。 */
    private static final Permission ALLOWED = new Permission(true, true, true);

    @Test
    void returnsFreshLivingRoomAcPowerWithExactObservationAndSource() {
        Fixture fixture = new Fixture();
        var written = fixture.repository.append(reading("obs:fresh", "ON", NOW.minusSeconds(5), HOME, SENSOR_SOURCE));

        var result = fixture.status.read(request(), ALLOWED);

        assertEquals(WorldDeviceStatus.Status.KNOWN, result.status());
        assertEquals(POWER.subjectId(), result.candidates().getFirst().id());
        assertEquals("ON", result.state().current().value());
        assertEquals(NOW.minusSeconds(5), result.state().current().observedAt());
        assertEquals(written.observation(), result.state().current().observation());
        assertEquals(SENSOR_SOURCE, result.state().current().source());
        assertNull(result.refresh());
    }

    @Test
    void expiredReadingRefreshesViaOneLocalToolAndRetriesTheSameReceiptIdempotently() {
        Fixture fixture = new Fixture();
        fixture.repository.append(reading("obs:expired", "OFF", NOW.minusSeconds(120), HOME, SENSOR_SOURCE));

        var pending = fixture.status.read(request(), ALLOWED);
        assertEquals(WorldDeviceStatus.Status.NEEDS_REFRESH, pending.status());
        assertEquals(WorldState.Status.EXPIRED, pending.state().status());
        assertEquals("OFF", pending.state().lastKnown().value());
        var refresh = pending.refresh();
        assertEquals(SENSOR_REF, refresh.sensor().unit());

        AcSensor tool = new AcSensor();
        var executor = SpringToolExecutor.withBindings(List.of(new ToolDirectory.Binding(ACTUAL_TOOL, tool)), new AcResources(), 1);
        var call = new AgentOrchestrator.CallContext("root", "run", "read-ac", Instant.now().plusSeconds(30), new AtomicReference<>());
        var actual = executor.execute(ACTUAL_TOOL, Map.of("subjectId", refresh.query().key().subjectId(),
                "predicate", refresh.query().key().predicate()), call);
        assertEquals(AgentOrchestrator.Status.SUCCESS, actual.status());
        assertEquals(1, tool.calls);
        Map<?, ?> receipt = (Map<?, ?>) actual.value();
        var observed = reading((String) receipt.get("id"), (String) receipt.get("value"),
                Instant.parse((String) receipt.get("observedAt")), HOME, (String) receipt.get("source"));

        var complete = fixture.status.complete(refresh, SENSOR_REF, observed, ALLOWED);
        assertEquals(WorldDeviceStatus.Status.KNOWN, complete.status());
        assertEquals("ON", complete.state().current().value());
        assertEquals("obs:from-tool", complete.state().current().observation().id());
        assertNull(complete.refresh());
        var retry = fixture.status.complete(refresh, SENSOR_REF, observed, ALLOWED);
        assertEquals(complete.state().current(), retry.state().current());
        var history = fixture.context.history(new WorldState.Query(POWER, HOME, NOW, POLICY));
        assertTrue(history.complete());
        assertEquals(2, history.observations().size());
        assertEquals(1, tool.calls);
    }

    @Test
    void distinguishesAmbiguousDevicesFromDeniedAccessWithoutReturningEvidence() {
        Fixture fixture = new Fixture();
        fixture.store.save(device("device:portable-ac", "移动空调"));

        var ambiguous = fixture.status.read(request(), ALLOWED);
        assertEquals(WorldDeviceStatus.Status.AMBIGUOUS, ambiguous.status());
        assertEquals(WorldDeviceStatus.Reason.MULTIPLE_DEVICES, ambiguous.reason());
        assertEquals(2, ambiguous.candidates().size());
        assertNull(ambiguous.state());
        assertNull(ambiguous.refresh());

        var deniedScope = fixture.status.read(request(), new Permission(false, false, false));
        assertEquals(WorldDeviceStatus.Reason.ACCESS_DENIED, deniedScope.reason());
        assertTrue(deniedScope.candidates().isEmpty());
        var exact = new WorldDeviceStatus.Request(new WorldDeviceDirectory.Query(POWER.subjectId(), null, null,
                HOME, DIRECTORY_BUDGET), POWER.predicate(), POLICY);
        var deniedProperty = fixture.status.read(exact, new Permission(true, false, true));
        assertEquals(WorldDeviceStatus.Reason.ACCESS_DENIED, deniedProperty.reason());
        assertTrue(deniedProperty.candidates().isEmpty());
        assertNull(deniedProperty.state());
        assertNull(deniedProperty.refresh());
    }

    @Test
    void rejectsWrongSensorReadingAndRevokedPermissionBeforeWritingEvidence() {
        Fixture fixture = new Fixture();
        var noSensor = fixture.status.read(request(), new Permission(true, true, false));
        assertEquals(WorldDeviceStatus.Reason.SENSOR_UNAVAILABLE, noSensor.reason());
        var refresh = fixture.status.read(request(), ALLOWED).refresh();
        var data = reading("obs:unexpected", "ON", NOW, HOME, SENSOR_SOURCE);
        EntityRef oldSensor = new EntityRef(SENSOR_REF.id(), 2L, null, null, UnitType.TOOL, null);

        assertRejected(fixture, refresh, oldSensor, data);
        assertRejected(fixture, refresh, SENSOR_REF, reading(data.id(), "ON", NOW, HOME, "unapproved:source"));
        assertRejected(fixture, refresh, SENSOR_REF, reading(data.id(), "ON", NOW, Map.of("tenant", "other"), SENSOR_SOURCE));
        assertRejected(fixture, refresh, SENSOR_REF, reading(data.id(), "ON", NOW.minusSeconds(1), HOME, SENSOR_SOURCE));
        var revoked = fixture.status.complete(refresh, SENSOR_REF, data, new Permission(true, true, false));
        assertEquals(WorldDeviceStatus.Reason.ACCESS_DENIED, revoked.reason());
        assertTrue(fixture.store.read(data.id()).isEmpty());
    }

    private static void assertRejected(Fixture fixture, WorldDeviceStatus.Refresh refresh,
                                       EntityRef tool, WorldState.Observation reading) {
        var result = fixture.status.complete(refresh, tool, reading, ALLOWED);
        assertEquals(WorldDeviceStatus.Status.UNKNOWN, result.status());
        assertEquals(WorldDeviceStatus.Reason.UNVERIFIED_READING, result.reason());
        assertTrue(fixture.store.read(reading.id()).isEmpty());
    }

    private static WorldDeviceStatus.Request request() {
        return new WorldDeviceStatus.Request(new WorldDeviceDirectory.Query(null, "空调", "客厅", HOME, DIRECTORY_BUDGET),
                "power", POLICY);
    }

    private static WorldState.Observation reading(String id, String value, Instant at, Map<String, Object> scope, String source) {
        return new WorldState.Observation(id, POWER, value, at, at.plusSeconds(60), source, scope, 0.95, List.of());
    }

    private static WorldEntity device(String id, String name) {
        return entity(id, WorldObjectType.DEVICE, name, Map.of("aliases", List.of("空调"), "roomId", "room:living"));
    }

    private static WorldEntity entity(String id, WorldObjectType type, String name, Map<String, Object> data) {
        return new WorldEntity(id, type, null, null, null, name, null, data,
                List.of(), null, 1, "test:household", List.of(), HOME, NOW, NOW);
    }

    private record Permission(
            /** 当前家庭范围是否获准。 */
            boolean directoryAllowed,
            /** 是否允许读取目标属性及裁决证据。 */
            boolean observationAllowed,
            /** 是否允许使用给定版本的感知工具。 */
            boolean sensorAllowed) implements WorldDeviceStatus.Access {
        @Override
        public boolean allowsScope(Map<String, Object> scope) {
            return directoryAllowed && HOME.equals(scope);
        }

        @Override
        public boolean allows(WorldEntity entity, Map<String, Object> scope) {
            return allowsScope(scope) && HOME.equals(entity.scope());
        }

        @Override
        public boolean allowsRead(WorldState.Key key, Map<String, Object> scope) {
            return observationAllowed && allowsScope(scope) && POWER.equals(key);
        }

        @Override
        public Optional<WorldDeviceStatus.Sensor> sensorFor(WorldState.Key key, Map<String, Object> scope) {
            if (sensorAllowed && allowsRead(key, scope)) {
                return Optional.of(new WorldDeviceStatus.Sensor(SENSOR_REF, SENSOR_SOURCE));
            }
            return Optional.empty();
        }
    }

    private static final class AcSensor implements ToolCallback {
        /** 确认重新提交同一观测不触发第二次工具调用。 */
        private int calls;

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(ACTUAL_TOOL.toolName()).description("本地空调感知替身").inputSchema("{}").build();
        }

        @Override
        public String call(String input) {
            var args = new EntityJson().parse(input);
            assertEquals(POWER.subjectId(), args.path("subjectId").asText());
            assertEquals(POWER.predicate(), args.path("predicate").asText());
            calls++;
            return """
                    {"id":"obs:from-tool","value":"ON","observedAt":"2026-11-17T10:00:00Z","source":"sensor:local-ac"}
                    """;
        }
    }

    private static final class AcResources implements SpringToolExecutor.ResourceResolver {
        @Override
        public List<String> keys(String tool, Map<String, Object> args) {
            return List.of(POWER.subjectId());
        }
    }

    private static final class Fixture {
        /** 固定查询与测量时刻，避免等待真实时间。 */
        private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        /** 通过真实实体编解码和统一存储合同读写本地内存。 */
        private final Memory store = new Memory();
        /** 设备目录和世界状态共用的权威存储。 */
        private final DualWriteStore persistence = new DualWriteStore(store, new Memory());
        /** 标准状态及观测业务仓储。 */
        private final WorldRepository repository = new WorldRepository(persistence, DualWriteStore.Database.NEO4J, clock);
        /** 当前裁决及独立观测历史。 */
        private final ContextService context = new ContextService(repository);
        /** 同一来源下的设备查询与状态接口。 */
        private final WorldDeviceStatus status = new WorldDeviceStatus(
                new WorldDeviceDirectory(persistence, DualWriteStore.Database.NEO4J), repository, context, clock);

        Fixture() {
            store.save(entity("room:living", WorldObjectType.ROOM, "会客区", Map.of("aliases", List.of("客厅"))));
            store.save(device(POWER.subjectId(), "柜式空调"));
        }
    }

    private static final class Memory implements DualWriteStore.Participant {
        /** 本地持久化替身存 JSON，读取时恢复实际统一实体合同。 */
        private final TreeMap<String, String> values = new TreeMap<>();
        /** 使用生产实体编解码器验证观测和设备的字段形状。 */
        private final EntityJson json = new EntityJson();

        void save(WorldEntity entity) {
            values.put(entity.id(), json.encode(entity));
        }

        @Override
        public DualWriteStore.Change open(String operationId, List<WorldEntity> entities) {
            throw new AssertionError("感知记录通过条件写入追加");
        }

        @Override
        public boolean writeConditionally(WorldEntity expected, WorldEntity replacement) {
            if (expected != null || values.containsKey(replacement.id())) {
                return false;
            }
            save(replacement);
            return true;
        }

        @Override
        public Optional<WorldEntity> read(String id) {
            String body = values.get(id);
            return body == null ? Optional.empty() : Optional.of(json.entity(body));
        }

        @Override
        public StorageQuery.Page query(StorageQuery.PageRequest request) {
            List<WorldEntity> matches = new ArrayList<>();
            String after = request.cursor() == null ? "" : request.cursor().position();
            for (String body : values.tailMap(after, false).values()) {
                if (!matches(body, request.filter())) {
                    continue;
                }
                if (matches.size() == request.limit()) {
                    return new StorageQuery.Page(matches, new StorageQuery.Cursor(DualWriteStore.Database.NEO4J,
                            matches.getLast().id()));
                }
                matches.add(json.entity(body));
            }
            return new StorageQuery.Page(matches, null);
        }

        private boolean matches(String body, StorageQuery.Filter filter) {
            var row = json.parse(body);
            for (var term : filter.matches()) {
                var value = row.at(term.pointer());
                if (value.isArray()) {
                    boolean contains = false;
                    for (var item : value) {
                        if (term.values().contains(item.asText())) {
                            contains = true;
                            break;
                        }
                    }
                    if (!contains) {
                        return false;
                    }
                } else if (!term.values().contains(value.asText())) {
                    return false;
                }
            }
            return true;
        }
    }
}
