package com.example.myhelper.persistence;

import com.example.myhelper.world.model.ExecutionStatistics;
import com.example.myhelper.world.model.WorldEntity;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionCallback;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import static com.example.myhelper.persistence.DualWriteStore.Database.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证 Driver 边界合同及原事务复用；不声称模拟器证明真实数据库并发锁。 */
class ConditionalWriteTest {
    @Test void absentCreatesOnceAndExistingEntityIsNotOverwritten() {
        Fixture fixture = new Fixture();
        DualWriteStore store = fixture.store();
        WorldEntity original = entity(0);
        assertTrue(store.writeIfAbsent(NEO4J, original));
        assertEquals(original, fixture.entity());
        int saves = fixture.saves;
        assertFalse(store.writeIfAbsent(NEO4J, entity(8)));
        assertEquals(original, fixture.entity());
        assertEquals(saves, fixture.saves);
        assertTrue(fixture.commands.get(0).startsWith("SHOW CONSTRAINTS"));
        assertTrue(fixture.commands.get(1).contains("SET e._persistenceLock=true REMOVE e._persistenceLock RETURN e.json"));
        assertEquals(2, fixture.transactions);
    }

    @Test void entireSnapshotDetectsStatisticsChangedWithoutRevisionChange() {
        Fixture fixture = new Fixture();
        DualWriteStore store = fixture.store();
        WorldEntity old = entity(0);
        assertTrue(store.writeIfAbsent(NEO4J, old));
        assertTrue(store.compareAndSet(NEO4J, old, entity(1)));
        assertFalse(store.compareAndSet(NEO4J, old, entity(2)));
        assertEquals(1, fixture.entity().statistics().successCount());
        assertTrue(store.compareAndSet(NEO4J, fixture.entity(), entity(2)));
        assertEquals(2, fixture.entity().statistics().successCount());
        assertTrue(fixture.terms.contains("[\"/statistics/successCount\",\"number\",\"2\"]"));
        assertEquals(entity(2).bindings().size() + entity(2).evidence().size(), fixture.references);
    }

    @Test void missingConstraintFailsBeforeOpeningWriteTransaction() {
        Fixture fixture = new Fixture();
        fixture.schemaReady = false;
        DualWriteStore store = fixture.store();
        assertThrows(IllegalStateException.class, new Executable() {
            public void execute() { store.writeIfAbsent(NEO4J, entity(0)); }
        });
        assertEquals(0, fixture.transactions);
        assertNull(fixture.saved);
    }

    @Test void sourceAndIdValidationDoNotReachDatabase() {
        Fixture fixture = new Fixture();
        DualWriteStore store = fixture.store();
        assertThrows(UnsupportedOperationException.class, new Executable() {
            public void execute() { store.writeIfAbsent(QDRANT, entity(0)); }
        });
        WorldEntity a = entity(0);
        WorldEntity b = new WorldEntity("other", a.worldObjectType(), a.contextUnitType(), a.unitType(),
                a.semanticType(), a.name(), a.text(), a.data(), a.bindings(), a.embedding(), a.revision(),
                a.source(), a.evidence(), a.scope(), a.createdAt(), a.updatedAt());
        assertThrows(IllegalArgumentException.class, new Executable() {
            public void execute() { store.compareAndSet(NEO4J, a, b); }
        });
        assertTrue(fixture.commands.isEmpty());
    }

    @Test void failedReferenceWriteUsesSameLocalTransactionAndReportsError() {
        Fixture fixture = new Fixture();
        DualWriteStore store = fixture.store();
        WorldEntity before = entity(0);
        store.writeIfAbsent(NEO4J, before);
        List<?> beforeTerms = fixture.terms;
        fixture.failReferences = true;
        assertThrows(IllegalStateException.class, new Executable() {
            public void execute() { store.compareAndSet(NEO4J, before, entity(1)); }
        });
        assertEquals(before, fixture.entity());
        assertEquals(beforeTerms, fixture.terms);
        assertEquals(1, fixture.rollbacks);
        assertEquals(2, fixture.transactions);
    }

    @Test void normalizedLegacySnapshotCanBeComparedWithoutDiscardingDefaultFields() {
        Fixture fixture = new Fixture();
        var tree = (com.fasterxml.jackson.databind.node.ObjectNode) fixture.json.parse(fixture.json.encode(entity(0)));
        tree.remove("statistics");
        tree.remove("chainPositions");
        fixture.saved = tree.toString();
        WorldEntity loaded = fixture.entity();
        assertTrue(fixture.store().compareAndSet(NEO4J, loaded, entity(1)));
        assertEquals(1, fixture.entity().statistics().successCount());
    }

    // 同一业务 revision 的执行统计变化用于检查不能仅比较版本号。
    private static WorldEntity entity(long successCount) {
        WorldEntity value = QdrantEntityStoreCheck.entity(1, true);
        return new WorldEntity(value.id(), value.worldObjectType(), value.contextUnitType(), value.unitType(),
                value.semanticType(), value.name(), value.text(), value.data(), value.bindings(), value.embedding(),
                value.revision(), value.source(), value.evidence(), value.scope(), value.createdAt(), value.updatedAt(),
                value.chainPositions(), new ExecutionStatistics(successCount, 0, List.of()));
    }

    private static final class Fixture implements InvocationHandler {
        /** 与业务相同的序列化器。 */
        private final EntityJson json = new EntityJson();
        /** 记录实际发出的语句顺序。 */
        private final List<String> commands = new ArrayList<>();
        /** 模拟数据库中的完整实体值。 */
        private String saved;
        /** 模拟查询投影与实体处于同一个本地事务。 */
        private List<?> terms = List.of();
        /** 已写入引用数量。 */
        private int references;
        /** 实体保存次数。 */
        private int saves;
        /** 本地事务启动次数。 */
        private int transactions;
        /** 本地事务失败回滚次数。 */
        private int rollbacks;
        /** 缺少唯一约束时必须拒绝写入。 */
        private boolean schemaReady = true;
        /** 在实体保存后制造引用投影失败。 */
        private boolean failReferences;

        private DualWriteStore store() {
            var graph = new Neo4jEntityStore(proxy(Driver.class, this), "neo4j", json);
            return new DualWriteStore(graph, graph);
        }

        private WorldEntity entity() {
            return json.entity(saved);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            switch (method.getName()) {
                case "session": return proxy(Session.class, this);
                case "close": return null;
                case "executeWrite": return execute((TransactionCallback<?>) arguments[0]);
                case "run": return run((String) arguments[0], arguments.length > 1 ? (Map<?, ?>) arguments[1] : Map.of());
                default: throw new AssertionError(method);
            }
        }

        private Object execute(TransactionCallback<?> callback) {
            transactions++;
            String original = saved;
            List<?> originalTerms = terms;
            int originalReferences = references;
            try {
                return callback.execute(proxy(TransactionContext.class, this));
            } catch (RuntimeException failure) {
                saved = original;
                terms = originalTerms;
                references = originalReferences;
                rollbacks++;
                throw failure;
            }
        }

        private Result run(String query, Map<?, ?> params) {
            commands.add(query);
            if (query.startsWith("SHOW CONSTRAINTS")) {
                return result(schemaReady ? List.of(proxy(Record.class, new JsonRecord(null))) : List.of());
            }
            if (query.contains("_persistenceLock")) {
                return result(List.of(proxy(Record.class, new JsonRecord(saved))));
            }
            if (query.contains("SET e.json")) {
                saved = (String) params.get("json");
                terms = (List<?>) params.get("terms");
                saves++;
            } else if (query.contains("DELETE r")) {
                references = 0;
            } else if (query.contains("CREATE (e)-[:WORLD_REF")) {
                if (failReferences) {
                    throw new IllegalStateException("reference write failed");
                }
                references++;
            } else {
                throw new AssertionError(query);
            }
            return result(List.of());
        }
    }

    private record JsonRecord(String encoded) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getName().equals("get") && arguments[0].equals("json")) {
                return proxy(Value.class, new JsonValue(encoded));
            }
            throw new AssertionError(method);
        }
    }

    private record JsonValue(String encoded) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "isNull" -> encoded == null;
                case "asString" -> encoded;
                default -> throw new AssertionError(method);
            };
        }
    }

    private record Rows(List<Record> rows) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "list" -> rows;
                case "consume" -> null;
                default -> throw new AssertionError(method);
            };
        }
    }

    private static Result result(List<Record> rows) {
        return proxy(Result.class, new Rows(rows));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
