package com.example.myhelper.orchestration;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.records.ExecutionTracePort;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.support.RecordingTaskRecords;
import com.example.myhelper.support.TestModelAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

/** 最终归档在真实收尾后提交，包含固定图；提交故障不得伪造消费通知。 */
class FinalArchiveTest {
    /** 工具真实写入的产物，归档或模型收尾失败不能抹去。 */
    private static final Map<String, Object> ARTIFACT = Map.of("file", "result.txt", "version", 2);

    @Test
    void normalCompletionArchivesClosedGraphAndActualToolEvidence() throws Exception {
        for (boolean withTool : List.of(false, true)) {
            ArchiveRecords records = new ArchiveRecords();
            ExecutionTraceStore traces = new ExecutionTraceStore(2, 200, 1000);
            try (Harness harness = new Harness(records, traces, new WorkPlanner(withTool), new WriteAction())) {
                AgentOrchestrator.Handle handle = harness.submit();
                assertEquals(AgentOrchestrator.Status.SUCCESS, handle.result().get(5, TimeUnit.SECONDS).status());
                handle.settled().get(5, TimeUnit.SECONDS);
                TaskRecordPort.RunFinalization saved = records.saved;
                assertNotNull(saved);
                assertTrue(saved.graphComplete());
                assertTrue(saved.graph().closed());
                assertTrue(saved.gaps().isEmpty());
                assertEquals(handle.taskId(), saved.event().taskId());
                assertEquals(handle.rootRunId(), saved.event().rootRunId());
                assertEquals(TaskRecordPort.EventKind.RUN_ENDED, saved.event().kind());
                assertFalse(saved.graph().nodes().isEmpty());
                for (ExecutionTraceStore.Node node : saved.graph().nodes().values()) {
                    assertNotNull(node.endedAt(), "不能把尚未结束的节点宣布为完整图");
                    assertNotEquals(ExecutionTraceStore.NodeStatus.RUNNING, node.status());
                }
                if (withTool) {
                    assertToolArtifact(saved.graph());
                    assertFalse(saved.graph().edges().isEmpty());
                    boolean verified = false;
                    for (TaskRecordPort.ExecutionEvent event : records.delegate.events(handle.taskId())) {
                        if (event.kind() == TaskRecordPort.EventKind.VERIFIED) { verified = true; }
                    }
                    assertTrue(verified, "归档提交时，执行中的验收事件已由记录端口接收");
                }
                assertEquals(1, records.wakes.get());
                assertNull(records.delegate.activeClaim(handle.taskId()));
            }
        }
    }

    @Test
    void cancellationWaitsForRealToolReturnBeforeArchiving() throws Exception {
        ArchiveRecords records = new ArchiveRecords();
        BlockingWrite actions = new BlockingWrite();
        try (Harness harness = new Harness(records, new ExecutionTraceStore(2, 200, 1000),
                new WorkPlanner(true), actions)) {
            AgentOrchestrator.Handle handle = harness.submit();
            try {
                assertTrue(actions.started.await(3, TimeUnit.SECONDS));
                handle.cancel().run();
                assertEquals(AgentOrchestrator.Status.CANCELLED, handle.result().get(2, TimeUnit.SECONDS).status());
                assertNull(records.saved, "对外取消不能提前提交最终图");
                assertNotNull(records.delegate.activeClaim(handle.taskId()));
                assertEquals(0, records.wakes.get());
            } finally {
                actions.release.countDown();
            }
            handle.settled().get(5, TimeUnit.SECONDS);
            assertNotNull(records.saved);
            assertTrue(records.saved.graph().closed());
            assertToolArtifact(records.saved.graph());
            assertNull(records.delegate.activeClaim(handle.taskId()));
            assertEquals(1, records.wakes.get());
        }
    }

    @Test
    void snapshotFailureRecordsExplicitGapAndPreservesActualOutput() throws Exception {
        ArchiveRecords records = new ArchiveRecords();
        try (Harness harness = new Harness(records, new MissingSnapshot(), new WorkPlanner(true), new WriteAction())) {
            AgentOrchestrator.Handle handle = harness.submit();
            AgentOrchestrator.Outcome outcome = handle.result().get(5, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.UNKNOWN, outcome.status());
            assertEquals(ARTIFACT, outcome.value());
            assertSettledFails(handle);
            assertNotNull(records.saved);
            assertNull(records.saved.graph());
            assertFalse(records.saved.graphComplete());
            assertFalse(records.saved.gaps().isEmpty());
            assertEquals(0, records.wakes.get());
            assertEquals(ARTIFACT, ((Map<?, ?>) records.saved.event().details().get("outcome")).get("value"));
            assertNull(records.delegate.activeClaim(handle.taskId()), "无图收尾仍保存结束事实并释放领取");
        }
    }

    @Test
    void failedArchiveSubmissionDoesNotWakeOrReleaseClaim() throws Exception {
        ArchiveRecords records = new ArchiveRecords();
        records.failEveryEnd = true;
        try (Harness harness = new Harness(records, new ExecutionTraceStore(2, 200, 1000),
                new WorkPlanner(true), new WriteAction())) {
            AgentOrchestrator.Handle handle = harness.submit();
            AgentOrchestrator.Outcome outcome = handle.result().get(5, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.UNKNOWN, outcome.status());
            assertEquals(ARTIFACT, outcome.value());
            assertSettledFails(handle);
            assertNull(records.saved);
            assertEquals(0, records.wakes.get());
            assertEquals(3, records.attempts.size());
            assertSame(records.attempts.getFirst(), records.attempts.get(1));
            assertSame(records.attempts.getFirst(), records.attempts.get(2));
            assertNotNull(records.delegate.activeClaim(handle.taskId()), "未确认结束提交不能提前释放领取");
        }
    }

    @Test
    void failedSubmissionRetriesSameFrozenFinalizationAndEventIdentity() {
        ArchiveRecords records = new ArchiveRecords();
        records.failNextEnd = true;
        RunRecordAccess access = new RunRecordAccess(records, "root-retry",
                new AgentOrchestrator.Request("root", Map.of(), "A", List.of("write")), null);
        ExecutionTraceStore.Snapshot graph = completeGraph();
        AgentOrchestrator.Outcome outcome = new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, ARTIFACT, null);
        access.end(outcome, graph, List.of());
        assertEquals(2, records.attempts.size());
        assertSame(records.attempts.getFirst(), records.attempts.getLast(),
                "自动重试必须复用原提交时间、身份和固定载荷");
        assertEquals(1, records.saved.graph().nodes().size());
        assertEquals(1, records.wakes.get());
        assertNull(records.delegate.activeClaim(access.taskId()));
        access.end(outcome, graph, List.of());
        assertEquals(2, records.attempts.size());
    }

    @Test
    void unconfirmedEvidenceIsDistinctFromCompleteGraphAndPreventsWake() {
        ArchiveRecords records = new ArchiveRecords();
        records.rejectAppend = true;
        RunRecordAccess access = new RunRecordAccess(records, "root-gap",
                new AgentOrchestrator.Request("root", Map.of(), "A", List.of()), null);
        assertThrows(IllegalStateException.class, new Executable() {
            @Override public void execute() {
                access.emit("run", "task", "branch", 0, TaskRecordPort.EventKind.OBSERVED, Map.of("phase", "BEFORE"));
            }
        });
        access.end(new AgentOrchestrator.Outcome(AgentOrchestrator.Status.UNKNOWN, ARTIFACT, "record-failed"),
                completeGraph(), List.of());
        assertTrue(records.saved.graphComplete());
        assertFalse(records.saved.evidenceComplete());
        assertTrue(records.saved.evidenceGaps().getFirst().contains("OBSERVED"));
        assertEquals(0, records.wakes.get());
    }

    private static ExecutionTraceStore.Snapshot completeGraph() {
        Instant now = Instant.now();
        ExecutionTraceStore.Node node = new ExecutionTraceStore.Node("task", "run", null,
                ExecutionTraceStore.NodeKind.TASK, "root", 0, Map.of(),
                ExecutionTraceStore.NodeStatus.SUCCESS, ARTIFACT, null, now, now);
        return new ExecutionTraceStore.Snapshot(Map.of("task", node), List.of(),
                List.of(new ExecutionTraceStore.Event(1, "task", "CREATED", now),
                        new ExecutionTraceStore.Event(2, "task", "SUCCESS", now)), true);
    }

    private static void assertSettledFails(AgentOrchestrator.Handle handle) {
        assertThrows(ExecutionException.class, new Executable() {
            @Override public void execute() throws Throwable {
                handle.settled().get(5, TimeUnit.SECONDS);
            }
        });
    }

    private static void assertToolArtifact(ExecutionTraceStore.Snapshot graph) {
        boolean found = false;
        for (ExecutionTraceStore.Node node : graph.nodes().values()) {
            if (node.kind() == ExecutionTraceStore.NodeKind.TOOL) {
                assertEquals(ARTIFACT, ((Map<?, ?>) node.output()).get("value"));
                found = true;
            }
        }
        assertTrue(found, "最终图必须保留工具真实产物");
    }

    private static final class WorkPlanner implements AgentOrchestrator.Planner {
        /** 是否先提交一次工具计划，再确认完成。 */
        private final boolean withTool;
        private WorkPlanner(boolean withTool) { this.withTool = withTool; }
        @Override public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
            if (withTool && context.history().isEmpty()) {
                TaskPlan.Step step = new TaskPlan.Step("write", "write", TaskPlan.Kind.TOOL,
                        "write", Map.of("file", "result.txt"), List.of(), Map.of());
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN,
                        new TaskPlan("root", List.of(step)), null);
            }
            return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, ARTIFACT);
        }
    }

    private static final class WriteAction implements AgentOrchestrator.ActionExecutor {
        @Override public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args,
                                                           AgentOrchestrator.CallContext context) {
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, ARTIFACT, null);
        }
    }

    private static final class BlockingWrite implements AgentOrchestrator.ActionExecutor {
        /** 真实工具进入信号。 */
        private final CountDownLatch started = new CountDownLatch(1);
        /** 显式放行返回，避免用 sleep 猜测收尾时机。 */
        private final CountDownLatch release = new CountDownLatch(1);
        @Override public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args,
                                                           AgentOrchestrator.CallContext context) {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) { throw new AssertionError("工具返回未获放行"); }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, ARTIFACT, null);
        }
    }

    /** 仅用检查替身转交既有领取/事件行为，观察最终提交边界。 */
    private static final class ArchiveRecords implements TaskRecordPort {
        /** 原有检查记录替身，非生产持久实现。 */
        private final RecordingTaskRecords delegate = new RecordingTaskRecords();
        /** 每次最终提交收到的原对象。 */
        private final List<RunFinalization> attempts = new ArrayList<>();
        /** 已确认的固定图提交。 */
        private volatile RunFinalization saved;
        /** 模拟一次提交失败。 */
        private boolean failNextEnd;
        /** 模拟持续提交失败，验证有界重试。 */
        private boolean failEveryEnd;
        /** 模拟逐阶段事件未取得写入确认。 */
        private boolean rejectAppend;
        /** 成功提交后的唤醒次数。 */
        private final AtomicInteger wakes = new AtomicInteger();

        @Override public boolean enabled() { return true; }
        @Override public TaskSnapshot create(TaskSpec task) { return delegate.create(task); }
        @Override public TaskSnapshot load(String taskId) { return delegate.load(taskId); }
        @Override public RunClaim beginRun(String taskId, String rootRunId) { return delegate.beginRun(taskId, rootRunId); }
        @Override public RecoverySnapshot query(RunClaim claim, String runId) { return delegate.query(claim, runId); }
        @Override public void append(RunClaim claim, ExecutionEvent event) {
            if (rejectAppend) { throw new IllegalStateException("append-failed"); }
            delegate.append(claim, event);
        }
        @Override public RecoverySnapshot review(RunClaim claim, ResolutionProposal proposal) { return delegate.review(claim, proposal); }
        @Override public void endRun(RunClaim claim, ExecutionEvent event) { delegate.endRun(claim, event); }
        @Override public synchronized void endRun(RunClaim claim, RunFinalization finalization) {
            attempts.add(finalization);
            if (failEveryEnd || failNextEnd) {
                failNextEnd = false;
                throw new IllegalStateException("archive-submit-failed");
            }
            delegate.endRun(claim, finalization.event());
            saved = finalization;
        }
        @Override public void wakeConsolidation(RunClaim claim) {
            assertNotNull(saved, "未确认最终提交不得唤醒");
            wakes.incrementAndGet();
        }
    }

    private static final class MissingSnapshot implements ExecutionTracePort {
        /** 写入正常，仅模拟最后读取失败。 */
        private final ExecutionTraceStore delegate = new ExecutionTraceStore(2, 200, 1000);
        @Override public void open(String root) { delegate.open(root); }
        @Override public void add(String root, ExecutionTraceStore.Node node) { delegate.add(root, node); }
        @Override public void edge(String root, ExecutionTraceStore.Edge edge) { delegate.edge(root, edge); }
        @Override public void finish(String root, String node, ExecutionTraceStore.NodeStatus status, Object output, String error) {
            delegate.finish(root, node, status, output, error);
        }
        @Override public void close(String root) { delegate.close(root); }
        @Override public ExecutionTraceStore.Snapshot snapshot(String root) {
            throw new IllegalStateException("snapshot-read-failed");
        }
    }

    private static final class Harness implements AutoCloseable {
        /** 检查工作线程。 */
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        /** 截止时间线程。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 被检查的真实循环。 */
        private final AgentOrchestrator orchestrator;
        private Harness(ArchiveRecords records, ExecutionTracePort traces, AgentOrchestrator.Planner planner,
                        AgentOrchestrator.ActionExecutor actions) {
            AgentOrchestrator.Checks checks = new AgentOrchestrator.Checks() {
                @Override public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) { return true; }
                @Override public boolean verify(String goal, Map<String, Object> args, Object output) { return true; }
            };
            orchestrator = new AgentOrchestrator(planner, CapabilitySearchPort.disabled(), actions, checks,
                    traces, workers, timer, new AgentOrchestrator.Limits(5, 5, 20, 20, 30, 30, Duration.ofSeconds(15)),
                    new TestModelAdapter(List.of("A")), records);
        }
        private AgentOrchestrator.Handle submit() {
            return orchestrator.submit(new AgentOrchestrator.Request("root", Map.of(), "A", List.of("write")));
        }
        @Override public void close() {
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }
}
