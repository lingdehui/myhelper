package com.example.myhelper.world;

import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.DualWriteStore.Database;
import com.example.myhelper.world.WorldReferenceExpander.Budget;
import com.example.myhelper.world.WorldReferenceExpander.LocatedRef;
import com.example.myhelper.world.WorldReferenceExpander.Status;
import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.FieldBinding;
import com.example.myhelper.world.model.WorldEntity;
import com.example.myhelper.world.model.WorldObjectType;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static com.example.myhelper.persistence.DualWriteStore.Database.NEO4J;
import static com.example.myhelper.persistence.DualWriteStore.Database.QDRANT;
import static org.junit.jupiter.api.Assertions.*;

class WorldReferenceExpanderTest {
    /** 普通用例的有界查询预算。 */
    private static final Budget STANDARD = new Budget(4, 40, Duration.ofSeconds(5));
    /** 普通用例的明确读取授权。 */
    private static final WorldReferenceExpander.ReadAccess PUBLIC = new PublicAccess();

    @Test
    void preservesDirectValueAndDifferentTargetsWhileSharingIdenticalReads() {
        EntityRef target = ref("vector", 2L);
        Memory graph = new Memory(Map.of("root", entity("root", 1,
                Map.of("first", "literal", "second", List.of("stored")), List.of(
                        new FieldBinding("/data/first", target),
                        new FieldBinding("/data/first", ref("other", 1L)),
                        new FieldBinding("/data/second", ref("vector", null))))));
        Memory vector = new Memory(Map.of(
                "vector", entity("vector", 2, Map.of("a/b~c", new BigDecimal("0.1234567890123456789")), List.of()),
                "other", entity("other", 1, Map.of("a/b~c", false), List.of())));
        var result = expander(graph, vector, new VectorTargets()).expand(NEO4J, ref("root", 1L), List.of(
                List.of("/data/first", "/data/a~1b~0c"), List.of("/data/second", "/data/a~1b~0c")), STANDARD, PUBLIC);

        assertTrue(result.complete());
        assertEquals(1, graph.reads);
        assertEquals(2, vector.reads);
        assertEquals(3, result.links().size());
        assertEquals("literal", result.fields().getFirst().value());
        assertEquals(new BigDecimal("0.1234567890123456789"), result.fields().get(1).value());
        assertEquals(false, result.fields().get(2).value());
        assertEquals("/data/second", result.fields().getLast().path().getFirst().slot());
        assertEquals(new LocatedRef(QDRANT, target), result.links().getLast().resolved());
    }

    @Test
    void reportsExactRevisionMissingDeniedFieldsAndCycles() {
        EntityRef root = ref("root", 1L);
        Memory graph = new Memory(Map.of("root", entity("root", 1,
                Map.of("secret", "private", "cycle", "possible", "old", "pinned"), List.of(
                        new FieldBinding("/data/cycle", root),
                        new FieldBinding("/data/old", ref("changed", 1L)),
                        new FieldBinding("/data/blocked", ref("blocked", null)))),
                "changed", entity("changed", 2, Map.of(), List.of())));
        Memory vector = new Memory(Map.of());
        var result = expander(graph, vector, new GraphTargets()).expand(NEO4J, root, List.of(
                List.of("/data/cycle", "/text"), List.of("/data/old"), List.of("/data/blocked"),
                List.of("/data/secret")), STANDARD, new RestrictedAccess());

        assertFalse(result.complete());
        assertEquals(2, graph.reads);
        assertEquals(0, vector.reads);
        assertEquals(Status.CYCLE, result.links().get(0).status());
        assertEquals(Status.REVISION_UNAVAILABLE, result.links().get(1).status());
        assertNull(result.links().get(1).resolved());
        assertEquals(Status.ACCESS_DENIED, result.links().get(2).status());
        assertEquals(Status.ACCESS_DENIED, result.problems().getFirst().status());
        assertEquals("/data/secret", result.problems().getFirst().slot());
    }

    @Test
    void budgetStopsBeforeReadingUnselectedOrOverDepthTargets() {
        EntityRef root = ref("root", 1L);
        Memory graph = new Memory(Map.of("root", entity("root", 1, Map.of("field", "value"), List.of(
                new FieldBinding("/data/field", ref("target", 1L)),
                new FieldBinding("/data/unused", ref("unused", 1L))))));
        Memory vector = new Memory(Map.of());
        var expander = expander(graph, vector, new VectorTargets());
        var depth = expander.expand(NEO4J, root, List.of(List.of("/data/field")),
                new Budget(0, 5, Duration.ofSeconds(5)), PUBLIC);
        assertEquals(Status.DEPTH_LIMIT, depth.links().getFirst().status());
        assertEquals(1, depth.links().size());
        var count = expander.expand(NEO4J, root, List.of(List.of("/data/field")),
                new Budget(4, 1, Duration.ofSeconds(5)), PUBLIC);
        assertTrue(count.links().isEmpty());
        assertEquals(Status.ITEM_LIMIT, count.problems().getFirst().status());
        assertEquals(0, vector.reads);
    }

    @Test
    void discardsLateDatabaseResults() {
        AtomicLong monotonic = new AtomicLong();
        EntityRef root = ref("root", 1L);
        Memory graph = new LateRead(Map.of("root", entity("root", 1, Map.of("field", "value"), List.of())), monotonic);
        var expander = new WorldReferenceExpander(new DualWriteStore(graph, new Memory(Map.of())),
                new GraphTargets(), monotonic::get);
        var result = expander.expand(NEO4J, root, List.of(List.of("/data/field")),
                new Budget(2, 10, Duration.ofNanos(100)), PUBLIC);
        assertEquals(1, graph.reads);
        assertTrue(result.fields().isEmpty());
        assertEquals(Status.TIME_LIMIT, result.problems().getFirst().status());
    }

    private static WorldReferenceExpander expander(Memory graph, Memory vector,
                                                    WorldReferenceExpander.SourceResolver sources) {
        return new WorldReferenceExpander(new DualWriteStore(graph, vector), sources);
    }

    private static EntityRef ref(String id, Long revision) {
        return new EntityRef(id, revision, WorldObjectType.DEVICE, null, null, null);
    }

    private static WorldEntity entity(String id, long revision, Map<String, Object> data, List<FieldBinding> bindings) {
        return new WorldEntity(id, WorldObjectType.DEVICE, null, null, null,
                id, null, data, bindings, null, revision, "test", List.of(), Map.of(), null, null);
    }

    private static final class PublicAccess implements WorldReferenceExpander.ReadAccess {
        @Override
        public boolean allows(LocatedRef entity, String slot) {
            return true;
        }
    }

    private static final class RestrictedAccess implements WorldReferenceExpander.ReadAccess {
        @Override
        public boolean allows(LocatedRef entity, String slot) {
            return !entity.ref().id().equals("blocked") && !"/data/secret".equals(slot);
        }
    }

    private static final class GraphTargets implements WorldReferenceExpander.SourceResolver {
        @Override
        public Optional<Database> resolve(LocatedRef owner, String slot, EntityRef target) {
            return Optional.of(NEO4J);
        }
    }

    private static final class VectorTargets implements WorldReferenceExpander.SourceResolver {
        @Override
        public Optional<Database> resolve(LocatedRef owner, String slot, EntityRef target) {
            return Optional.of(QDRANT);
        }
    }

    private static class Memory implements DualWriteStore.Participant {
        /** 供读取的固定实体集合。 */
        private final Map<String, WorldEntity> entities;
        /** 实际数据库端口调用次数。 */
        private int reads;

        private Memory(Map<String, WorldEntity> entities) {
            this.entities = entities;
        }

        @Override
        public DualWriteStore.Change open(String operationId, List<WorldEntity> batch) {
            throw new AssertionError("只读检查不能写入");
        }

        @Override
        public Optional<WorldEntity> read(String id) {
            reads++;
            return Optional.ofNullable(entities.get(id));
        }
    }

    private static final class LateRead extends Memory {
        /** 等待期间跨过读取预算的单调时钟。 */
        private final AtomicLong monotonic;

        private LateRead(Map<String, WorldEntity> entities, AtomicLong monotonic) {
            super(entities);
            this.monotonic = monotonic;
        }

        @Override
        public Optional<WorldEntity> read(String id) {
            monotonic.set(101);
            return super.read(id);
        }
    }
}
