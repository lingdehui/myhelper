package com.example.myhelper.orchestration;

import com.example.myhelper.llm.ModelAdapter;
import com.example.myhelper.support.TestModelAdapter;
import com.example.myhelper.support.TestModelAdapter.TestSession;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

/** 分支分配检查只验证绑定政策，不发起真实模型调用。 */
class ModelAdapterSessionContractTest {
    @Test
    void repeatedForkReturnsOriginalBindingsAndReleasedBatchCannotRestart() {
        TestSession session = session();
        session.candidates(List.of("B", "C"));
        List<ModelAdapter.Binding> first = session.fork(session.root(), List.of("left", "right"));
        session.candidates(List.of("D", "E"));
        assertEquals(first, session.fork(session.root(), List.of("left", "right")));
        assertEquals(3, session.snapshot().size());
        assertTrue(session.release("left"));
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                session.fork(session.root(), List.of("left", "right"));
            }
        });
        assertEquals("C", session.binding("right").modelRef());
    }

    @Test
    void nestedForkKeepsParentsAndFallsBackOnlyForRemainingChildren() {
        TestSession allocator = session();
        List<String> models = List.of("A", "B", "B", "C", "D", "E");
        List<ModelAdapter.Binding> first = fork(allocator, allocator.root(), List.of("left", "right"), models);
        assertEquals(List.of("B", "C"), modelRefs(first));

        List<ModelAdapter.Binding> nested = fork(allocator, first.getFirst(), List.of("inner1", "inner2", "inner3"), models);
        assertEquals(List.of("D", "E", "B"), modelRefs(nested));
        assertEquals(ModelAdapter.Assignment.INHERITED, nested.get(2).assignment());
        assertEquals("A", allocator.root().modelRef());
        assertEquals("B", allocator.binding("left").modelRef());
        assertEquals("left", nested.getFirst().parentId());
    }

    @Test
    void inheritedChildReleaseKeepsParentAndOnlyLastReferenceBecomesReusable() {
        TestSession allocator = session();
        List<String> models = List.of("A", "B");
        List<ModelAdapter.Binding> first = fork(allocator, allocator.root(), List.of("left", "right"), models);
        assertEquals(List.of("B", "A"), modelRefs(first));
        List<ModelAdapter.Binding> inherited = fork(allocator, first.getFirst(), List.of("inner1", "inner2"), models);
        assertEquals(List.of("B", "B"), modelRefs(inherited));

        assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                allocator.release("left");
            }
        });
        assertTrue(allocator.release("inner1"));
        assertTrue(allocator.release("inner2"));
        List<ModelAdapter.Binding> stillOccupied = fork(allocator, first.get(1), List.of("r1", "r2"), models);
        assertEquals(List.of("A", "A"), modelRefs(stillOccupied));
        assertTrue(allocator.release("left"));
        assertFalse(allocator.release("left"));
        List<ModelAdapter.Binding> reusable = fork(allocator, first.get(1), List.of("r3", "r4"), models);
        assertEquals(List.of("B", "A"), modelRefs(reusable));
        assertFalse(allocator.release("root"));
        assertEquals("A", allocator.binding("root").modelRef());
    }

    @Test
    void validatesWholeForkBeforeChangingBindings() {
        TestSession allocator = session();
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                fork(allocator, allocator.root(), List.of("new1", "new1"), List.of("B", "C"));
            }
        });
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                fork(allocator, allocator.root(), List.of("new1", "new2"), List.of("B", " "));
            }
        });
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                fork(allocator, allocator.root(), List.of("linear"), List.of("B"));
            }
        });
        assertEquals(Map.of("root", allocator.root()), allocator.snapshot());
        List<ModelAdapter.Binding> firstValid = fork(allocator, allocator.root(), List.of("new1", "new2"), List.of("B", "C"));
        assertEquals(List.of("B", "C"), modelRefs(firstValid));
    }

    @Test
    void snapshotsAndAllocationResultsAreReadOnlyAndDoNotChangeLater() {
        TestSession allocator = session();
        Map<String, ModelAdapter.Binding> before = allocator.snapshot();
        List<ModelAdapter.Binding> children = fork(allocator, allocator.root(), List.of("left", "right"), List.of("B", "C"));
        assertEquals(Set.of("root"), before.keySet());
        Map<String, ModelAdapter.Binding> afterFork = allocator.snapshot();
        allocator.release("left");
        assertTrue(afterFork.containsKey("left"));
        assertFalse(allocator.snapshot().containsKey("left"));
        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            public void execute() {
                before.put("bad", allocator.root());
            }
        });
        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            public void execute() {
                children.add(allocator.root());
            }
        });
    }

    @Test
    void concurrentForksDoNotAllocateAnAlreadyBoundOtherModel() throws Exception {
        TestSession allocator = session();
        CountDownLatch start = new CountDownLatch(1);
        List<String> models = List.of("A", "B", "C", "D", "E");
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<ModelAdapter.Binding>> first = executor.submit(new ForkCall(allocator, start, List.of("l1", "l2"), models));
            Future<List<ModelAdapter.Binding>> second = executor.submit(new ForkCall(allocator, start, List.of("r1", "r2"), models));
            start.countDown();
            Set<String> allocated = new HashSet<>(modelRefs(first.get(5, TimeUnit.SECONDS)));
            allocated.addAll(modelRefs(second.get(5, TimeUnit.SECONDS)));
            assertEquals(Set.of("B", "C", "D", "E"), allocated);
            assertEquals(5, allocator.snapshot().size());
        }
    }

    @Test
    void restrictedAdapterCandidatesDoNotInventModelsAndFallbackUsesCurrentParent() {
        TestSession allocator = session();
        List<ModelAdapter.Binding> first = fork(allocator, allocator.root(), List.of("left", "right"), List.of("B", "C"));
        List<String> candidates = List.of("A");
        assertEquals(List.of("A"), candidates);
        List<ModelAdapter.Binding> nested = fork(allocator, first.getFirst(), List.of("inner1", "inner2"), candidates);
        assertEquals(List.of("B", "B"), modelRefs(nested));
    }

    // 目录属于测试适配器；业务调用始终使用 Session.fork 的两参数合同。
    private static List<ModelAdapter.Binding> fork(TestSession session, ModelAdapter.Binding parent,
                                                   List<String> children, List<String> models) {
        session.candidates(models);
        return session.fork(parent, children);
    }

    private static TestSession session() {
        return new TestModelAdapter(List.of("A")).openRun(
                new ModelAdapter.RunRequest("run", "root", "A", List.of()));
    }
    private static List<String> modelRefs(List<ModelAdapter.Binding> bindings) {
        List<String> models = new ArrayList<>();
        for (ModelAdapter.Binding binding : bindings) {
            models.add(binding.modelRef());
        }
        return models;
    }

    /** 同时触发两次分叉，核对复合分配操作确实原子化。 */
    private record ForkCall(
            TestSession allocator,
            CountDownLatch start,
            List<String> childIds,
            List<String> models) implements Callable<List<ModelAdapter.Binding>> {
        @Override
        public List<ModelAdapter.Binding> call() throws Exception {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("检查没有按时启动并发分配");
            }
            return fork(allocator, allocator.root(), childIds, models);
        }
    }
}
