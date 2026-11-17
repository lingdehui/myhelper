package com.example.myhelper.application;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.capability.CheckedCapabilityUse;
import com.example.myhelper.interaction.TaskEntry;
import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.orchestration.ExecutionTraceStore;
import com.example.myhelper.orchestration.ToolReliabilityGate;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.EntityJson;
import com.example.myhelper.persistence.Neo4jEntityStore;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.records.RunArchivePort.ArchiveRef;
import com.example.myhelper.records.StoredTaskRecords;
import com.example.myhelper.records.TaskInstructionPort.Kind;
import com.example.myhelper.records.TaskRecordPort.RunClaim;
import com.example.myhelper.records.TaskRecordPort.TaskSpec;
import com.example.myhelper.support.TestModelAdapter;
import com.example.myhelper.tasklog.TaskLogPort;
import com.example.myhelper.world.model.WorldEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Result;
import static org.junit.jupiter.api.Assertions.*;

/** 实际启动恢复、仓储、入口和循环共同验证分页及真实收尾；数据库仅替换物理参与者。 */
class StartupTaskRecoveryTest {
    @Test
    void startupRecoversEveryPageAfterSettlementAndOnlyReconcilesSameOwner() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("checks", "build"), "startup-recovery-");
        try {
            try (TaskProcessLock owner = TaskProcessLock.acquire(directory)) {
                MemoryParticipant database = new MemoryParticipant();
                StoredTaskRecords records = repository(database, owner.ownerId());
                StoredTaskRecords other = repository(database, "other-owner");
                List<String> expected = new ArrayList<>();
                String oldTask = records.create(spec("old-local")).taskId();
                RunClaim old = records.beginRun(oldTask, "old-local-run");
                records.bind(old);
                expected.add(oldTask);
                expected.add(records.create(spec("queued-one")).taskId());
                expected.add(records.create(spec("queued-two")).taskId());
                String otherTask = other.create(spec("running-elsewhere")).taskId();
                RunClaim otherClaim = other.beginRun(otherTask, "other-run");
                other.bind(otherClaim);
                BlockingFirstPlanner planner = new BlockingFirstPlanner();
                ConstraintOnlyDriver initializer = new ConstraintOnlyDriver();
                try (Runtime runtime = new Runtime(records, planner)) {
                    Neo4jEntityStore graph = new Neo4jEntityStore(initializer.driver(), null, new EntityJson());
                    try (StartupTaskRecovery startup = new StartupTaskRecovery(owner, records,
                            new TaskRecoveryService(runtime.orchestrator, records), runtime.entry, graph, 1,
                            runtime.activity)) {
                        assertTrue(runtime.activity.snapshot().busy(), "从装配到实际扫描均须保持占用");
                        startup.run(null);
                        try {
                            assertTrue(planner.firstEntered.await(3, TimeUnit.SECONDS));
                            assertNull(runtime.activity.tryStartIdleCall(runtime.activity.snapshot().generation()));
                            assertFalse(planner.secondEntered.await(150, TimeUnit.MILLISECONDS),
                                    "第一批真实工作未结束时，不应提前受理下一页");
                            assertEquals(1, planner.calls.get());
                            assertEquals(1, initializer.initializations.get());
                            assertEquals(otherClaim, records.activeRuns("other-owner", 1).getFirst().claim());
                            assertTrue(records.readArchive(new ArchiveRef(oldTask, old.rootRunId())).isPresent(),
                                    "同 owner 的旧运行先核对并归档");
                            assertTrue(records.readArchive(new ArchiveRef(otherTask, otherClaim.rootRunId())).isEmpty(),
                                    "不能把其他宿主的在途领取当成失联");
                        } finally {
                            planner.releaseFirst.countDown();
                        }
                        assertTrue(planner.allEntered.await(5, TimeUnit.SECONDS), "三页恢复均须受理");
                        waitUntil(new BooleanSupplier() {
                            @Override public boolean getAsBoolean() {
                                return records.activeRuns(owner.ownerId(), 10).isEmpty()
                                        && records.pendingConsolidations(10).size() == 3
                                        && tracesReleased(records.pendingConsolidations(10), runtime.traces)
                                        && !runtime.activity.snapshot().busy();
                            }
                        });
                        assertEquals(3, planner.calls.get());
                        assertTrue(records.recoverable(10).isEmpty());
                        assertEquals(otherClaim, records.activeRuns("other-owner", 1).getFirst().claim());
                        List<ArchiveRef> archived = records.pendingConsolidations(10);
                        List<String> actual = new ArrayList<>();
                        for (ArchiveRef ref : archived) {
                            actual.add(ref.taskId());
                            var saved = records.readArchive(ref).orElseThrow();
                            assertTrue(saved.graphComplete());
                            assertTrue(saved.evidenceComplete());
                            assertEquals("SUCCESS", ((Map<?, ?>) saved.endEvent().details().get("outcome")).get("status"));
                            assertEquals(Kind.CONTINUE, records.query(ref.taskId()).latestKind());
                        }
                        assertEquals(new java.util.HashSet<>(expected), new java.util.HashSet<>(actual));
                        assertEquals(0, runtime.actions.get(), "本检查只需要完成决策，不重放任何旧动作");
                    }
                } finally {
                    planner.releaseFirst.countDown();
                }
            }
        } finally {
            Files.deleteIfExists(directory.resolve("task-owner.lock"));
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void startupFailureAndClosingBeforeRunReleaseTheScanOccupation() {
        TaskActivity activity = new TaskActivity();
        var initializer = new ConstraintOnlyDriver();
        initializer.failInitialization = true;
        Neo4jEntityStore graph = new Neo4jEntityStore(initializer.driver(), null, new EntityJson());
        // 初始化先失败，其余仓储和恢复依赖不会被访问。
        try (StartupTaskRecovery failed = new StartupTaskRecovery(null, null, null, null, graph, 1, activity)) {
            assertTrue(activity.snapshot().busy());
            assertThrows(IllegalStateException.class, new org.junit.jupiter.api.function.Executable() {
                @Override public void execute() { failed.run(null); }
            });
            assertFalse(activity.snapshot().busy());
        }
        try (StartupTaskRecovery unopened = new StartupTaskRecovery(null, null, null, null, graph, 1, activity)) {
            assertTrue(activity.snapshot().busy());
        }
        assertFalse(activity.snapshot().busy());
    }

    @Test
    void closedEntryStopsEveryAlreadyAcceptedRunInTheBatch() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("checks", "build"), "startup-closed-entry-");
        CountDownLatch releaseModels = new CountDownLatch(1);
        CountDownLatch modelEntered = new CountDownLatch(1);
        try {
            try (TaskProcessLock owner = TaskProcessLock.acquire(directory)) {
                StoredTaskRecords records = repository(new MemoryParticipant(), owner.ownerId());
                List<String> tasks = List.of(records.create(spec("queued-one")).taskId(),
                        records.create(spec("queued-two")).taskId());
                StopSignals signals = new StopSignals(tasks, modelEntered);
                AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
                    @Override public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                        modelEntered.countDown();
                        try {
                            if (!releaseModels.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("停机竞争检查未放行模型");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                        return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null,
                                Map.of("goal", context.goal()));
                    }
                };
                try (Runtime runtime = new Runtime(records, planner, 2, signals)) {
                    // 模拟恢复已准备领取一批时，外部入口先关闭。
                    runtime.entry.close();
                    ConstraintOnlyDriver initializer = new ConstraintOnlyDriver();
                    Neo4jEntityStore graph = new Neo4jEntityStore(initializer.driver(), null, new EntityJson());
                    try (StartupTaskRecovery startup = new StartupTaskRecovery(owner, records,
                            new TaskRecoveryService(runtime.orchestrator, records), runtime.entry, graph, 2)) {
                        startup.run(null);
                        try {
                            assertTrue(signals.stopped.await(5, TimeUnit.SECONDS),
                                    "整批两个已领取任务都必须收到即时停止信号");
                            assertEquals(0, modelEntered.getCount(), "至少一个模型必须已经进入");
                        } finally {
                            releaseModels.countDown();
                        }
                        waitUntil(new BooleanSupplier() {
                            @Override public boolean getAsBoolean() {
                                return records.activeRuns(owner.ownerId(), 10).isEmpty();
                            }
                        });
                        for (String taskId : tasks) {
                            assertTrue(hasStopRequest(records, taskId), "真实收尾后才核对持久停止事件");
                            assertEquals(Kind.CONTINUE, records.query(taskId).latestKind(),
                                    "本地停机不能改写用户控制意图");
                            int ended = 0;
                            for (var event : records.readEvents(taskId)) {
                                if (event.kind() == com.example.myhelper.records.TaskRecordPort.EventKind.RUN_ENDED) {
                                    ended++;
                                    assertTrue(records.readArchive(new ArchiveRef(taskId, event.rootRunId())).isPresent());
                                }
                            }
                            assertEquals(1, ended, "整批两个已领取任务都须真实收尾，不能遗留第二个");
                        }
                        assertEquals(2, records.recoverable(10).size(), "停机后原目标仍可接续");
                        assertEquals(0, runtime.actions.get());
                    }
                }
            }
        } finally {
            releaseModels.countDown();
            Files.deleteIfExists(directory.resolve("task-owner.lock"));
            Files.deleteIfExists(directory);
        }
    }

    /** 检查用即时停止观察；持久事件仍由真实收尾产生。 */
    private static final class StopSignals implements TaskLogPort {
        /** 只接受本轮两个任务的通知。 */
        private final java.util.Set<String> tasks;
        /** 同一任务的重复通知不能提前放行。 */
        private final java.util.Set<String> stoppedTasks = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** 两个本地停止请求均已被观察。 */
        private final CountDownLatch stopped = new CountDownLatch(2);
        /** 第一个真实模型进入信号。 */
        private final CountDownLatch modelEntered;
        /** 用第二次受理建立检查专用屏障。 */
        private final AtomicInteger starts = new AtomicInteger();

        private StopSignals(List<String> tasks, CountDownLatch modelEntered) {
            this.tasks = java.util.Set.copyOf(tasks);
            this.modelEntered = modelEntered;
        }

        @Override
        public void publish(TaskLogPort.Event event) {
            if (event.context() == null || !tasks.contains(event.context().taskId())) {
                return;
            }
            switch (event.kind()) {
                case RUN_STARTED -> {
                    if (starts.incrementAndGet() == 2) {
                        // 仅检查替身等待：第一运行已派发，第二运行尚未返回句柄。
                        // 先让一个模型进入，再让 Startup 开始 adopt 并请求停止。
                        try {
                            if (!modelEntered.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("第一模型未进入，无法检查在途停机");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                    }
                }
                case STOP_REQUESTED -> {
                    if (stoppedTasks.add(event.context().taskId())) {
                        stopped.countDown();
                    }
                }
                default -> { }
            }
        }
    }

    private static boolean hasStopRequest(StoredTaskRecords records, String taskId) {
        for (var event : records.readEvents(taskId)) {
            if (event.kind() == com.example.myhelper.records.TaskRecordPort.EventKind.RUN_INTERRUPTION_REQUESTED) {
                return true;
            }
        }
        return false;
    }

    private static StoredTaskRecords repository(MemoryParticipant database, String ownerId) {
        return new StoredTaskRecords(new DualWriteStore(database, new RejectVector()),
                StoredTaskRecords.retainingReviews(), new Consumer<ArchiveRef>() {
                    @Override public void accept(ArchiveRef ignored) { }
                }, ownerId);
    }

    private static TaskSpec spec(String goal) {
        return new TaskSpec(goal, Map.of(), "A", List.of());
    }

    private static boolean tracesReleased(List<ArchiveRef> archives, ExecutionTraceStore traces) {
        for (ArchiveRef ref : archives) {
            try {
                traces.snapshot(ref.rootRunId());
                return false;
            } catch (NullPointerException missing) {
                // ExecutionTraceStore 用缺失根运行明确表达图已释放。
            }
        }
        return true;
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "等待归档提交及 adopt 图清理超时");
    }

    private static final class BlockingFirstPlanner implements AgentOrchestrator.Planner {
        /** 只阻塞首次规划，后续页面立即返回。 */
        private final AtomicInteger calls = new AtomicInteger();
        /** 第一任务已进入真实模型调用。 */
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        /** 用于证明未收尾时没有启动第二任务。 */
        private final CountDownLatch secondEntered = new CountDownLatch(1);
        /** 三个任务最终都必须到达规划器。 */
        private final CountDownLatch allEntered = new CountDownLatch(3);
        /** 显式控制第一批返回，不靠工作耗时推测先后。 */
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        @Override public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
            int number = calls.incrementAndGet();
            allEntered.countDown();
            if (number == 1) {
                firstEntered.countDown();
                try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS)) { throw new AssertionError("第一批未放行"); }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            } else {
                secondEntered.countDown();
            }
            return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null,
                    Map.of("goal", context.goal()));
        }
    }

    /** 只允许生产 initialize 创建唯一约束，任何真实查询均使检查失败。 */
    private static final class ConstraintOnlyDriver implements InvocationHandler {
        /** 验证启动只初始化一次约束。 */
        private final AtomicInteger initializations = new AtomicInteger();
        /** 在获取候选前模拟唯一约束初始化失败。 */
        private boolean failInitialization;
        private Driver driver() { return proxy(Driver.class); }
        private <T> T proxy(Class<T> type) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, this));
        }
        @Override public Object invoke(Object target, Method method, Object[] args) {
            switch (method.getName()) {
                case "session": return proxy(Session.class);
                case "run":
                    if (failInitialization) { throw new IllegalStateException("模拟约束初始化失败"); }
                    assertEquals("CREATE CONSTRAINT world_entity_id IF NOT EXISTS FOR (e:WorldEntity) REQUIRE e.id IS UNIQUE",
                            args[0]);
                    initializations.incrementAndGet();
                    return proxy(Result.class);
                case "consume", "close": return null;
                default: throw new AssertionError("不应访问真实 Neo4j 操作: " + method.getName());
            }
        }
    }

    private static final class Runtime implements AutoCloseable {
        /** 启动扫描与实际根运行共用同一个占用仲裁器。 */
        private final TaskActivity activity = new TaskActivity();
        /** 工作线程独立于启动扫描等待线程。 */
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        /** 截止计时器不承担模型工作。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 分页检查用容量一，停机批量检查用容量二。 */
        private final ExecutionTraceStore traces;
        /** 防止恢复误走普通工具。 */
        private final AtomicInteger actions = new AtomicInteger();
        /** 使用生产递归编排和恢复入口。 */
        private final AgentOrchestrator orchestrator;
        /** 真实应用入口负责管理恢复句柄和清理内存图。 */
        private final TaskEntry entry;
        private Runtime(StoredTaskRecords records, AgentOrchestrator.Planner planner) {
            this(records, planner, 1, TaskLogPort.disabled());
        }
        private Runtime(StoredTaskRecords records, AgentOrchestrator.Planner planner, int maxRuns, TaskLogPort taskLog) {
            traces = new ExecutionTraceStore(maxRuns, 200, 1000);
            AgentOrchestrator.Checks checks = new AgentOrchestrator.Checks() {
                @Override public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) { return true; }
                @Override public boolean verify(String goal, Map<String, Object> args, Object value) { return true; }
            };
            AgentOrchestrator.ActionExecutor executor = new AgentOrchestrator.ActionExecutor() {
                @Override public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args,
                                                                    AgentOrchestrator.CallContext context) {
                    actions.incrementAndGet();
                    throw new AssertionError("启动恢复不应重放旧工具");
                }
            };
            var search = CapabilitySearchPort.disabled();
            orchestrator = new AgentOrchestrator(planner, search, executor, checks,
                    traces, workers, timer, new AgentOrchestrator.Limits(5, 5, 20, 20, 30, 30, Duration.ofSeconds(15)),
                    new TestModelAdapter(List.of("A")), records, new CheckedCapabilityUse(search, checks),
                    ToolReliabilityGate.unavailable(), taskLog, activity);
            entry = new TaskEntry(orchestrator, "A", List.of(), traces);
        }
        @Override public void close() {
            entry.close();
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }
    /** 实际 JSON 编解码模拟进程重启及数值类型变化，不共享生产 State 对象。 */
    private static final class MemoryParticipant implements DualWriteStore.Participant {
        /** 只保存序列化后的完整实体。 */
        private final Map<String, String> data = new TreeMap<>();
        /** 与生产统一存储相同的实体编解码器。 */
        private final EntityJson json = new EntityJson();
        /** 确认最终提交只使用一次原子条件写。 */
        private int commits;
        /** 写入之前持续失败。 */
        private boolean failBefore;
        /** 写入已完成但返回异常，只触发一次。 */
        private boolean commitThenThrow;

        @Override public DualWriteStore.Change open(String operationId, List<WorldEntity> entities) {
            throw new AssertionError("记录权威状态必须走 CAS，不能普通覆盖写");
        }

        @Override public synchronized boolean writeConditionally(WorldEntity expected, WorldEntity replacement) {
            if (failBefore) { throw new IllegalStateException("before-commit"); }
            String current = data.get(replacement.id());
            if (expected == null ? current != null
                    : current == null || !json.parse(current).equals(json.parse(json.encode(expected)))) {
                return false;
            }
            data.put(replacement.id(), json.encode(replacement));
            commits++;
            if (commitThenThrow) {
                commitThenThrow = false;
                throw new IllegalStateException("reply-lost-after-commit");
            }
            return true;
        }

        @Override public synchronized Optional<WorldEntity> read(String id) {
            String encoded = data.get(id);
            return encoded == null ? Optional.empty() : Optional.of(json.entity(encoded));
        }

        @Override public synchronized StorageQuery.Page query(StorageQuery.PageRequest request) {
            List<WorldEntity> found = new ArrayList<>();
            String after = request.cursor() == null ? "" : request.cursor().position();
            boolean more = false;
            for (Map.Entry<String, String> entry : data.entrySet()) {
                if (entry.getKey().compareTo(after) <= 0 || !matches(json.parse(entry.getValue()), request.filter())) {
                    continue;
                }
                if (found.size() == request.limit()) { more = true; break; }
                found.add(json.entity(entry.getValue()));
            }
            StorageQuery.Cursor next = more ? new StorageQuery.Cursor(DualWriteStore.Database.NEO4J,
                    found.getLast().id()) : null;
            return new StorageQuery.Page(found, next);
        }

        private boolean matches(JsonNode entity, StorageQuery.Filter filter) {
            for (StorageQuery.Match match : filter.matches()) {
                JsonNode actual = entity.at(match.pointer());
                boolean accepted = false;
                for (Object value : match.values()) {
                    JsonNode expected = json.parse(json.encode(value));
                    if (actual.isArray()) {
                        for (JsonNode member : actual) {
                            if (sameScalar(member, expected)) { accepted = true; }
                        }
                    } else if (sameScalar(actual, expected)) {
                        accepted = true;
                    }
                }
                if (!accepted) { return false; }
            }
            return true;
        }

        private boolean sameScalar(JsonNode left, JsonNode right) {
            if (left.isNumber() && right.isNumber()) {
                return left.decimalValue().compareTo(right.decimalValue()) == 0;
            }
            return left.equals(right);
        }
    }

    private static final class RejectVector implements DualWriteStore.Participant {
        @Override public DualWriteStore.Change open(String operationId, List<WorldEntity> entities) {
            throw new AssertionError("记录仓储不应隐式双写向量库");
        }
        @Override public Optional<WorldEntity> read(String id) {
            throw new AssertionError("记录仓储不应从向量库回退");
        }
    }


}
