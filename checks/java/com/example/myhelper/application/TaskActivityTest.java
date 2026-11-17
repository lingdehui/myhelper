package com.example.myhelper.application;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.capability.CheckedCapabilityUse;
import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.orchestration.ExecutionTraceStore;
import com.example.myhelper.orchestration.ToolReliabilityGate;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.support.RecordingTaskRecords;
import com.example.myhelper.support.TestModelAdapter;
import com.example.myhelper.tasklog.TaskLogPort;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskActivityTest {
    @Test
    void taskOccupationsInvalidateTheGenerationAndAnOldCallKeepsItsInFlightSlot() {
        var activity = new TaskActivity();
        long original = activity.snapshot().generation();
        try (var idleCall = activity.tryStartIdleCall(original)) {
            assertNotNull(idleCall);
            assertNull(activity.tryStartIdleCall(original));
            var first = activity.occupy();
            try (first; var second = activity.occupy()) {
                assertFalse(activity.currentIdle(original));
                first.close();
                assertTrue(activity.snapshot().busy());
                assertNull(activity.tryStartIdleCall(activity.snapshot().generation()));
            }
            assertFalse(activity.snapshot().busy());
            assertFalse(activity.currentIdle(original));
            assertNull(activity.tryStartIdleCall(activity.snapshot().generation()));
        }
        try (var next = activity.tryStartIdleCall(activity.snapshot().generation())) {
            assertNotNull(next);
        }
    }

    @Test
    void rootSubmissionAndRecoveryHoldActivityThroughEarlyCancellationAndActualReturn() throws Exception {
        try (var runtime = new Runtime()) {
            var task = runtime.records.create(new TaskRecordPort.TaskSpec("restore", Map.of(), "local", List.of()));
            var first = runtime.orchestrator.submit(request("submit"));
            var second = runtime.orchestrator.resume(task.taskId());
            assertTrue(runtime.first.entered.await(3, TimeUnit.SECONDS));
            assertTrue(runtime.second.entered.await(3, TimeUnit.SECONDS));
            assertTrue(runtime.activity.snapshot().busy());

            first.cancel().run();
            assertEquals(AgentOrchestrator.Status.CANCELLED, first.result().get(3, TimeUnit.SECONDS).status());
            assertFalse(first.settled().isDone());
            assertNull(runtime.activity.tryStartIdleCall(runtime.activity.snapshot().generation()));
            runtime.first.release.countDown();
            assertEquals(AgentOrchestrator.Status.CANCELLED, first.settled().get(3, TimeUnit.SECONDS).status());
            assertTrue(runtime.activity.snapshot().busy(), "并发恢复未收尾仍占用");

            runtime.second.release.countDown();
            assertEquals(AgentOrchestrator.Status.SUCCESS, second.settled().get(3, TimeUnit.SECONDS).status());
            assertFalse(runtime.activity.snapshot().busy());
            try (var idleCall = runtime.activity.tryStartIdleCall(runtime.activity.snapshot().generation())) {
                assertNotNull(idleCall);
            }
        }
    }

    @Test
    void rejectedRegistrationOrStartupCannotLeakAnOccupation() {
        try (var runtime = new Runtime()) {
            runtime.observer.rejectCreate = true;
            long previous = runtime.activity.snapshot().generation();
            assertThrows(IllegalStateException.class, new RunnableAssertion(runtime, false));
            assertFalse(runtime.activity.snapshot().busy());
            assertFalse(runtime.activity.currentIdle(previous));

            previous = runtime.activity.snapshot().generation();
            assertThrows(IllegalArgumentException.class, new RunnableAssertion(runtime, true));
            assertFalse(runtime.activity.snapshot().busy());
            assertFalse(runtime.activity.currentIdle(previous));

            runtime.observer.rejectCreate = false;
            runtime.timer.shutdownNow();
            assertThrows(RejectedExecutionException.class, new RunnableAssertion(runtime, false));
            assertFalse(runtime.activity.snapshot().busy());
        }
    }

    private static AgentOrchestrator.Request request(String goal) {
        return new AgentOrchestrator.Request(goal, Map.of(), "local", List.of());
    }

    private record RunnableAssertion(
            /** 被检查的真实编排和本地活动共享运行时。 */
            Runtime runtime,
            /** true 时检查不存在的恢复任务。 */
            boolean resume) implements org.junit.jupiter.api.function.Executable {
        @Override
        public void execute() {
            if (resume) {
                runtime.orchestrator.resume("unknown task");
            } else {
                runtime.orchestrator.submit(request("submit"));
            }
        }
    }

    private static final class Runtime implements AutoCloseable {
        /** 真实受理与空闲规划的仲裁。 */
        private final TaskActivity activity = new TaskActivity();
        /** 第一项新任务的同步模型替身。 */
        private final BlockedDecision first = new BlockedDecision();
        /** 第二项恢复任务的同步模型替身。 */
        private final BlockedDecision second = new BlockedDecision();
        /** 已有检查仓储负责实际登记和恢复数据。 */
        private final RecordingTaskRecords records = new RecordingTaskRecords();
        /** 记录操作发生时即时检查占用已经登记。 */
        private final RecordObserver observer = new RecordObserver(activity, records);
        /** 两条根运行可并发进入模型。 */
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        /** 错误路径可通过关闭此计时器触发。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 使用生产根运行生命周期。 */
        private final AgentOrchestrator orchestrator;

        private Runtime() {
            AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
                @Override
                public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                    var gate = "submit".equals(context.goal()) ? first : second;
                    return gate.waitForRelease();
                }
            };
            AgentOrchestrator.ActionExecutor actions = new AgentOrchestrator.ActionExecutor() {
                @Override
                public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args,
                                                         AgentOrchestrator.CallContext context) {
                    throw new AssertionError("检查只执行模型答复");
                }
            };
            var gate = new AgentOrchestrator.Checks() {
                @Override
                public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) {
                    return false;
                }

                @Override
                public boolean verify(String goal, Map<String, Object> args, Object value) {
                    return true;
                }
            };
            var search = CapabilitySearchPort.disabled();
            orchestrator = new AgentOrchestrator(planner, search, actions, gate,
                    new ExecutionTraceStore(8, 100, 500), workers, timer,
                    new AgentOrchestrator.Limits(3, 3, 8, 8, 8, 8, Duration.ofSeconds(15)),
                    new TestModelAdapter(List.of("local")), observer.port(), new CheckedCapabilityUse(search, gate),
                    ToolReliabilityGate.unavailable(), TaskLogPort.disabled(), activity);
        }

        @Override
        public void close() {
            first.release.countDown();
            second.release.countDown();
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }

    private static final class BlockedDecision {
        /** 确认在途模型已经实际开始。 */
        private final CountDownLatch entered = new CountDownLatch(1);
        /** 将真实模型回包与提前的用户结果分开。 */
        private final CountDownLatch release = new CountDownLatch(1);

        private AgentOrchestrator.Decision waitForRelease() {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
            return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, "完成");
        }
    }

    private static final class RecordObserver implements InvocationHandler {
        /** 记录入口中直接核对活动版本，无需依赖线程时机。 */
        private final TaskActivity activity;
        /** 其余操作交给已有记录替身。 */
        private final RecordingTaskRecords records;
        /** 验证受理失败后回收占用。 */
        private boolean rejectCreate;

        private RecordObserver(TaskActivity activity, RecordingTaskRecords records) {
            this.activity = activity;
            this.records = records;
        }

        private TaskRecordPort port() {
            return (TaskRecordPort) Proxy.newProxyInstance(TaskRecordPort.class.getClassLoader(),
                    new Class<?>[]{TaskRecordPort.class}, this);
        }

        @Override
        public Object invoke(Object target, Method method, Object[] args) throws Throwable {
            assertTrue(activity.snapshot().busy(), "记录操作前须已登记占用：" + method.getName());
            if (rejectCreate && "create".equals(method.getName())) {
                throw new IllegalStateException("模拟登记失败");
            }
            try {
                return method.invoke(records, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }
    }
}
