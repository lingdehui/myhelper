package com.example.myhelper.application;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.orchestration.ExecutionTraceStore;
import com.example.myhelper.records.TaskInstructionPort;
import com.example.myhelper.records.TaskInstructionPort.RecoveryCandidate;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.support.RecordingTaskRecords;
import com.example.myhelper.support.TestModelAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

/** 应用有界扫描使用真实循环；记录替身不证明生产跨重启恢复已实现。 */
class TaskRecoveryServiceTest {
    @Test
    void failedCandidateDoesNotBlockLaterTaskAndDuplicatesDoNotClaimTwice() throws Exception {
        ScanRecords records = new ScanRecords();
        String taskId = records.createTask("继续已保存的任务");
        records.candidates = List.of(new RecoveryCandidate("missing-task", "检查未知任务"),
                new RecoveryCandidate(taskId, "外部已核对可恢复"),
                new RecoveryCandidate(taskId, "重复候选"));
        try (Harness harness = new Harness(records)) {
            List<TaskRecoveryService.RecoveryAttempt> attempts = harness.service.resumeAvailable(3);
            assertEquals(2, attempts.size());
            assertNull(attempts.get(0).handle());
            assertTrue(attempts.get(0).error().contains("missing-task"));
            TaskRecoveryService.RecoveryAttempt accepted = attempts.get(1);
            assertEquals(taskId, accepted.taskId());
            assertEquals("外部已核对可恢复", accepted.reason());
            assertNull(accepted.error());
            assertEquals(taskId, accepted.handle().taskId());
            assertEquals(AgentOrchestrator.Status.NEEDS_INPUT,
                    accepted.handle().settled().get(5, TimeUnit.SECONDS).status());
            // 应用扫描没有 beginRun；唯一领取来自真实 resume 的内部入口。
            assertEquals(1, records.beginCalls.get());
            assertEquals(1, harness.planner.calls.get());
            assertNull(records.delegate.activeClaim(taskId));
        }
    }

    @Test
    void oversizedBatchIsRejectedBeforeAnyTaskStarts() throws Exception {
        ScanRecords records = new ScanRecords();
        String first = records.createTask("first");
        String second = records.createTask("second");
        records.candidates = List.of(new RecoveryCandidate(first, "ready"), new RecoveryCandidate(second, "ready"));
        try (Harness harness = new Harness(records)) {
            assertThrows(IllegalStateException.class, new ScanCall(harness.service, 1));
            assertEquals(0, records.beginCalls.get());
            assertEquals(0, harness.planner.calls.get());
        }
    }

    @Test
    void invalidBatchAndDisabledPortCannotStartWork() throws Exception {
        ScanRecords records = new ScanRecords();
        String first = records.createTask("first");
        List<RecoveryCandidate> invalid = new ArrayList<>();
        invalid.add(new RecoveryCandidate(first, "ready"));
        invalid.add(null);
        records.candidates = invalid;
        try (Harness harness = new Harness(records)) {
            assertThrows(NullPointerException.class, new ScanCall(harness.service, 2));
            assertEquals(0, records.beginCalls.get());
            records.instructionsEnabled = false;
            assertThrows(IllegalStateException.class, new ScanCall(harness.service, 2));
            assertThrows(IllegalArgumentException.class, new ScanCall(harness.service, 0));
            assertEquals(0, harness.planner.calls.get());
        }
    }

    @Test
    void taskPausedAfterScanCannotBeResumed() throws Exception {
        ScanRecords records = new ScanRecords();
        String taskId = records.createTask("暂停的任务");
        records.delegate.instructions().accept(new TaskInstructionPort.Command(taskId, "pause", 0,
                TaskInstructionPort.Kind.PAUSE, "暂停，等用户继续"));
        // 模拟扫描与实际领取之间发生暂停；服务不能因候选曾出现就绕过当前资格。
        records.candidates = List.of(new RecoveryCandidate(taskId, "扫描时曾可恢复"));
        try (Harness harness = new Harness(records)) {
            List<TaskRecoveryService.RecoveryAttempt> attempts = harness.service.resumeAvailable(1);
            assertEquals(1, attempts.size());
            assertNull(attempts.getFirst().handle());
            assertNotNull(attempts.getFirst().error());
            assertEquals(0, harness.planner.calls.get());
            assertNull(records.delegate.activeClaim(taskId));
        }
    }

    /** 明确命名的检查回调，避免为一行断言引入 Lambda。 */
    private record ScanCall(
            /** 本次被检查的恢复入口。 */
            TaskRecoveryService service,
            /** 本次请求的候选上限。 */
            int limit) implements Executable {
        @Override
        public void execute() {
            service.resumeAvailable(limit);
        }
    }

    /** 只替换候选列表；领取、执行事实与指令状态仍委托共享的记录替身。 */
    private static final class ScanRecords implements TaskRecordPort {
        /** 本检查唯一的任务事实和运行领取来源。 */
        private final RecordingTaskRecords delegate = new RecordingTaskRecords();
        /** 可注入过期、重复或损坏的外部扫描结果。 */
        private List<RecoveryCandidate> candidates = List.of();
        /** 观察实际领取次数，不能由服务扫描额外增加。 */
        private final AtomicInteger beginCalls = new AtomicInteger();
        /** 模拟未装配指令入口的应用配置。 */
        private boolean instructionsEnabled = true;
        /** 候选列表的包装端口，其他版本检查全部委托原实现。 */
        private final TaskInstructionPort listing;

        private ScanRecords() {
            listing = new ListingInstructions(this, delegate.enableInstructions());
        }

        private String createTask(String goal) {
            return create(new TaskSpec(goal, Map.of(), "A", List.of())).taskId();
        }

        @Override
        public boolean enabled() { return true; }
        @Override
        public TaskInstructionPort instructions() {
            return instructionsEnabled ? listing : TaskInstructionPort.disabled();
        }
        @Override
        public TaskSnapshot create(TaskSpec task) { return delegate.create(task); }
        @Override
        public TaskSnapshot load(String taskId) { return delegate.load(taskId); }
        @Override
        public RunClaim beginRun(String taskId, String rootRunId) {
            beginCalls.incrementAndGet();
            return delegate.beginRun(taskId, rootRunId);
        }
        @Override
        public RecoverySnapshot query(RunClaim claim, String runId) { return delegate.query(claim, runId); }
        @Override
        public void append(RunClaim claim, ExecutionEvent event) { delegate.append(claim, event); }
        @Override
        public RecoverySnapshot review(RunClaim claim, ResolutionProposal proposal) { return delegate.review(claim, proposal); }
        @Override
        public void endRun(RunClaim claim, ExecutionEvent event) { delegate.endRun(claim, event); }
    }

    /** 仅提供本测试指定的扫描结果，不能在扫描时替循环领取任务。 */
    private static final class ListingInstructions implements TaskInstructionPort {
        /** 当前检查指定的候选返回。 */
        private final ScanRecords source;
        /** 与执行事实共用状态的指令替身。 */
        private final TaskInstructionPort delegate;

        private ListingInstructions(ScanRecords source, TaskInstructionPort delegate) {
            this.source = source;
            this.delegate = delegate;
        }

        @Override
        public boolean enabled() { return true; }
        @Override
        public Receipt accept(Command command) { return delegate.accept(command); }
        @Override
        public Snapshot bind(TaskRecordPort.RunClaim claim) { return delegate.bind(claim); }
        @Override
        public Directive check(TaskRecordPort.RunClaim claim, long revision) { return delegate.check(claim, revision); }
        @Override
        public boolean applied(TaskRecordPort.RunClaim claim, long revision, String runId,
                               long planRevision, Map<String, Object> plan) {
            return delegate.applied(claim, revision, runId, planRevision, plan);
        }
        @Override
        public boolean complete(TaskRecordPort.RunClaim claim, long revision, long snapshotRevision,
                                Map<String, Object> outcome) {
            return delegate.complete(claim, revision, snapshotRevision, outcome);
        }
        @Override
        public List<RecoveryCandidate> recoverable(int limit) { return source.candidates; }
        @Override
        public Snapshot query(String taskId) { return delegate.query(taskId); }
    }

    /** 恢复后请求用户补充，避免把接受恢复冒充任务完成。 */
    private static final class NeedsInputPlanner implements AgentOrchestrator.Planner {
        /** 实际调用次数。 */
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
            calls.incrementAndGet();
            return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.NEEDS_INPUT, null, "请确认剩余要求");
        }
    }

    /** 当前服务实际调用生产循环，工作线程和定时器只属于检查。 */
    private static final class Harness implements AutoCloseable {
        /** 调度恢复任务的检查线程。 */
        private final ExecutorService workers = Executors.newSingleThreadExecutor();
        /** 检查超时调度。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 检查规划器调用，不依赖真实模型。 */
        private final NeedsInputPlanner planner = new NeedsInputPlanner();
        /** 被测应用恢复服务。 */
        private final TaskRecoveryService service;

        private Harness(ScanRecords records) {
            AgentOrchestrator.ActionExecutor actions = new AgentOrchestrator.ActionExecutor() {
                @Override
                public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args,
                                                          AgentOrchestrator.CallContext context) {
                    throw new AssertionError("本恢复入口检查不应执行工具");
                }
            };
            AgentOrchestrator.Checks checks = new AgentOrchestrator.Checks() {
                @Override
                public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) { return true; }
                @Override
                public boolean verify(String goal, Map<String, Object> args, Object output) { return true; }
            };
            AgentOrchestrator orchestrator = new AgentOrchestrator(planner, CapabilitySearchPort.disabled(),
                    actions, checks, new ExecutionTraceStore(8, 300, 1000), workers, timer,
                    new AgentOrchestrator.Limits(3, 5, 12, 8, 10, 10, Duration.ofSeconds(10)),
                    new TestModelAdapter(List.of("A")), records);
            service = new TaskRecoveryService(orchestrator, records);
        }

        @Override
        public void close() throws InterruptedException {
            workers.shutdown();
            timer.shutdown();
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
            if (!timer.awaitTermination(5, TimeUnit.SECONDS)) {
                timer.shutdownNow();
            }
        }
    }
}
