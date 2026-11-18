package com.example.myhelper.world;

import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.world.WorldDeviceDirectory.Access;
import com.example.myhelper.world.WorldDeviceDirectory.Budget;
import com.example.myhelper.world.WorldDeviceDirectory.Query;
import com.example.myhelper.world.WorldDeviceDirectory.Status;
import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.WorldEntity;
import com.example.myhelper.world.model.WorldObjectType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static com.example.myhelper.persistence.DualWriteStore.Database.NEO4J;
import static com.example.myhelper.world.model.WorldObjectType.DEVICE;
import static com.example.myhelper.world.model.WorldObjectType.ROOM;
import static org.junit.jupiter.api.Assertions.*;

class WorldDeviceDirectoryTest {
    /** 用于验证权限在同一请求范围内核对。 */
    private static final Map<String, Object> SCOPE = Map.of("tenant", "home");
    /** 普通查询按单实体分页，能覆盖跨页、名称与别名合并。 */
    private static final Budget STANDARD = new Budget(1, 12, Duration.ofSeconds(5));

    @Test
    void intersectsRoomAndExactAliasAndDeduplicatesMatches() {
        WorldEntity room = room("r1", "Living", "Lounge");
        WorldEntity other = room("r2", "Study");
        WorldEntity selected = device("first", "AC", "r1", "AC", "Cooling");
        WorldEntity ignored = device("second", "AC", "r2", "Cooling");
        Fixture fixture = new Fixture(List.of(room, other, selected, ignored));

        var found = fixture.directory.find(new Query(null, "AC", "Lounge", SCOPE, STANDARD), permitted());

        assertEquals(Status.FOUND, found.status());
        assertEquals(List.of(new EntityRef("first", 2L, DEVICE, null, null, null)), found.candidates());
        assertEquals(0, fixture.vector.calls);
        assertEquals(Status.FOUND,
                fixture.directory.find(new Query(null, "Cooling", "r1", SCOPE, STANDARD), permitted()).status());
        assertEquals(Status.NOT_FOUND,
                fixture.directory.find(new Query(null, "Cool", "Lounge", SCOPE, STANDARD), permitted()).status());
    }

    @Test
    void preservesDeviceAndRoomAmbiguity() {
        Fixture fixture = new Fixture(List.of(
                room("r1", "Living", "Common"), room("r2", "Study", "Common"),
                device("first", "AC", "r1"), device("second", "Portable", "r1", "AC")));

        var devices = fixture.directory.find(new Query(null, "AC", "Living", SCOPE, STANDARD), permitted());
        assertEquals(Status.AMBIGUOUS, devices.status());
        assertEquals(List.of("first", "second"),
                List.of(devices.candidates().get(0).id(), devices.candidates().get(1).id()));
        var rooms = fixture.directory.find(new Query(null, "AC", "Common", SCOPE, STANDARD), permitted());
        assertEquals(Status.AMBIGUOUS, rooms.status());
        assertTrue(rooms.candidates().isEmpty());
    }

    @Test
    void rejectsScopeBeforeStoreAndReturnsNoIdentitiesForAnyDeniedMatch() {
        Fixture fixture = new Fixture(List.of(room("r1", "Living"),
                device("first", "AC", "r1"), device("secret", "Portable", "r1", "AC")));
        Query query = new Query(null, "AC", "Living", SCOPE, STANDARD);
        var scope = fixture.directory.find(query, new Permission(false, null));
        assertEquals(Status.DENIED, scope.status());
        assertEquals(0, fixture.graph.calls);

        var device = fixture.directory.find(query, new Permission(true, "secret"));
        assertEquals(Status.DENIED, device.status());
        assertTrue(device.candidates().isEmpty());

        var room = fixture.directory.find(query, new Permission(true, "r1"));
        assertEquals(Status.DENIED, room.status());
        assertTrue(room.candidates().isEmpty());
    }

    @Test
    void exactScopeFiltersIdenticalRoomAndDeviceNamesBeforeItemAuthorization() {
        Map<String, Object> otherScope = Map.of("tenant", "other");
        Fixture fixture = new Fixture(List.of(room("r1", "Living"),
                entity("other-room", ROOM, "Living", Map.of(), otherScope),
                device("first", "AC", "r1"),
                entity("other-device", DEVICE, "AC", Map.of("roomId", "r1"), otherScope)));

        var result = fixture.directory.find(new Query(null, "AC", "Living", SCOPE, STANDARD), permitted());
        assertEquals(Status.FOUND, result.status());
        assertEquals("first", result.candidates().getFirst().id());

        var other = fixture.directory.find(new Query("other-device", null, null, SCOPE, STANDARD), permitted());
        assertEquals(Status.NOT_FOUND, other.status());
        assertTrue(other.candidates().isEmpty());
    }

    @Test
    void pageBudgetNeverTurnsPartialFirstMatchIntoUniqueDevice() {
        Fixture fixture = new Fixture(List.of(device("first", "AC", null), device("second", "AC", null)));
        var result = fixture.directory.find(new Query(null, "AC", null, SCOPE,
                new Budget(1, 1, Duration.ofSeconds(5))), permitted());

        assertEquals(Status.INCOMPLETE, result.status());
        assertTrue(result.candidates().isEmpty());
        assertEquals(1, fixture.graph.calls);
    }

    @Test
    void validatesExplicitIdAgainstOtherCluesAndDiscardsLateReads() {
        Fixture fixture = new Fixture(List.of(room("r1", "Living"), room("r2", "Study"),
                device("first", "AC", "r1"), device("second", "AC", "r2")));
        var result = fixture.directory.find(new Query("first", "AC", "Study", SCOPE, STANDARD), permitted());
        assertEquals(Status.NOT_FOUND, result.status());

        AtomicLong clock = new AtomicLong();
        Memory late = new LateMemory(List.of(device("first", "AC", null)), clock);
        var directory = new WorldDeviceDirectory(new DualWriteStore(late, fixture.vector), NEO4J, clock::get);
        var timed = directory.find(new Query("first", null, null, SCOPE,
                new Budget(1, 1, Duration.ofNanos(100))), permitted());
        assertEquals(Status.INCOMPLETE, timed.status());
        assertTrue(timed.candidates().isEmpty());
    }

    private static Access permitted() {
        return new Permission(true, null);
    }

    private static WorldEntity room(String id, String name, String... aliases) {
        return entity(id, ROOM, name, Map.of("aliases", List.of(aliases)));
    }

    private static WorldEntity device(String id, String name, String room, String... aliases) {
        if (room == null) {
            return entity(id, DEVICE, name, Map.of("aliases", List.of(aliases)));
        }
        return entity(id, DEVICE, name, Map.of("aliases", List.of(aliases), "roomId", room));
    }

    private static WorldEntity entity(String id, WorldObjectType type, String name, Map<String, Object> data) {
        return entity(id, type, name, data, SCOPE);
    }

    private static WorldEntity entity(String id, WorldObjectType type, String name, Map<String, Object> data,
                                      Map<String, Object> scope) {
        return new WorldEntity(id, type, null, null, null, name, null, data,
                List.of(), null, 2, "test", List.of(), scope, null, null);
    }

    private record Permission(
            /** 本次范围是否开放。 */
            boolean scopeAllowed,
            /** 有权访问范围时，指定一个也不得公开的实体。 */
            String deniedId) implements Access {
        @Override
        public boolean allowsScope(Map<String, Object> scope) {
            return scopeAllowed && SCOPE.equals(scope);
        }

        @Override
        public boolean allows(WorldEntity entity, Map<String, Object> scope) {
            return SCOPE.equals(scope) && scope.equals(entity.scope()) && !entity.id().equals(deniedId);
        }
    }

    private static final class Fixture {
        /** 只读权威目录。 */
        private final Memory graph;
        /** 用来检查不会访问另一来源。 */
        private final Memory vector = new Memory(List.of());
        /** 按显式权威库调用的目录。 */
        private final WorldDeviceDirectory directory;

        private Fixture(List<WorldEntity> entities) {
            graph = new Memory(entities);
            directory = new WorldDeviceDirectory(new DualWriteStore(graph, vector), NEO4J);
        }
    }

    private static class Memory implements DualWriteStore.Participant {
        /** 按既定顺序分页的目录快照。 */
        private final List<WorldEntity> values;
        /** 单条读取及分页次数。 */
        private int calls;

        private Memory(List<WorldEntity> values) {
            this.values = values;
        }

        @Override
        public DualWriteStore.Change open(String operationId, List<WorldEntity> entities) {
            throw new AssertionError("设备查找不能写库");
        }

        @Override
        public Optional<WorldEntity> read(String id) {
            calls++;
            for (WorldEntity entity : values) {
                if (id.equals(entity.id())) {
                    return Optional.of(entity);
                }
            }
            return Optional.empty();
        }

        @Override
        public StorageQuery.Page query(StorageQuery.PageRequest request) {
            calls++;
            List<WorldEntity> matches = new ArrayList<>();
            for (WorldEntity entity : values) {
                if (matches(entity, request.filter())) {
                    matches.add(entity);
                }
            }
            int start = request.cursor() == null ? 0 : Integer.parseInt(request.cursor().position());
            int end = Math.min(start + request.limit(), matches.size());
            StorageQuery.Cursor next = null;
            if (end < matches.size()) {
                next = new StorageQuery.Cursor(NEO4J, Integer.toString(end));
            }
            return new StorageQuery.Page(matches.subList(start, end), next);
        }

        private static boolean matches(WorldEntity entity, StorageQuery.Filter filter) {
            for (StorageQuery.Match condition : filter.matches()) {
                Object value = switch (condition.pointer()) {
                    case "/worldObjectType" -> entity.worldObjectType().name();
                    case "/name" -> entity.name();
                    case "/data/aliases" -> entity.data().get("aliases");
                    case "/data/roomId" -> entity.data().get("roomId");
                    default -> throw new AssertionError("未识别的目录查询字段");
                };
                if (value instanceof List<?> list) {
                    if (!list.contains(condition.values().getFirst())) {
                        return false;
                    }
                } else if (!condition.values().getFirst().equals(value)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class LateMemory extends Memory {
        /** 可确定地模拟存储回包超过本轮预算。 */
        private final AtomicLong clock;

        private LateMemory(List<WorldEntity> values, AtomicLong clock) {
            super(values);
            this.clock = clock;
        }

        @Override
        public Optional<WorldEntity> read(String id) {
            clock.set(101);
            return super.read(id);
        }
    }
}
