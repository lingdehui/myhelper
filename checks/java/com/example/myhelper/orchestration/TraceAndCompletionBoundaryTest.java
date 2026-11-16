package com.example.myhelper.orchestration;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.records.ExecutionTracePort;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.records.TaskRecordPort.Disposition;
import com.example.myhelper.records.TaskRecordPort.EventKind;
import com.example.myhelper.records.TaskRecordPort.RecoveryItem;
import com.example.myhelper.records.TaskRecordPort.RecoverySnapshot;
import com.example.myhelper.support.RecordingTaskRecords;
import com.example.myhelper.support.TestModelAdapter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 检查执行后的链路故障和完成期间新增事项，不依赖真实模型或持久仓储。 */
class TraceAndCompletionBoundaryTest {
    /** 已发生的真实工具产物，链路故障不能把它删除或当成未执行。 */
    private static final Map<String, Object> WRITTEN = Map.of("version", "V2", "receipt", "write-once");

    @Test
    void failedToolTracePreservesActualOutputAndDoesNotReplan() throws Exception {
        FailingToolFinish traces = new FailingToolFinish();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                assertEquals(1, modelCalls.incrementAndGet(), "链路写入失败不能让已执行动作自动重新规划");
                TaskPlan.Step write = new TaskPlan.Step("write", "write", TaskPlan.Kind.TOOL, "write",
                        Map.of("target", "sample.txt"), List.of(), Map.of());
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN,
                        new TaskPlan("root", List.of(write)), null);
            }
        };
        AgentOrchestrator.ActionExecutor actions = new AgentOrchestrator.ActionExecutor() {
            @Override
            public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> arguments,
                                                     AgentOrchestrator.CallContext context) {
                assertEquals("write", tool);
                assertEquals(1, toolCalls.incrementAndGet(), "真实写入只能执行一次");
                return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, WRITTEN, null);
            }
        };
        try (Harness harness = new Harness(planner, actions, traces, TaskRecordPort.disabled())) {
            AgentOrchestrator.Handle handle = harness.submit();
            AgentOrchestrator.Outcome outcome = handle.result().get(10, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.UNKNOWN, outcome.status(), outcome.error());
            assertEquals(1, traces.failures.get());
            assertEquals(1, toolCalls.get());
            assertEquals(1, modelCalls.get());
            Map<?, ?> steps = assertInstanceOf(Map.class, outcome.value());
            Map<?, ?> write = assertInstanceOf(Map.class, steps.get("write"));
            assertEquals("UNKNOWN", write.get("status"));
            assertEquals(WRITTEN, write.get("value"), "已执行的工具产物须保留在汇总结果中");
            assertTrue(String.valueOf(write.get("error")).contains("trace-write-failed"));
            assertTrue(traces.snapshot(handle.rootRunId()).closed());
        }
    }

    @Test
    void unconfirmedToolFactsAreArchivedAsGapsWithoutWakingConsolidation() throws Exception {
        for (EventKind missing : List.of(EventKind.RETURNED, EventKind.VERIFIED)) {
            assertUnconfirmedFactIsArchivedAsGap(missing);
        }
    }

    private static void assertUnconfirmedFactIsArchivedAsGap(EventKind missing) throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        records.failAppend(missing);
        AtomicInteger modelCalls = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                assertEquals(1, modelCalls.incrementAndGet());
                TaskPlan.Step write = new TaskPlan.Step("write", "write", TaskPlan.Kind.TOOL, "write",
                        Map.of("target", "sample.txt"), List.of(), Map.of());
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN,
                        new TaskPlan("root", List.of(write)), null);
            }
        };
        AgentOrchestrator.ActionExecutor actions = new AgentOrchestrator.ActionExecutor() {
            @Override
            public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> arguments,
                                                     AgentOrchestrator.CallContext context) {
                return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, WRITTEN, null);
            }
        };
        try (Harness harness = new Harness(planner, actions, new ExecutionTraceStore(2, 20, 70), records)) {
            AgentOrchestrator.Handle handle = harness.submit();
            AgentOrchestrator.Outcome outcome = handle.settled().get(10, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.UNKNOWN, outcome.status(), outcome.error());
            Map<?, ?> steps = assertInstanceOf(Map.class, outcome.value());
            Map<?, ?> write = assertInstanceOf(Map.class, steps.get("write"));
            assertEquals(WRITTEN, write.get("value"));
            TaskRecordPort.RunFinalization finalization = records.finalization(handle.rootRunId());
            assertNotNull(finalization);
            assertTrue(finalization.graph().closed());
            assertFalse(finalization.graphComplete());
            assertEquals(1, finalization.gaps().size());
            assertTrue(finalization.gaps().getFirst().contains(missing.name()));
            assertFalse(records.consolidationWoken(handle.rootRunId()));
        }
    }

    @Test
    void planningRoundLimitKeepsTheSuccessfulPlanOutput() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                assertEquals(1, modelCalls.incrementAndGet(), "maxRounds=1 不能继续第二轮模型请求");
                TaskPlan.Step write = new TaskPlan.Step("write", "write", TaskPlan.Kind.TOOL, "write",
                        Map.of("target", "sample.txt"), List.of(), Map.of());
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN,
                        new TaskPlan("root", List.of(write)), null);
            }
        };
        AgentOrchestrator.ActionExecutor actions = new AgentOrchestrator.ActionExecutor() {
            @Override
            public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> arguments,
                                                     AgentOrchestrator.CallContext context) {
                assertEquals("write", tool);
                assertEquals(1, toolCalls.incrementAndGet());
                return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, WRITTEN, null);
            }
        };
        ExecutionTraceStore traces = new ExecutionTraceStore(2, 200, 1000);
        try (Harness harness = new Harness(planner, actions, traces, TaskRecordPort.disabled(), 1)) {
            AgentOrchestrator.Handle handle = harness.submit();
            AgentOrchestrator.Outcome outcome = handle.result().get(10, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.FAILED, outcome.status());
            assertEquals(LlmTexts.PLANNING_ROUND_LIMIT_REACHED, outcome.error());
            assertEquals(1, modelCalls.get());
            assertEquals(1, toolCalls.get());
            Map<?, ?> steps = assertInstanceOf(Map.class, outcome.value());
            Map<?, ?> write = assertInstanceOf(Map.class, steps.get("write"));
            assertEquals("SUCCESS", write.get("status"), "预算耗尽不能改写已经验收成功的工具结果");
            assertEquals(WRITTEN, write.get("value"), "最后一轮实际产物必须随预算失败返回");
            assertTrue(traces.snapshot(handle.rootRunId()).closed());
        }
    }

    @Test
    void normalCompleteRechecksIncidentsAddedDuringTheModelCall() throws Exception {
        // 两种新结论都需要读取新快照；不能因为调用前是 NORMAL 就直接宣布成功。
        for (Disposition disposition : List.of(Disposition.WAIT, Disposition.CONSULT_MODEL)) {
            assertNewIncidentPreventsCompletion(disposition);
        }
    }

    private static void assertNewIncidentPreventsCompletion(Disposition disposition) throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        AtomicInteger modelCalls = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                int call = modelCalls.incrementAndGet();
                if (call == 1) {
                    assertEquals(AgentOrchestrator.PlanningPhase.NORMAL, context.phase());
                    assertTrue(context.recovery().pending().isEmpty());
                    // 确定性模拟模型尚未返回期间，独立记录模块已经接受新的影响事项。
                    RecoveryItem incident = new RecoveryItem("late-incident", "OPEN", "另一个动作的影响仍需核对",
                            Map.of("receipt", "late-receipt", "version", "V3"));
                    records.setRecovery(context.taskId(), new RecoverySnapshot(context.recovery().revision() + 1,
                            disposition, List.of(incident), Map.of("knownVersion", "V3"), "新事项尚未处理"));
                    return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null,
                            "这是基于旧快照给出的完成结论");
                }
                assertEquals(2, call, "未获外部批准时不能反复调用模型忙等");
                assertEquals(Disposition.CONSULT_MODEL, disposition);
                assertEquals(AgentOrchestrator.PlanningPhase.RECOVERY, context.phase());
                assertEquals("late-incident", context.recovery().pending().getFirst().id());
                assertEquals("V3", context.recovery().progress().get("knownVersion"));
                // 此提案仍须交外部审核；记录替身默认不批准。
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null,
                        Map.of("incidentId", "late-incident", "proposal", "请核对已观察到的影响"));
            }
        };
        AgentOrchestrator.ActionExecutor noTools = new AgentOrchestrator.ActionExecutor() {
            @Override
            public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> arguments,
                                                     AgentOrchestrator.CallContext context) {
                throw new AssertionError("本检查只提交完成结论，不应执行工具");
            }
        };
        ExecutionTraceStore traces = new ExecutionTraceStore(2, 200, 1000);
        try (Harness harness = new Harness(planner, noTools, traces, records)) {
            AgentOrchestrator.Handle handle = harness.submit();
            AgentOrchestrator.Outcome outcome = handle.result().get(10, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.WAITING, outcome.status(), disposition + ": " + outcome.error());
            assertEquals(disposition == Disposition.CONSULT_MODEL ? 2 : 1, modelCalls.get());
            assertEquals(disposition == Disposition.CONSULT_MODEL ? 1 : 0, records.reviews(handle.taskId()).size());
            assertEquals("late-incident", records.recovery(handle.taskId()).pending().getFirst().id());
            assertEquals(Disposition.WAIT, records.recovery(handle.taskId()).disposition());
            assertNull(records.activeClaim(handle.taskId()));
            assertTrue(traces.snapshot(handle.rootRunId()).closed());
        }
    }

    /** 只在 TOOL 完成写入时注入一次故障，其他图操作保持真实内存实现的行为。 */
    private static final class FailingToolFinish implements ExecutionTracePort {
        /** 底层执行图用于定位节点类型以及验证真实结束。 */
        private final ExecutionTraceStore delegate = new ExecutionTraceStore(2, 200, 1000);
        /** 原子控制第一次工具完成写入失败，不受回调线程切换影响。 */
        private final AtomicBoolean injected = new AtomicBoolean();
        /** 确认测试确实经过了故障注入点。 */
        private final AtomicInteger failures = new AtomicInteger();

        @Override
        public void open(String rootRunId) {
            delegate.open(rootRunId);
        }

        @Override
        public void add(String rootRunId, ExecutionTraceStore.Node node) {
            delegate.add(rootRunId, node);
        }

        @Override
        public void edge(String rootRunId, ExecutionTraceStore.Edge edge) {
            delegate.edge(rootRunId, edge);
        }

        @Override
        public void finish(String rootRunId, String nodeId, ExecutionTraceStore.NodeStatus status,
                           Object output, String error) {
            ExecutionTraceStore.Node node = delegate.snapshot(rootRunId).nodes().get(nodeId);
            if (node.kind() == ExecutionTraceStore.NodeKind.TOOL && injected.compareAndSet(false, true)) {
                Map<?, ?> observed = assertInstanceOf(Map.class, output);
                assertEquals(WRITTEN, observed.get("value"), "故障必须发生在真实工具结果已经返回之后");
                failures.incrementAndGet();
                throw new IllegalStateException("trace-write-failed");
            }
            delegate.finish(rootRunId, nodeId, status, output, error);
        }

        @Override
        public void close(String rootRunId) {
            delegate.close(rootRunId);
        }

        @Override
        public ExecutionTraceStore.Snapshot snapshot(String rootRunId) {
            return delegate.snapshot(rootRunId);
        }
    }

    /** 只为这两项接线检查提供调度资源和明确通过的业务验收。 */
    private static final class Harness implements AutoCloseable {
        /** 真实异步执行器，不使用当前线程模拟完整调度。 */
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        /** 独立的总超时计时器。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 被测生产循环。 */
        private final AgentOrchestrator orchestrator;

        private Harness(AgentOrchestrator.Planner planner, AgentOrchestrator.ActionExecutor actions,
                        ExecutionTracePort traces, TaskRecordPort records) {
            this(planner, actions, traces, records, 5);
        }

        private Harness(AgentOrchestrator.Planner planner, AgentOrchestrator.ActionExecutor actions,
                        ExecutionTracePort traces, TaskRecordPort records, int maxRounds) {
            AgentOrchestrator.Checks checks = new AgentOrchestrator.Checks() {
                @Override
                public boolean allow(String goal, Map<String, Object> arguments, CapabilityLookup.Ref ref) {
                    return true;
                }

                @Override
                public boolean verify(String goal, Map<String, Object> arguments, Object output) {
                    return true;
                }
            };
            AgentOrchestrator.Limits limits = new AgentOrchestrator.Limits(maxRounds, 5, 10, 10, 10, 10,
                    Duration.ofSeconds(15));
            orchestrator = new AgentOrchestrator(planner, CapabilitySearchPort.disabled(), actions, checks,
                    traces, workers, timer, limits, new TestModelAdapter(List.of("A")), records);
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
