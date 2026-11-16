package com.example.myhelper.orchestration;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.records.TaskRecordPort.EventKind;
import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.support.RecordingTaskRecords;
import com.example.myhelper.support.TestModelAdapter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 异常边界仍须保留实际产物，并在真实运行结束后才归还外部领取。 */
class RecoveryFailureBoundaryTest {
    /** 工具实际产生的业务结果，用于检查记录失败没有丢弃产物。 */
    private static final Map<String, Object> ARTIFACT = Map.of("file", "sample.txt", "version", "V2");

    @Test
    void rejectedTimerSchedulingReleasesTheAlreadyAcquiredClaim() throws Exception {
        ObservingRecords records = new ObservingRecords(false);
        WriteAction actions = new WriteAction();
        UnexpectedPlanner planner = new UnexpectedPlanner();
        try (Harness harness = new Harness(records, new EntryCapability(), actions, planner, 10)) {
            harness.timer.shutdownNow();
            try {
                AgentOrchestrator.Handle handle = harness.submit();
                assertNotEquals(AgentOrchestrator.Status.SUCCESS, await(handle).status());
            } catch (RejectedExecutionException expected) {
                // 启动阶段可以直接报告定时器拒绝，但已经取得的领取仍必须收尾。
            }
            assertEquals(1, records.delegate.tasks().size());
            String taskId = records.delegate.tasks().getFirst().taskId();
            assertTrue(records.ended.await(5, TimeUnit.SECONDS), "启动失败也必须通知外部结束运行");
            assertNull(records.delegate.activeClaim(taskId));
            assertEquals(1, records.endCalls.get());
            assertEquals(1, countEvents(records.delegate.events(taskId), EventKind.RUN_ENDED));
            assertEquals(0, actions.calls.get());
            assertEquals(0, planner.calls.get());
        }
    }

    @Test
    void failedCapabilityVerificationRecordPreservesResultAsUnknownWithoutReplanning() throws Exception {
        // 第一次工具 VERIFIED 正常保存，第二次组合能力 VERIFIED 才注入写入故障。
        ObservingRecords records = new ObservingRecords(true);
        WriteAction actions = new WriteAction();
        UnexpectedPlanner planner = new UnexpectedPlanner();
        try (Harness harness = new Harness(records, new EntryCapability(), actions, planner, 10)) {
            AgentOrchestrator.Handle handle = harness.submit();
            AgentOrchestrator.Outcome outcome = await(handle);
            assertEquals(AgentOrchestrator.Status.UNKNOWN, outcome.status(), outcome.error());
            assertNotNull(outcome.error());
            assertTrue(outcome.error().contains("combined-verification-record-unavailable"), outcome.error());
            // 组合输出仍包含已执行 write 的业务值，不能因记录失败变成 null。
            Map<?, ?> planOutput = assertInstanceOf(Map.class, outcome.value());
            Map<?, ?> writeOutput = assertInstanceOf(Map.class, planOutput.get("write"));
            assertEquals(ARTIFACT, writeOutput.get("value"));
            assertEquals(1, actions.calls.get());
            assertEquals(0, planner.calls.get());
            assertEquals(2, records.verificationAttempts.get());
            assertEquals(1, countEvents(records.delegate.events(handle.taskId()), EventKind.VERIFIED));
            assertEquals(0, countEvents(records.delegate.events(handle.taskId()), EventKind.MODEL_FAILED));
            assertNull(records.delegate.activeClaim(handle.taskId()));
        }
    }

    @Test
    void exhaustingModelBudgetIsNotReportedAsModelOffline() throws Exception {
        ObservingRecords records = new ObservingRecords(false);
        WriteAction actions = new WriteAction();
        AtomicInteger modelCalls = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                assertEquals(1, modelCalls.incrementAndGet(), "第二次请求应在调用模型前被预算拒绝");
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN, writePlan(), null);
            }
        };
        try (Harness harness = new Harness(records, CapabilitySearchPort.disabled(), actions, planner, 1)) {
            AgentOrchestrator.Handle handle = harness.submit();
            AgentOrchestrator.Outcome outcome = await(handle);
            assertEquals(AgentOrchestrator.Status.FAILED, outcome.status(), outcome.error());
            assertTrue(outcome.error().contains(LlmTexts.MODEL_CALL_LIMIT_REACHED), outcome.error());
            assertEquals(1, modelCalls.get());
            assertEquals(1, actions.calls.get());
            assertEquals(0, countEvents(records.delegate.events(handle.taskId()), EventKind.MODEL_FAILED));
            assertEquals(TaskRecordPort.Disposition.CONTINUE, records.delegate.recovery(handle.taskId()).disposition());
            assertTrue(records.delegate.recovery(handle.taskId()).pending().isEmpty());
            assertNull(records.delegate.activeClaim(handle.taskId()));
        }
    }

    @Test
    void publicCancellationKeepsClaimUntilInFlightToolActuallyReturns() throws Exception {
        ObservingRecords records = new ObservingRecords(false);
        BlockingWriteAction actions = new BlockingWriteAction();
        UnexpectedPlanner planner = new UnexpectedPlanner();
        try (Harness harness = new Harness(records, new EntryCapability(), actions, planner, 10)) {
            AgentOrchestrator.Handle handle = harness.submit();
            try {
                assertTrue(actions.started.await(5, TimeUnit.SECONDS), "先确认工具已进入实际执行");
                handle.cancel().run();
                assertEquals(AgentOrchestrator.Status.CANCELLED, await(handle).status());
                // 对外 Future 已结束，但工具还被 release 阻挡，运行权不能提前归还。
                assertNotNull(records.delegate.activeClaim(handle.taskId()));
                assertEquals(0, records.endCalls.get());
                assertEquals(0, countEvents(records.delegate.events(handle.taskId()), EventKind.RUN_ENDED));
            } finally {
                actions.release.countDown();
            }
            assertTrue(records.ended.await(5, TimeUnit.SECONDS), "工具真实返回后应记录结束并归还领取");
            assertNull(records.delegate.activeClaim(handle.taskId()));
            assertEquals(1, records.endCalls.get());
            List<ExecutionEvent> events = records.delegate.events(handle.taskId());
            int returned = firstEvent(events, EventKind.RETURNED);
            int ended = firstEvent(events, EventKind.RUN_ENDED);
            assertTrue(returned >= 0 && ended > returned, "迟到的真实回执必须早于结束领取");
            Map<?, ?> actual = assertInstanceOf(Map.class, events.get(returned).details().get("outcome"));
            assertEquals("SUCCESS", actual.get("status"));
            assertEquals(ARTIFACT, actual.get("value"));
            assertEquals(AgentOrchestrator.Status.CANCELLED, await(handle).status());
            assertEquals(0, planner.calls.get());
        }
    }

    private static TaskPlan writePlan() {
        return new TaskPlan("write-plan", List.of(new TaskPlan.Step("write", "write", TaskPlan.Kind.TOOL,
                "write", Map.of("file", "sample.txt"), List.of(), Map.of())));
    }

    private static AgentOrchestrator.Outcome await(AgentOrchestrator.Handle handle) throws Exception {
        return handle.result().get(10, TimeUnit.SECONDS);
    }

    private static int countEvents(List<ExecutionEvent> events, EventKind kind) {
        int count = 0;
        for (ExecutionEvent event : events) {
            if (event.kind() == kind) {
                count++;
            }
        }
        return count;
    }

    private static int firstEvent(List<ExecutionEvent> events, EventKind kind) {
        for (int index = 0; index < events.size(); index++) {
            if (events.get(index).kind() == kind) {
                return index;
            }
        }
        return -1;
    }

    /** 包装已有记录替身，只增加精确故障点和可等待的结束信号。 */
    private static final class ObservingRecords implements TaskRecordPort {
        /** 外部记录、领取和事件的检查替身。 */
        private final RecordingTaskRecords delegate = new RecordingTaskRecords();
        /** 是否只拒绝组合能力的第二次验收事件。 */
        private final boolean rejectSecondVerification;
        /** 包含被拒绝事件在内的验收写入尝试次数。 */
        private final AtomicInteger verificationAttempts = new AtomicInteger();
        /** 成功结束领取的次数。 */
        private final AtomicInteger endCalls = new AtomicInteger();
        /** 只在外部已接受 RUN_ENDED 并释放领取后发出信号。 */
        private final CountDownLatch ended = new CountDownLatch(1);

        private ObservingRecords(boolean rejectSecondVerification) {
            this.rejectSecondVerification = rejectSecondVerification;
        }

        @Override
        public boolean enabled() {
            return delegate.enabled();
        }

        @Override
        public TaskSnapshot create(TaskSpec task) {
            return delegate.create(task);
        }

        @Override
        public TaskSnapshot load(String taskId) {
            return delegate.load(taskId);
        }

        @Override
        public RunClaim beginRun(String taskId, String rootRunId) {
            return delegate.beginRun(taskId, rootRunId);
        }

        @Override
        public RecoverySnapshot query(RunClaim claim, String runId) {
            return delegate.query(claim, runId);
        }

        @Override
        public void append(RunClaim claim, ExecutionEvent event) {
            if (event.kind() == EventKind.VERIFIED) {
                int attempt = verificationAttempts.incrementAndGet();
                if (rejectSecondVerification && attempt == 2) {
                    throw new IllegalStateException("combined-verification-record-unavailable");
                }
            }
            delegate.append(claim, event);
        }

        @Override
        public RecoverySnapshot review(RunClaim claim, ResolutionProposal proposal) {
            return delegate.review(claim, proposal);
        }

        @Override
        public void endRun(RunClaim claim, ExecutionEvent event) {
            delegate.endRun(claim, event);
            endCalls.incrementAndGet();
            ended.countDown();
        }
    }

    /** 根入口直接执行已匹配的固定能力，失败时不得自动重跑入口。 */
    private static final class EntryCapability implements CapabilitySearchPort {
        /** 当前检查唯一可用的固定版本。 */
        private final CapabilityLookup.Definition definition = new CapabilityLookup.Definition(
                new CapabilityLookup.Ref("checks", "write-capability", 1), writePlan());

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public CapabilityLookup.Match searchAtEntry(CapabilityLookup.Query query) {
            return new CapabilityLookup.Match(List.of(), Optional.of(definition));
        }

        @Override
        public CapabilityLookup.PlanMatches searchAfterPlanning(String runId, long revision, TaskPlan plan,
                                                               Map<String, Object> input) {
            throw new AssertionError("纯工具入口场景不应重新规划或检索");
        }

        @Override
        public boolean isAvailable(CapabilityLookup.Ref ref) {
            return definition.ref().equals(ref);
        }
    }

    private static final class WriteAction implements AgentOrchestrator.ActionExecutor {
        /** 工具实际进入次数。 */
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args,
                                                 AgentOrchestrator.CallContext context) {
            calls.incrementAndGet();
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, ARTIFACT, null);
        }
    }

    private static final class BlockingWriteAction implements AgentOrchestrator.ActionExecutor {
        /** 确认动作已开始，取消才有真实在途工作可验证。 */
        private final CountDownLatch started = new CountDownLatch(1);
        /** 由检查显式允许动作返回，不靠 sleep 猜测状态。 */
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args,
                                                 AgentOrchestrator.CallContext context) {
            started.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS), "检查应允许在途动作结束");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, ARTIFACT, null);
        }
    }

    private static final class UnexpectedPlanner implements AgentOrchestrator.Planner {
        /** 用于发现记录异常被错误地当作可重新规划的普通业务失败。 */
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
            calls.incrementAndGet();
            throw new AssertionError("该异常边界不得触发模型重规划");
        }
    }

    /** 检查仅装配真实循环及外部替身，不承担生产启动。 */
    private static final class Harness implements AutoCloseable {
        /** 可并发执行外部工具和记录调用。 */
        private final ExecutorService workers = Executors.newFixedThreadPool(3);
        /** 启动失败检查会主动关闭这个定时器。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 本实例的运行图不替代外部任务记录。 */
        private final ExecutionTraceStore traces = new ExecutionTraceStore(3, 500, 4000);
        /** 被测循环。 */
        private final AgentOrchestrator orchestrator;

        private Harness(TaskRecordPort records, CapabilitySearchPort lookup, AgentOrchestrator.ActionExecutor actions,
                        AgentOrchestrator.Planner planner, int modelBudget) {
            AgentOrchestrator.Checks checks = new AgentOrchestrator.Checks() {
                @Override
                public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) {
                    return true;
                }

                @Override
                public boolean verify(String goal, Map<String, Object> args, Object output) {
                    return true;
                }
            };
            AgentOrchestrator.Limits limits = new AgentOrchestrator.Limits(6, 12, 40, 20,
                    modelBudget, 10, Duration.ofSeconds(15));
            orchestrator = new AgentOrchestrator(planner, lookup, com.example.myhelper.support.ReliableTools.bind(actions),
                    checks, traces, workers, timer,
                    limits, new TestModelAdapter(List.of("A", "B")), records,
                    new com.example.myhelper.capability.CheckedCapabilityUse(lookup, checks),
                    com.example.myhelper.support.ReliableTools.gate());
        }

        private AgentOrchestrator.Handle submit() {
            return orchestrator.submit(new AgentOrchestrator.Request("root", Map.of(), "A", List.of("write")));
        }

        @Override
        public void close() {
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }
}
