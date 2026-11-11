package com.example.myhelper.persistence;


import com.example.myhelper.world.model.SemanticType;
import com.example.myhelper.world.model.WorldEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static com.example.myhelper.persistence.DualWriteStore.Database.NEO4J;
import static com.example.myhelper.persistence.DualWriteStore.Database.QDRANT;

/** 验证显式来源/目标及失败补偿，未参与的库不得收到任何调用。 */
public final class DualWriteStoreCheck {
    public static void main(String[] args) {
        Fake graph = new Fake();
        Fake vector = new Fake();
        DualWriteStore store = new DualWriteStore(graph, vector);
        WorldEntity a = entity("a", 1);
        // 相同语义类型分别指定两库，证明没有类型路由。
        store.write(NEO4J, a);
        require(graph.data.get("a").equals(a) && vector.calls.isEmpty());
        graph.calls.clear();
        store.write(QDRANT, entity("a", 2));
        require(graph.calls.isEmpty() && vector.data.get("a").revision() == 2);
        vector.calls.clear();
        require(store.read(NEO4J, "a").orElseThrow().revision() == 1 && vector.calls.isEmpty());
        graph.calls.clear();
        require(store.read(QDRANT, "a").orElseThrow().revision() == 2);
        require(store.read(QDRANT, "missing").isEmpty() && graph.calls.isEmpty());
        // 两库不同数据统一提交；同一 ID 跨库不冲突。
        store.writeBatch(Map.of(NEO4J, List.of(entity("same", 1)), QDRANT, List.of(entity("same", 2))));
        require(graph.data.get("same").revision() == 1 && vector.data.get("same").revision() == 2);
        require(graph.operationId.equals(vector.operationId));
        vector.calls.clear();
        store.writeBatch(Map.of(NEO4J, List.of(entity("b", 1)), QDRANT, List.of()));
        require(vector.calls.isEmpty());
        graph.calls.clear();
        store.writeBatch(Map.of());
        require(graph.calls.isEmpty() && vector.calls.isEmpty());
        // 所有目标先校验；第二库数据非法也不能先访问第一库。
        try {
            store.writeBatch(Map.of(NEO4J, List.of(a), QDRANT, List.of(a, a)));
            throw new AssertionError("应拒绝同库重复 ID");
        } catch (IllegalArgumentException expected) { }
        require(graph.calls.isEmpty() && vector.calls.isEmpty());
        try {
            store.read(null, "a");
            throw new AssertionError("应拒绝空来源");
        } catch (NullPointerException expected) { }
        try {
            store.read(NEO4J, " ");
            throw new AssertionError("应拒绝空 ID");
        } catch (IllegalArgumentException expected) { }
        // 第二库写入后失败，恢复两库各自的原始数据。
        vector.failCommit = true;
        try {
            store.writeBatch(Map.of(NEO4J, List.of(entity("same", 3)), QDRANT, List.of(entity("same", 4))));
            throw new AssertionError("应报告提交失败");
        } catch (DualWriteStore.WriteFailure failure) {
            require(failure.rollbackCompleted());
            require(graph.data.get("same").revision() == 1 && vector.data.get("same").revision() == 2);
        }
        vector.failRollback = true;
        try {
            store.writeBatch(Map.of(NEO4J, List.of(entity("new", 1)), QDRANT, List.of(entity("same", 4))));
            throw new AssertionError("应报告补偿失败");
        } catch (DualWriteStore.WriteFailure failure) {
            require(!failure.rollbackCompleted() && failure.getSuppressed().length == 1);
            require(!graph.data.containsKey("new"));
        }
        // 单库失败也进行补偿，未选中的库不参与。
        graph.calls.clear();
        vector.failRollback = false;
        try {
            store.write(QDRANT, entity("single", 1));
            throw new AssertionError("应报告单库失败");
        } catch (DualWriteStore.WriteFailure failure) {
            require(failure.rollbackCompleted() && !vector.data.containsKey("single"));
        }
        require(graph.calls.isEmpty());
        System.out.println("DualWriteStore checks passed: explicit targets/sources, no fallback, empty targets, validation, compensation");
    }
    private static WorldEntity entity(String id, long revision) {
        return new WorldEntity(id, null, null, null, SemanticType.CONTENT,
                null, "test", Map.of(), List.of(), null, revision,
                null, List.of(), Map.of(), null, null);
    }
    private static void require(boolean value) {
        if (!value) throw new AssertionError();
    }
    /** 分支只记录被调用情况及模拟故障，不实现任何路由。 */
    private static final class Fake implements DualWriteStore.Participant {
        /** 模拟数据库中的实体。 */
        private final Map<String, WorldEntity> data = new HashMap<>();
        /** 检测未参与分支是否被调用。 */
        private final List<String> calls = new ArrayList<>();
        /** 最近操作编号，检查同次提交共享编号。 */
        private String operationId;
        /** 写入后抛出异常。 */
        private boolean failCommit;
        /** 模拟补偿失败。 */
        private boolean failRollback;
        public Optional<WorldEntity> read(String id) {
            calls.add("read");
            return Optional.ofNullable(data.get(id));
        }
        public DualWriteStore.Change open(String operation, List<WorldEntity> batch) {
            calls.add("open");
            operationId = operation;
            Map<String, WorldEntity> before = new HashMap<>(data);
            return new DualWriteStore.Change() {
                public void prepare() { calls.add("prepare"); }
                public void commit() {
                    calls.add("commit");
                    for (WorldEntity entity : batch) {
                        data.put(entity.id(), entity);
                    }
                    if (failCommit) throw new IllegalStateException("commit");
                }
                public void rollback() {
                    calls.add("rollback");
                    if (failRollback) throw new IllegalStateException("rollback");
                    data.clear();
                    data.putAll(before);
                }
            };
        }
    }
}
