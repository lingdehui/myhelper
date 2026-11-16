package com.example.myhelper.orchestration;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.support.RecordingTaskRecords;
import com.example.myhelper.support.TestModelAdapter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 慢记录服务不能占用共享计时器；真实收尾仍须等中断事实提交完成。 */
class TimeoutRecordingTest {
    @Test
    void timeoutReturnsAndTimerRemainsAvailableBeforeInterruptionRecordIsCommitted() throws Exception {
        BlockingInterruptionRecords records = new BlockingInterruptionRecords();
        BlockingPlanner planner = new BlockingPlanner();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        try {
            AgentOrchestrator orchestrator = new AgentOrchestrator(planner, CapabilitySearchPort.disabled(),
                    new NoTools(), new PassingChecks(), new ExecutionTraceStore(2, 100, 300), workers, timer,
                    new AgentOrchestrator.Limits(3, 4, 10, 10, 10, 10, Duration.ofSeconds(2)),
                    new TestModelAdapter(List.of("A")), records);
            AgentOrchestrator.Handle handle = orchestrator.submit(new AgentOrchestrator.Request(
                    "等待中的目标", Map.of(), "A", List.of()));
            await(planner.entered, "模型应先进入真实调用，再触发超时");
            assertFalse(records.instructions().enabled(), "本检查验证未装配新指令端口的运行时超时");

            AgentOrchestrator.Outcome visible = handle.result().get(6, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.CANCELLED, visible.status());
            assertEquals(LlmTexts.TASK_TIMEOUT, visible.error());
            assertFalse(handle.settled().isDone(), "对外超时不能提前宣布在途模型已经结束");
            assertEquals(1L, records.appendEntered.getCount(), "模型尚未收束时计时器不得同步写中断记录");

            CountDownLatch timerStillWorks = new CountDownLatch(1);
            timer.schedule(new Runnable() {
                @Override
                public void run() {
                    timerStillWorks.countDown();
                }
            }, 0, TimeUnit.MILLISECONDS);
            await(timerStillWorks, "同一计时器仍应执行其他定时动作，不能被记录写入占住");

            planner.release.countDown();
            await(records.appendEntered, "真实模型返回后，收尾应开始提交中断记录");
            assertFalse(handle.settled().isDone(), "记录服务尚未确认，真实收尾不能完成");
            assertNotNull(records.delegate.activeClaim(handle.taskId()), "中断记录落盘前不能释放运行领取");
            assertFalse(records.order.contains(TaskRecordPort.EventKind.RUN_ENDED));

            records.releaseAppend.countDown();
            handle.settled().get(6, TimeUnit.SECONDS);
            assertNull(records.delegate.activeClaim(handle.taskId()));
            int interrupted = records.order.indexOf(TaskRecordPort.EventKind.RUN_INTERRUPTION_REQUESTED);
            int ended = records.order.indexOf(TaskRecordPort.EventKind.RUN_ENDED);
            assertTrue(interrupted >= 0 && ended > interrupted, "中断事实必须先提交，再保存结束和释放领取");
            TaskRecordPort.ExecutionEvent event = records.interruption.get();
            assertNotNull(event);
            assertEquals("RUNTIME", event.details().get("source"));
            assertEquals(LlmTexts.TASK_TIMEOUT, event.details().get("reason"));
            Instant requestedAt = Instant.parse((String) event.details().get("requestedAt"));
            Instant recordedAt = Instant.parse((String) event.details().get("recordedAt"));
            assertFalse(recordedAt.isBefore(requestedAt), "保存实际停止请求时间，不能拿落盘时间冒充超时时间");
        } finally {
            // 任何断言失败也释放两个可控阻塞点，避免影响后面的检查。
            planner.release.countDown();
            records.releaseAppend.countDown();
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch, String failure) throws InterruptedException {
        assertTrue(latch.await(6, TimeUnit.SECONDS), failure);
    }

    private static void block(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("检查阻塞点未按时释放");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("检查阻塞点被中断", interrupted);
        }
    }

    /** 保持真实模型调用在途，不让超时前的正常完成掩盖记录阻塞问题。 */
    private static final class BlockingPlanner implements AgentOrchestrator.Planner {
        /** 通知检查模型已经进入。 */
        private final CountDownLatch entered = new CountDownLatch(1);
        /** 允许检查在超时响应后控制模型真实返回。 */
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
            entered.countDown();
            block(release);
            return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, "迟到完成");
        }
    }

    /** 仅中断记录模拟慢服务；其他任务和事实合同仍委托既有检查替身。 */
    private static final class BlockingInterruptionRecords implements TaskRecordPort {
        /** 未启用新指令能力的记录替身。 */
        private final RecordingTaskRecords delegate = new RecordingTaskRecords();
        /** 中断事实开始提交的观察点。 */
        private final CountDownLatch appendEntered = new CountDownLatch(1);
        /** 控制外部记录服务何时确认中断事实。 */
        private final CountDownLatch releaseAppend = new CountDownLatch(1);
        /** 已成功接受事件的顺序，不把进入 append 当作完成提交。 */
        private final List<EventKind> order = new CopyOnWriteArrayList<>();
        /** 用来核对运行时请求时间与落盘时间。 */
        private final AtomicReference<ExecutionEvent> interruption = new AtomicReference<>();

        @Override
        public boolean enabled() {
            return true;
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
            if (event.kind() == EventKind.RUN_INTERRUPTION_REQUESTED) {
                interruption.set(event);
                appendEntered.countDown();
                block(releaseAppend);
            }
            delegate.append(claim, event);
            order.add(event.kind());
        }

        @Override
        public RecoverySnapshot review(RunClaim claim, ResolutionProposal proposal) {
            return delegate.review(claim, proposal);
        }

        @Override
        public void endRun(RunClaim claim, ExecutionEvent event) {
            delegate.endRun(claim, event);
            order.add(event.kind());
        }
    }

    /** 超时检查无需业务工具；调用工具即说明执行路径异常。 */
    private static final class NoTools implements AgentOrchestrator.ActionExecutor {
        @Override
        public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> arguments,
                                                 AgentOrchestrator.CallContext context) {
            throw new AssertionError("本检查不应派发工具");
        }
    }

    /** 验收允许正常完成，旧超时决策仍必须被循环拦截。 */
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
