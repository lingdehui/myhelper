package com.example.myhelper.support;

import com.example.myhelper.llm.ModelAdapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.model.ChatModel;

/** 仅供 checks 的模型适配替身；生产策略由外部模型适配模块实现。 */
public final class TestModelAdapter implements ModelAdapter {
    /** 检查使用的有序候选引用，不读取真实账号配置。 */
    private final List<String> candidates;
    /** 可观测的会话创建次数，用来检查纯工具路径没有请求模型模块。 */
    private final AtomicInteger opens = new AtomicInteger();
    /** 每次运行单独创建会话，不让不同根任务共享绑定。 */
    private final List<TestSession> sessions = new CopyOnWriteArrayList<>();

    public TestModelAdapter(List<String> candidates) {
        this.candidates = List.copyOf(candidates);
    }

    @Override
    public TestSession openRun(RunRequest request) {
        Objects.requireNonNull(request);
        opens.incrementAndGet();
        TestSession session = new TestSession(request.rootBranchId(), request.modelRef(), candidates);
        sessions.add(session);
        return session;
    }

    @Override
    public ChatModel resolve(String modelRef) {
        throw new AssertionError("This fixture records routing only; it has no real ChatModel: " + modelRef);
    }

    public int openCalls() {
        return opens.get();
    }

    public List<TestSession> sessions() {
        return List.copyOf(sessions);
    }

    /** 测试会话保留原分配检查的原子回退、引用计数及只读快照语义。 */
    public static final class TestSession implements Session {
        /** 父根绑定不会被嵌套分叉覆盖。 */
        private final Binding root;
        /** 测试中可替换目录快照，模拟配置变化及非法外部数据。 */
        private List<String> candidates;
        /** 本会话当前活动绑定。 */
        private final Map<String, Binding> branches = new LinkedHashMap<>();
        /** 多个回退分支共享模型时仍分别持有引用。 */
        private final Map<String, Integer> references = new LinkedHashMap<>();
        /** 同批申请必须返回原绑定；已回收的批次不能重新分配。 */
        private final Map<ForkKey, List<Binding>> batches = new LinkedHashMap<>();
        /** 身份使用历史保留到会话结束，禁止不同批次重复利用旧身份。 */
        private final Set<String> usedIds = new HashSet<>();
        /** 生命周期观测用于验证取消不会提前关闭模型会话。 */
        private final CountDownLatch closedSignal = new CountDownLatch(1);
        /** 已关闭的会话不得再分配分支。 */
        private boolean closed;
        /** 记录关闭调用次数，避免重复收尾被隐藏。 */
        private int closeCalls;

        private TestSession(String rootBranchId, String modelRef, List<String> candidates) {
            root = new Binding(rootBranchId, null, modelRef, Assignment.ROOT);
            this.candidates = List.copyOf(candidates);
            register(root);
        }

        @Override
        public Binding root() {
            return root;
        }

        @Override
        public synchronized Binding binding(String id) {
            requireOpen();
            Binding binding = branches.get(id);
            if (binding == null) {
                throw new IllegalArgumentException("Unknown test branch: " + id);
            }
            return binding;
        }

        @Override
        public synchronized List<Binding> fork(Binding parent, List<String> childIds) {
            requireOpen();
            List<String> children = List.copyOf(childIds);
            if (parent == null || !parent.equals(branches.get(parent.id()))) {
                throw new IllegalArgumentException("Test parent binding does not belong to this session");
            }
            ForkKey key = new ForkKey(parent.id(), children);
            List<Binding> previous = batches.get(key);
            if (previous != null) {
                for (Binding binding : previous) {
                    if (!binding.equals(branches.get(binding.id()))) {
                        throw new IllegalArgumentException("Released test fork cannot be reopened");
                    }
                }
                return previous;
            }
            List<String> models = List.copyOf(candidates);
            validate(parent, children, models);
            List<Binding> result = new ArrayList<>();
            for (String id : children) {
                String model = parent.modelRef();
                Assignment assignment = Assignment.INHERITED;
                for (String candidate : models) {
                    if (!references.containsKey(candidate)) {
                        model = candidate;
                        assignment = Assignment.OTHER_MODEL;
                        break;
                    }
                }
                Binding binding = new Binding(id, parent.id(), model, assignment);
                register(binding);
                result.add(binding);
            }
            List<Binding> snapshot = List.copyOf(result);
            batches.put(key, snapshot);
            return snapshot;
        }

        @Override
        public synchronized boolean release(String id) {
            requireOpen();
            if (root.id().equals(id)) {
                return false;
            }
            Binding old = branches.get(id);
            if (old == null) {
                return false;
            }
            for (Binding binding : branches.values()) {
                if (id.equals(binding.parentId())) {
                    throw new IllegalStateException("Test branch still has active children: " + id);
                }
            }
            branches.remove(id);
            int remaining = references.get(old.modelRef()) - 1;
            if (remaining == 0) {
                references.remove(old.modelRef());
            } else {
                references.put(old.modelRef(), remaining);
            }
            return true;
        }

        public synchronized void candidates(List<String> models) {
            requireOpen();
            candidates = List.copyOf(models);
        }

        public synchronized Map<String, Binding> snapshot() {
            return Collections.unmodifiableMap(new LinkedHashMap<>(branches));
        }

        public synchronized boolean closed() {
            return closed;
        }

        public synchronized int closeCalls() {
            return closeCalls;
        }

        public boolean awaitClosed(long seconds) throws InterruptedException {
            return closedSignal.await(seconds, TimeUnit.SECONDS);
        }

        @Override
        public synchronized void close() {
            closeCalls++;
            if (closed) {
                return;
            }
            closed = true;
            branches.clear();
            references.clear();
            batches.clear();
            usedIds.clear();
            closedSignal.countDown();
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Test model session is closed");
            }
        }

        private void validate(Binding parent, List<String> children, List<String> models) {
            if (parent == null || !parent.equals(branches.get(parent.id()))) {
                throw new IllegalArgumentException("Test parent binding does not belong to this session");
            }
            if (children.size() < 2) {
                throw new IllegalArgumentException("A test fork needs at least two children");
            }
            Set<String> ids = new HashSet<>();
            for (String id : children) {
                requireText(id);
                if (!ids.add(id) || usedIds.contains(id)) {
                    throw new IllegalArgumentException("Duplicate test branch: " + id);
                }
            }
            for (String model : models) {
                requireText(model);
            }
        }

        private static void requireText(String text) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Test reference cannot be blank");
            }
        }

        private void register(Binding binding) {
            branches.put(binding.id(), binding);
            usedIds.add(binding.id());
            references.put(binding.modelRef(), references.getOrDefault(binding.modelRef(), 0) + 1);
        }

        private record ForkKey(String parentId, List<String> childIds) {
            private ForkKey {
                childIds = List.copyOf(childIds);
            }
        }
    }
}
