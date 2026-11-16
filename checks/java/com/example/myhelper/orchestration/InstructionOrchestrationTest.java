package com.example.myhelper.orchestration;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.records.TaskInstructionPort.Command;
import com.example.myhelper.records.TaskInstructionPort.Kind;
import com.example.myhelper.records.TaskRecordPort.EventKind;
import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.support.RecordingTaskInstructions;
import com.example.myhelper.support.RecordingTaskRecords;
import com.example.myhelper.support.TestModelAdapter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

/** 验证真实循环的新指令接线；记录替身仍不提供生产持久化或跨进程恢复保证。 */
class InstructionOrchestrationTest {
    /** 第一轮已经写出的产物；重规划必须看到该事实，不能把它当作尚未执行。 */
    private static final Map<String, Object> WRITTEN = Map.of("target", "A", "version", "V2", "receipt", "written-A");
    /** 用户明确要求保留旧修改并调整剩余目标。 */
    private static final String AMENDMENT = "A 中已经写出的文件保留，剩余结果改为写到 B";

    @Test
    void lateModelPlanIsRecordedButNeverDispatchesItsOldTool() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        RecordingTaskInstructions instructions = records.enableInstructions();
        BlockingPlanner planner = new BlockingPlanner(plan());
        CountingAction actions = new CountingAction();
        try (Harness harness = new Harness(records, planner, actions)) {
            AgentOrchestrator.Handle handle = harness.submit();
            try {
                awaitLatch(planner.entered);
                harness.orchestrator.instruct(command(handle, Kind.AMEND, AMENDMENT));
                assertFalse(handle.settled().isDone(), "模型尚在途，对外停止不能提前完成真实收尾");
            } finally {
                planner.release.countDown();
            }
            handle.settled().get(10, TimeUnit.SECONDS);
            assertNotEquals(AgentOrchestrator.Status.SUCCESS, result(handle).status());
            assertEquals(0, actions.calls.get(), "旧计划不能因为模型迟到返回而派发工具");
            assertFalse(instructions.completed(handle.taskId()));
            assertTrue(hasEvent(records, handle, EventKind.MODEL_CALL_RETURNED), "模型实际返回仍须保留");
            assertFalse(hasEvent(records, handle, EventKind.MODEL_FAILED), "用户打断不应冒充模型调用故障");
            assertNull(records.activeClaim(handle.taskId()));
        }
    }

    @Test
    void legacyCancelHandlePersistsCancellationWithoutEndingInFlightWorkEarly() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        RecordingTaskInstructions instructions = records.enableInstructions();
        BlockingPlanner planner = new BlockingPlanner(complete("旧目标完成"));
        try (Harness harness = new Harness(records, planner, new CountingAction())) {
            AgentOrchestrator.Handle handle = harness.submit();
            try {
                awaitLatch(planner.entered);
                handle.cancel().run();
                assertEquals(AgentOrchestrator.Status.CANCELLED, result(handle).status());
                assertFalse(handle.settled().isDone(), "取消结果提前返回时仍须等待模型真实结束");
                assertEquals(Kind.CANCEL, instructions.query(handle.taskId()).latestKind());
            } finally {
                planner.release.countDown();
            }
            handle.settled().get(10, TimeUnit.SECONDS);
            assertFalse(instructions.completed(handle.taskId()));
            assertTrue(hasEvent(records, handle, EventKind.MODEL_CALL_RETURNED));
            assertNull(records.activeClaim(handle.taskId()));
        }
    }

    @Test
    void replayedPauseCannotReplaceNewerCancellation() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        records.enableInstructions();
        BlockingPlanner planner = new BlockingPlanner(complete("旧目标完成"));
        try (Harness harness = new Harness(records, planner, new CountingAction())) {
            AgentOrchestrator.Handle handle = harness.submit();
            try {
                awaitLatch(planner.entered);
                Command pause = command(handle, Kind.PAUSE, "暂停");
                var pauseReceipt = harness.orchestrator.instruct(pause);
                Command cancel = new Command(handle.taskId(), "cancel-after-pause",
                        pauseReceipt.revision(), Kind.CANCEL, "取消");
                harness.orchestrator.instruct(cancel);
                assertEquals(pauseReceipt, harness.orchestrator.instruct(pause));
            } finally {
                planner.release.countDown();
            }
            assertEquals(AgentOrchestrator.Status.CANCELLED, handle.settled().get(10, TimeUnit.SECONDS).status());
            assertEquals(AgentOrchestrator.Status.CANCELLED, result(handle).status());
            assertNull(records.activeClaim(handle.taskId()));
        }
    }

    @Test
    void amendmentWaitsForActualToolReturnAndPassesNewInstructionAndOldFacts() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        RecordingTaskInstructions instructions = records.enableInstructions();
        ReplanningPlanner planner = new ReplanningPlanner();
        BlockingWrite actions = new BlockingWrite();
        try (Harness harness = new Harness(records, planner, actions)) {
            AgentOrchestrator.Handle first = harness.submit();
            CompletableFuture<AgentOrchestrator.Handle> continuation;
            try {
                awaitLatch(actions.entered);
                continuation = harness.orchestrator.amend(command(first, Kind.AMEND, AMENDMENT));
                assertFalse(continuation.isDone(), "旧工具尚未真实返回时不能领取新运行");
                assertFalse(first.settled().isDone());
                assertEquals(first.rootRunId(), records.activeClaim(first.taskId()).rootRunId());
                assertEquals(1, planner.calls.get(), "不能一边收束旧写入一边重规划同一任务");
            } finally {
                actions.release.countDown();
            }
            AgentOrchestrator.Handle second = continuation.get(10, TimeUnit.SECONDS);
            AgentOrchestrator.Outcome outcome = result(second);
            second.settled().get(10, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.SUCCESS, outcome.status(), outcome.error());
            assertTrue(first.settled().isDone());
            assertEquals(first.taskId(), second.taskId());
            assertNotEquals(first.rootRunId(), second.rootRunId());
            assertEquals(1, actions.calls.get(), "恢复应利用已有修改，不能自动重写 A");
            assertEquals(2, planner.calls.get());
            AgentOrchestrator.PlanningContext resumed = planner.resumed.get();
            assertNotNull(resumed);
            assertEquals(1, resumed.instructions().revision());
            assertEquals(AMENDMENT, resumed.instructions().instructions().getFirst().text());
            assertTrue(containsEntry(resumed.recovery().progress(), "receipt", "written-A"),
                    "新运行的模型上下文必须能查到旧工具的真实产物和回执");
            assertTrue(instructions.completed(first.taskId()));
            assertNull(records.activeClaim(first.taskId()));
            assertReturnedBeforeOldEndAndNewModel(records.events(first.taskId()), first.rootRunId(), second.rootRunId());
        }
    }

    @Test
    void externallyAcceptedInstructionPreventsLateCompleteWithoutLocalNotification() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        RecordingTaskInstructions instructions = records.enableInstructions();
        BlockingPlanner planner = new BlockingPlanner(complete("old-goal-complete"));
        try (Harness harness = new Harness(records, planner, new CountingAction())) {
            AgentOrchestrator.Handle handle = harness.submit();
            try {
                awaitLatch(planner.entered);
                // 模拟另一个入口已经持久接收指令，但本实例尚未收到唤醒消息。
                instructions.accept(command(handle, Kind.AMEND, AMENDMENT));
            } finally {
                planner.release.countDown();
            }
            handle.settled().get(10, TimeUnit.SECONDS);
            assertNotEquals(AgentOrchestrator.Status.SUCCESS, result(handle).status(),
                    "不能只依赖进程内 stop，必须重新检查外部指令版本");
            assertFalse(instructions.completed(handle.taskId()), "旧 COMPLETE 不能完成新要求");
            assertEquals(1, instructions.query(handle.taskId()).revision());
        }
    }

    @Test
    void newInstructionDuringVerificationCannotBeOverwrittenByOldCompletion() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        RecordingTaskInstructions instructions = records.enableInstructions();
        AtomicReference<String> taskId = new AtomicReference<>();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                taskId.set(context.taskId());
                return complete("旧目标已经完成");
            }
        };
        AgentOrchestrator.Checks acceptingNewInstruction = new AgentOrchestrator.Checks() {
            @Override
            public boolean allow(String goal, Map<String, Object> arguments, CapabilityLookup.Ref ref) {
                return true;
            }

            @Override
            public boolean verify(String goal, Map<String, Object> arguments, Object output) {
                // 模型已经返回，旧结果正在验收时外部接受了新版本。
                instructions.accept(new Command(taskId.get(), "during-verification", 0, Kind.AMEND, AMENDMENT));
                return true;
            }
        };
        try (Harness harness = new Harness(records, planner, new CountingAction(), acceptingNewInstruction)) {
            AgentOrchestrator.Handle handle = harness.submit();
            handle.settled().get(10, TimeUnit.SECONDS);
            assertNotEquals(AgentOrchestrator.Status.SUCCESS, result(handle).status());
            assertFalse(instructions.completed(handle.taskId()), "验收返回 true 不能越过更新的用户指令");
            assertEquals(1, instructions.query(handle.taskId()).revision());
        }
    }

    @Test
    void pausedOrCancelledTaskCannotResumeUntilAnExplicitContinue() throws Exception {
        for (Kind kind : List.of(Kind.PAUSE, Kind.CANCEL)) {
            RecordingTaskRecords records = new RecordingTaskRecords();
            records.enableInstructions();
            NeedsInputPlanner planner = new NeedsInputPlanner();
            try (Harness harness = new Harness(records, planner, new CountingAction())) {
                AgentOrchestrator.Handle first = harness.submit();
                result(first);
                first.settled().get(10, TimeUnit.SECONDS);
                harness.orchestrator.instruct(command(first, kind, "停止当前任务"));
                assertThrows(IllegalStateException.class, new Executable() {
                    @Override
                    public void execute() {
                        harness.orchestrator.resume(first.taskId());
                    }
                });
                assertNull(records.activeClaim(first.taskId()), "拒绝恢复不能泄露新领取");
                assertEquals(1, planner.calls.get());
                if (kind == Kind.PAUSE) {
                    // 用户明确继续后，才允许开启下一次运行。
                    harness.orchestrator.instruct(new Command(first.taskId(), "continue", 1, Kind.CONTINUE, "继续"));
                    AgentOrchestrator.Handle second = harness.orchestrator.resume(first.taskId());
                    assertEquals(AgentOrchestrator.Status.NEEDS_INPUT, result(second).status());
                    second.settled().get(10, TimeUnit.SECONDS);
                    assertEquals(2, planner.calls.get());
                }
            }
        }
    }

    @Test
    void failedEndRecordingPreventsAmendmentFromClaimingANewRun() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        records.enableInstructions();
        ReplanningPlanner planner = new ReplanningPlanner();
        BlockingWrite actions = new BlockingWrite();
        try (Harness harness = new Harness(records, planner, actions)) {
            AgentOrchestrator.Handle first = harness.submit();
            CompletableFuture<AgentOrchestrator.Handle> continuation;
            try {
                awaitLatch(actions.entered);
                records.failAppend(EventKind.RUN_ENDED);
                continuation = harness.orchestrator.amend(command(first, Kind.AMEND, AMENDMENT));
            } finally {
                actions.release.countDown();
            }
            assertThrows(ExecutionException.class, new Executable() {
                @Override
                public void execute() throws Exception {
                    continuation.get(10, TimeUnit.SECONDS);
                }
            });
            assertTrue(first.settled().isCompletedExceptionally());
            assertEquals(1, planner.calls.get(), "结束落盘失败时不能自动启动新模型调用");
            assertEquals(first.rootRunId(), records.activeClaim(first.taskId()).rootRunId());
            assertTrue(hasEvent(records, first, EventKind.RETURNED), "结束故障也不能抹掉实际工具返回");
        }
    }

    @Test
    void amendmentPreventsQueuedSpringToolWhileIndependentLockHolderFinishes() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        records.enableInstructions();
        LockHoldingTool callback = new LockHoldingTool();
        CountDownLatch queuedResolved = new CountDownLatch(1);
        SpringToolExecutor.ResourceResolver resolver = new SpringToolExecutor.ResourceResolver() {
            @Override
            public List<String> keys(String tool, Map<String, Object> arguments) {
                if ("queued".equals(arguments.get("target"))) {
                    queuedResolved.countDown();
                }
                return List.of("shared-file");
            }
        };
        SpringToolExecutor actions = new SpringToolExecutor(List.of(callback), resolver, 1);
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                if (!context.history().isEmpty()) {
                    return complete("工作完成");
                }
                TaskPlan.Step write = new TaskPlan.Step("write", "write", TaskPlan.Kind.TOOL, "write",
                        context.arguments(), List.of(), Map.of());
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN,
                        new TaskPlan("root", List.of(write)), null);
            }
        };
        try (Harness harness = new Harness(records, planner, actions)) {
            AgentOrchestrator.Handle holder = harness.submit("holder");
            AgentOrchestrator.Handle queued;
            try {
                awaitLatch(callback.holderEntered);
                queued = harness.submit("queued");
                awaitLatch(queuedResolved);
                harness.orchestrator.instruct(command(queued, Kind.AMEND, AMENDMENT));
                assertFalse(holder.settled().isDone(), "打断另一个 taskId 不能结束当前占锁任务");
                assertEquals(0, callback.queuedCalls.get());
            } finally {
                callback.releaseHolder.countDown();
            }
            assertEquals(AgentOrchestrator.Status.SUCCESS, result(holder).status());
            holder.settled().get(10, TimeUnit.SECONDS);
            queued.settled().get(10, TimeUnit.SECONDS);
            assertNotEquals(AgentOrchestrator.Status.SUCCESS, result(queued).status());
            assertEquals(1, callback.holderCalls.get());
            assertEquals(0, callback.queuedCalls.get(), "即使之后拿到锁，旧版本排队工具也不能开始回调");
        }
    }

    @Test
    void consecutiveAndRetriedAmendmentsShareOneResumptionAndPreserveBothInputs() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        records.enableInstructions();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicReference<AgentOrchestrator.PlanningContext> resumed = new AtomicReference<>();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                modelCalls.incrementAndGet();
                if (context.instructions().revision() == 0) {
                    return plan();
                }
                resumed.set(context);
                // 快速结束本轮且任务未完成，重复 AMEND 也不能自动制造第三次模型调用。
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.NEEDS_INPUT, null, "还需要目录 B 的确认");
            }
        };
        BlockingWrite actions = new BlockingWrite();
        try (Harness harness = new Harness(records, planner, actions)) {
            AgentOrchestrator.Handle first = harness.submit();
            Command firstCommand = command(first, Kind.AMEND, AMENDMENT);
            Command secondCommand = new Command(first.taskId(), "second-amendment", 1, Kind.AMEND, "汇总再增加一列");
            CompletableFuture<AgentOrchestrator.Handle> firstContinuation;
            CompletableFuture<AgentOrchestrator.Handle> repeatedContinuation;
            CompletableFuture<AgentOrchestrator.Handle> secondContinuation;
            try {
                awaitLatch(actions.entered);
                firstContinuation = harness.orchestrator.amend(firstCommand);
                repeatedContinuation = harness.orchestrator.amend(firstCommand);
                secondContinuation = harness.orchestrator.amend(secondCommand);
                assertFalse(firstContinuation.isDone());
                assertFalse(repeatedContinuation.isDone());
                assertFalse(secondContinuation.isDone());
            } finally {
                actions.release.countDown();
            }
            AgentOrchestrator.Handle next = firstContinuation.get(10, TimeUnit.SECONDS);
            assertEquals(next.rootRunId(), repeatedContinuation.get(10, TimeUnit.SECONDS).rootRunId());
            assertEquals(next.rootRunId(), secondContinuation.get(10, TimeUnit.SECONDS).rootRunId());
            assertEquals(AgentOrchestrator.Status.NEEDS_INPUT, result(next).status());
            next.settled().get(10, TimeUnit.SECONDS);
            AgentOrchestrator.Handle retriedAfterSettlement = harness.orchestrator.amend(firstCommand).get(10, TimeUnit.SECONDS);
            assertEquals(next.rootRunId(), retriedAfterSettlement.rootRunId());
            assertEquals(2, modelCalls.get(), "相同 commandId 重试和同批修改不产生额外恢复运行");
            assertEquals(List.of(firstCommand, secondCommand), resumed.get().instructions().instructions());
            assertEquals(2, resumed.get().instructions().revision());
            assertEquals(1, actions.calls.get());
            assertNull(records.activeClaim(first.taskId()));
        }
    }

    private static Command command(AgentOrchestrator.Handle handle, Kind kind, String text) {
        return new Command(handle.taskId(), "command-" + kind.name(), 0, kind, text);
    }

    private static AgentOrchestrator.Outcome result(AgentOrchestrator.Handle handle) throws Exception {
        return handle.result().get(10, TimeUnit.SECONDS);
    }

    private static AgentOrchestrator.Decision plan() {
        TaskPlan.Step write = new TaskPlan.Step("write", "write", TaskPlan.Kind.TOOL,
                "write", Map.of("target", "A"), List.of(), Map.of());
        return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN,
                new TaskPlan("root", List.of(write)), null);
    }

    private static AgentOrchestrator.Decision complete(String output) {
        return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, output);
    }

    private static void awaitLatch(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(10, TimeUnit.SECONDS), "预期的并发边界未在期限内到达");
    }

    private static void waitForRelease(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("检查没有释放在途动作");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("检查动作被中断", interrupted);
        }
    }

    private static boolean hasEvent(RecordingTaskRecords records, AgentOrchestrator.Handle handle, EventKind kind) {
        for (ExecutionEvent event : records.events(handle.taskId())) {
            if (event.kind() == kind && event.rootRunId().equals(handle.rootRunId())) {
                return true;
            }
        }
        return false;
    }

    private static void assertReturnedBeforeOldEndAndNewModel(List<ExecutionEvent> events,
                                                             String previousRoot, String resumedRoot) {
        int returned = -1;
        int ended = -1;
        int nextModel = -1;
        for (int index = 0; index < events.size(); index++) {
            ExecutionEvent event = events.get(index);
            if (previousRoot.equals(event.rootRunId()) && event.kind() == EventKind.RETURNED) {
                returned = index;
            }
            if (previousRoot.equals(event.rootRunId()) && event.kind() == EventKind.RUN_ENDED) {
                ended = index;
            }
            if (resumedRoot.equals(event.rootRunId()) && event.kind() == EventKind.MODEL_CALL_STARTED) {
                nextModel = index;
            }
        }
        assertTrue(returned >= 0 && ended > returned && nextModel > ended,
                "必须先保留旧动作回执并结束旧运行，再开始新模型请求");
    }

    private static boolean containsEntry(Object data, String key, Object expected) {
        if (data instanceof Map<?, ?> map) {
            if (expected.equals(map.get(key))) {
                return true;
            }
            for (Object child : map.values()) {
                if (containsEntry(child, key, expected)) {
                    return true;
                }
            }
        } else if (data instanceof List<?> list) {
            for (Object child : list) {
                if (containsEntry(child, key, expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 将模型保持在真实调用内，给新指令一个确定的到达时机。 */
    private static final class BlockingPlanner implements AgentOrchestrator.Planner {
        /** 通知检查线程模型已开始。 */
        private final CountDownLatch entered = new CountDownLatch(1);
        /** 控制旧模型真实返回。 */
        private final CountDownLatch release = new CountDownLatch(1);
        /** 旧指令下生成的决策。 */
        private final AgentOrchestrator.Decision response;

        private BlockingPlanner(AgentOrchestrator.Decision response) {
            this.response = response;
        }

        @Override
        public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
            entered.countDown();
            waitForRelease(release);
            return response;
        }
    }

    /** 旧运行提出写入；新运行依据已有结果和新增要求作出后续结论。 */
    private static final class ReplanningPlanner implements AgentOrchestrator.Planner {
        /** 只计实际进入模型的次数。 */
        private final AtomicInteger calls = new AtomicInteger();
        /** 保存新运行看到的上下文供主检查线程断言。 */
        private final AtomicReference<AgentOrchestrator.PlanningContext> resumed = new AtomicReference<>();

        @Override
        public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
            calls.incrementAndGet();
            if (context.instructions().revision() == 0) {
                return plan();
            }
            resumed.set(context);
            return complete("新版目标已经依据已有产物核对");
        }
    }

    /** 对暂停/取消检查返回一个不会自动推进或完成目标的结果。 */
    private static final class NeedsInputPlanner implements AgentOrchestrator.Planner {
        /** 检查拒绝恢复不会偷偷调用模型。 */
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
            calls.incrementAndGet();
            return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.NEEDS_INPUT, null, "请补充要求");
        }
    }

    /** 模拟不能立即取消的外部写入；打断后仍有真实回执需要保存。 */
    private static final class BlockingWrite implements AgentOrchestrator.ActionExecutor {
        /** 确认动作已进入实际执行范围。 */
        private final CountDownLatch entered = new CountDownLatch(1);
        /** 控制外部动作的真实完成时刻。 */
        private final CountDownLatch release = new CountDownLatch(1);
        /** 检查恢复没有重复写入。 */
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> arguments,
                                                 AgentOrchestrator.CallContext context) {
            calls.incrementAndGet();
            context.reportBefore(Map.of("target", "A", "version", "V1"));
            entered.countDown();
            waitForRelease(release);
            context.reportAfter(WRITTEN);
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, WRITTEN, null);
        }
    }

    /** 检查迟到计划或无工具决策没有进入业务执行。 */
    private static final class CountingAction implements AgentOrchestrator.ActionExecutor {
        /** 实际业务派发次数。 */
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> arguments,
                                                 AgentOrchestrator.CallContext context) {
            calls.incrementAndGet();
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, WRITTEN, null);
        }
    }

    /** 使用真正的资源锁路径验证排队取消，不用手写执行器替代锁行为。 */
    private static final class LockHoldingTool implements ToolCallback {
        /** 第一个独立任务已经持锁并开始回调。 */
        private final CountDownLatch holderEntered = new CountDownLatch(1);
        /** 控制独立任务真实完成，期间另一个任务只能等锁。 */
        private final CountDownLatch releaseHolder = new CountDownLatch(1);
        /** 第一个任务的实际业务回调次数。 */
        private final AtomicInteger holderCalls = new AtomicInteger();
        /** 被打断的排队任务不得进入此业务回调。 */
        private final AtomicInteger queuedCalls = new AtomicInteger();

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("write").description("检查共享文件写入")
                    .inputSchema("{\"type\":\"object\",\"properties\":{\"target\":{\"type\":\"string\"}}}")
                    .build();
        }

        @Override
        public String call(String input) {
            throw new AssertionError("检查工具必须通过带运行上下文的入口执行");
        }

        @Override
        public String call(String input, ToolContext context) {
            if (input.contains("\"holder\"")) {
                holderCalls.incrementAndGet();
                holderEntered.countDown();
                waitForRelease(releaseHolder);
                return "{\"target\":\"holder\",\"receipt\":\"holder-finished\"}";
            }
            queuedCalls.incrementAndGet();
            return "{\"target\":\"queued\"}";
        }
    }

    /** 只装配本地替身；生产源码继续使用真实循环和指令端口。 */
    private static final class Harness implements AutoCloseable {
        /** 允许工具在途时查询和接收指令。 */
        private final ExecutorService workers = Executors.newFixedThreadPool(3);
        /** 与工作线程独立的任务截止计时器。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 被测循环，记录替身从外部注入。 */
        private final AgentOrchestrator orchestrator;

        private Harness(RecordingTaskRecords records, AgentOrchestrator.Planner planner,
                        AgentOrchestrator.ActionExecutor actions) {
            this(records, planner, actions, new PassingChecks());
        }

        private Harness(RecordingTaskRecords records, AgentOrchestrator.Planner planner,
                        AgentOrchestrator.ActionExecutor actions, AgentOrchestrator.Checks checks) {
            orchestrator = new AgentOrchestrator(planner, CapabilitySearchPort.disabled(), actions, checks,
                    new ExecutionTraceStore(5, 1000, 5000), workers, timer,
                    new AgentOrchestrator.Limits(5, 8, 50, 20, 50, 20, Duration.ofSeconds(20)),
                    new TestModelAdapter(List.of("A", "B")), records);
        }

        private AgentOrchestrator.Handle submit() {
            return submit("A");
        }

        private AgentOrchestrator.Handle submit(String target) {
            return orchestrator.submit(new AgentOrchestrator.Request("root", Map.of("target", target),
                    "A", List.of("write")));
        }

        @Override
        public void close() {
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }

    /** 业务验收在这些接线场景中明确通过，版本控制仍须单独生效。 */
    private static final class PassingChecks implements AgentOrchestrator.Checks {
        @Override
        public boolean allow(String goal, Map<String, Object> arguments, CapabilityLookup.Ref ref) {
            return true;
        }

        @Override
        public boolean verify(String goal, Map<String, Object> arguments, Object output) {
            return true;
        }
    }
}
