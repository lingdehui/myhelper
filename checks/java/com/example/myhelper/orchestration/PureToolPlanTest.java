package com.example.myhelper.orchestration;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.gate.RuntimeGate;
import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.llm.ModelAdapter;
import com.example.myhelper.support.TestModelAdapter;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

/** 纯工具依赖图可以交叉；模型分支归属不能额外限制它的执行。 */
class PureToolPlanTest {
    /** 交叉依赖图：b0 同时被 b1、c1 使用，c1 还需要 c0。 */
    private static final Map<String, List<String>> DEPENDENCIES = Map.of(
            "b0", List.of(), "c0", List.of(), "b1", List.of("b0"),
            "c1", List.of("b0", "c0"), "j", List.of("b1", "c1"));

    @Test
    void pureToolsKeepOwnerAndIgnoreModelRouting() {
        // 纯工具不使用模型 routing；故意提供不覆盖当前步骤的模型归属。
        TaskPlan original = crossedPlan(TaskPlan.Kind.TOOL);
        PlanBranches.Routing unusedRouting = new PlanBranches.Routing(Map.of("other", "unused"), List.of());
        TaskPlan plan = new TaskPlan(original.goal(), original.steps(), unusedRouting);
        ModelAdapter.Binding owner = new ModelAdapter.Binding("branch-b", "branch-a", "B",
                ModelAdapter.Assignment.OTHER_MODEL);
        RunModelAccess models = new RunModelAccess(null,
                new ModelAdapter.RunRequest("run", "branch-a", "A", List.of("echo")));
        try (PlanModelBindings bindings = new PlanModelBindings(plan, owner, models, false)) {
            for (TaskPlan.Step step : plan.steps()) {
                assertEquals("branch-b", bindings.branchId(step.id()));
                assertEquals("branch-a", bindings.parentBranchId(step.id()));
                assertSame(owner, bindings.ready(step.id()));
            }
            // 跳过模型归属分析仍然保留计划步骤身份检查。
            assertThrows(IllegalArgumentException.class, new Executable() {
                @Override
                public void execute() {
                    bindings.ready("missing");
                }
            });
        }
        models.close();
    }

    @Test
    void plansThatStillNeedModelsKeepTheRoutingValidation() {
        TaskPlan plan = crossedPlan(TaskPlan.Kind.TASK);
        RunModelAccess models = new RunModelAccess(null,
                new ModelAdapter.RunRequest("run", "branch-a", "A", List.of("echo")));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new PlanModelBindings(plan, models.rootContext(), models, true);
            }
        });
        assertEquals(LlmTexts.AMBIGUOUS_ROUTING, failure.getMessage());
        models.close();
    }

    @Test
    void crossedPureToolCapabilityRunsWithoutAModelAdapter() throws Exception {
        TaskPlan plan = crossedPlan(TaskPlan.Kind.TOOL);
        CrossedActions actions = new CrossedActions();
        FixedCapabilities lookup = new FixedCapabilities(plan, MatchMode.ENTRY);
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                throw new AssertionError("入口纯工具能力不得调用模型");
            }
        };
        // 显式提供可靠统计；没有 ModelAdapter 也必须完成整份工具 DAG。
        try (Harness harness = new Harness(planner, lookup, actions, null)) {
            AgentOrchestrator.Handle handle = harness.runSuccessfully();
            assertEquals(DEPENDENCIES.keySet(), actions.completed);
            assertEquals(0, lookup.afterPlanCalls.get());
            assertExecutionGraph(harness.traces.snapshot(handle.rootRunId()));
        }
    }

    @Test
    void tasksReplacedAfterPlanningRunAsPureTools() throws Exception {
        TaskPlan original = crossedPlan(TaskPlan.Kind.TASK);
        CrossedActions actions = new CrossedActions();
        FixedCapabilities lookup = new FixedCapabilities(original, MatchMode.STEPS);
        TestModelAdapter adapter = new TestModelAdapter(List.of("A", "B", "C"));
        AtomicInteger modelCalls = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                // 只允许根的规划与收尾；被固化替换的五个 TASK 都不再求解。
                assertEquals("root", context.goal());
                assertEquals("A", context.modelRef());
                modelCalls.incrementAndGet();
                if (context.history().isEmpty()) {
                    return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN, original, null);
                }
                assertEquals(AgentOrchestrator.Status.SUCCESS, context.history().getLast().outcome().status());
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, "finished");
            }
        };
        try (Harness harness = new Harness(planner, lookup, actions, adapter)) {
            AgentOrchestrator.Handle handle = harness.runSuccessfully();
            assertEquals(DEPENDENCIES.keySet(), actions.completed);
            assertEquals(1, lookup.afterPlanCalls.get());
            assertEquals(2, modelCalls.get());
            assertEquals(1, adapter.openCalls());
            assertExecutionGraph(harness.traces.snapshot(handle.rootRunId()));
        }
    }

    @Test
    void wholePureToolMatchUsesItsOwnGraphInsteadOfOriginalModelRouting() throws Exception {
        TaskPlan original = crossedPlan(TaskPlan.Kind.TASK);
        CrossedActions actions = new CrossedActions();
        FixedCapabilities lookup = new FixedCapabilities(crossedPlan(TaskPlan.Kind.TOOL), MatchMode.WHOLE);
        TestModelAdapter adapter = new TestModelAdapter(List.of("A", "B", "C"));
        AtomicInteger modelCalls = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                assertEquals("root", context.goal());
                modelCalls.incrementAndGet();
                if (context.history().isEmpty()) {
                    return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN, original, null);
                }
                assertEquals(AgentOrchestrator.Status.SUCCESS, context.history().getLast().outcome().status());
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, "finished");
            }
        };
        try (Harness harness = new Harness(planner, lookup, actions, adapter)) {
            AgentOrchestrator.Handle handle = harness.runSuccessfully();
            assertEquals(DEPENDENCIES.keySet(), actions.completed);
            assertEquals(1, lookup.afterPlanCalls.get());
            assertEquals(2, modelCalls.get());
            assertExecutionGraph(harness.traces.snapshot(handle.rootRunId()));
        }
    }

    private static TaskPlan crossedPlan(TaskPlan.Kind kind) {
        // 模型分支分析无法自动确定 c1 的归属，但所有执行依赖都已明确。
        return new TaskPlan("crossed", List.of(step("b0", kind), step("c0", kind),
                step("b1", kind), step("c1", kind), step("j", kind)));
    }

    private static TaskPlan.Step step(String id, TaskPlan.Kind kind) {
        Map<String, TaskPlan.InputBinding> inputs = new LinkedHashMap<>();
        // 固化组合会保留 apply 步骤的结果包装；直接工具则直接返回 id。
        String pointer = kind == TaskPlan.Kind.TOOL ? "/id" : "/apply/value/id";
        for (String dependency : DEPENDENCIES.get(id)) {
            inputs.put("from_" + dependency, new TaskPlan.InputBinding(dependency, pointer));
        }
        String tool = kind == TaskPlan.Kind.TOOL ? "echo" : null;
        return new TaskPlan.Step(id, id, kind, tool, Map.of("id", id), DEPENDENCIES.get(id), inputs);
    }

    private static void assertExecutionGraph(ExecutionTraceStore.Snapshot snapshot) {
        Map<String, String> ids = new LinkedHashMap<>();
        Set<String> owners = new HashSet<>();
        for (ExecutionTraceStore.Node node : snapshot.nodes().values()) {
            if (node.kind() == ExecutionTraceStore.NodeKind.STEP) {
                String stepId = (String) node.input().get("stepId");
                if (DEPENDENCIES.containsKey(stepId)) {
                    ids.put(stepId, node.id());
                    owners.add((String) node.input().get("branchId"));
                    assertEquals(ExecutionTraceStore.NodeStatus.SUCCESS, node.status());
                }
            }
        }
        assertEquals(DEPENDENCIES.keySet(), ids.keySet());
        assertEquals(1, owners.size());
        // 归属沿用 owner 不删除交叉依赖，读取图的其他模块仍能看到真实来源。
        assertTrue(snapshot.edges().contains(new ExecutionTraceStore.Edge(ids.get("b0"), ids.get("c1"),
                ExecutionTraceStore.EdgeKind.DEPENDS_ON, AgentOrchestrator.Status.SUCCESS.name())));
        int dataEdges = 0;
        int dependencyEdges = 0;
        for (ExecutionTraceStore.Edge edge : snapshot.edges()) {
            if (edge.kind() == ExecutionTraceStore.EdgeKind.DATA_FROM) {
                dataEdges++;
            }
            if (edge.kind() == ExecutionTraceStore.EdgeKind.DEPENDS_ON) {
                dependencyEdges++;
            }
        }
        assertEquals(5, dataEdges);
        assertEquals(5, dependencyEdges);
        assertTrue(snapshot.closed());
    }

    private enum MatchMode {
        ENTRY, STEPS, WHOLE
    }

    /** 只在检查中提供确定的固化匹配，不实现生产候选检索或资格策略。 */
    private static final class FixedCapabilities implements CapabilitySearchPort {
        /** 入口命中、规划后逐项替换或规划后整条替换。 */
        private final MatchMode mode;
        /** 检查内唯一已选能力。 */
        private final CapabilityLookup.Definition definition;
        /** 确认实际 PLAN 后仍经过检索。 */
        private final AtomicInteger afterPlanCalls = new AtomicInteger();

        private FixedCapabilities(TaskPlan plan, MatchMode mode) {
            this.mode = mode;
            if (mode == MatchMode.STEPS) {
                TaskPlan.Step apply = new TaskPlan.Step("apply", "apply", TaskPlan.Kind.TOOL, "echo",
                        Map.of(), List.of(), Map.of("request", new TaskPlan.InputBinding(TaskPlan.TASK_INPUT_SOURCE, "")));
                plan = new TaskPlan("tool-replacement", List.of(apply));
            }
            definition = new CapabilityLookup.Definition(new CapabilityLookup.Ref("checks", "fixed", 1), plan);
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public CapabilityLookup.Match searchAtEntry(CapabilityLookup.Query query) {
            if (mode != MatchMode.ENTRY) {
                return new CapabilityLookup.Match(List.of(), Optional.empty());
            }
            return new CapabilityLookup.Match(List.of(), Optional.of(definition));
        }

        @Override
        public CapabilityLookup.PlanMatches searchAfterPlanning(String runId, long revision, TaskPlan plan,
                                                               Map<String, Object> input) {
            assertNotEquals(MatchMode.ENTRY, mode);
            afterPlanCalls.incrementAndGet();
            Map<String, CapabilityLookup.Match> steps = new LinkedHashMap<>();
            CapabilityLookup.Match selected = new CapabilityLookup.Match(List.of(), Optional.of(definition));
            CapabilityLookup.Match empty = new CapabilityLookup.Match(List.of(), Optional.empty());
            for (TaskPlan.Step step : plan.steps()) {
                steps.put(step.id(), mode == MatchMode.STEPS ? selected : empty);
            }
            return new CapabilityLookup.PlanMatches(mode == MatchMode.WHOLE ? selected : empty, steps);
        }

        @Override
        public boolean isAvailable(CapabilityLookup.Ref ref) {
            return definition.ref().equals(ref);
        }
    }

    /** 用真实并发等待和实际入参验证依赖未被改成串行或丢失。 */
    private static final class CrossedActions implements AgentOrchestrator.ActionExecutor {
        /** 两个根工具必须都已启动，才能继续检查后续节点。 */
        private final CountDownLatch rootsStarted = new CountDownLatch(2);
        /** 跨工作线程检查前驱执行结果。 */
        private final Set<String> completed = ConcurrentHashMap.newKeySet();

        @Override
        public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args,
                                                 AgentOrchestrator.CallContext context) {
            assertEquals("echo", tool);
            Map<?, ?> request = args.containsKey("request") ? (Map<?, ?>) args.get("request") : args;
            String id = (String) request.get("id");
            for (String dependency : DEPENDENCIES.get(id)) {
                assertTrue(completed.contains(dependency), id + " must wait for " + dependency);
                assertEquals(dependency, request.get("from_" + dependency));
            }
            if (DEPENDENCIES.get(id).isEmpty()) {
                rootsStarted.countDown();
                try {
                    assertTrue(rootsStarted.await(5, TimeUnit.SECONDS), "独立根工具应并发启动");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            assertTrue(completed.add(id), "工具不可重复执行: " + id);
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, Map.of("id", id), null);
        }
    }

    /** 仅检查组件接线；真实应用入口和门禁仍由各模块提供。 */
    private static final class Harness implements AutoCloseable {
        /** 允许两个根工具同时执行。 */
        private final ExecutorService workers = Executors.newFixedThreadPool(4);
        /** 截止时间使用独立线程。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 验证实际步骤和交叉数据边仍完整保存。 */
        private final ExecutionTraceStore traces = new ExecutionTraceStore(2, 500, 4000);
        /** 当前检查的被测组件。 */
        private final AgentOrchestrator orchestrator;

        private Harness(AgentOrchestrator.Planner planner, CapabilitySearchPort lookup,
                        AgentOrchestrator.ActionExecutor actions, ModelAdapter adapter) {
            RuntimeGate checks = new RuntimeGate() {
                @Override
                public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) {
                    return true;
                }

                @Override
                public boolean verify(String goal, Map<String, Object> args, Object output) {
                    return true;
                }
            };
            AgentOrchestrator.Limits limits = new AgentOrchestrator.Limits(6, 12, 40, 20, 100, 10, Duration.ofSeconds(15));
            orchestrator = new AgentOrchestrator(planner, lookup, com.example.myhelper.support.ReliableTools.bind(actions),
                    checks, traces, workers, timer, limits,
                    adapter, com.example.myhelper.records.TaskRecordPort.disabled(),
                    new com.example.myhelper.capability.CheckedCapabilityUse(lookup, checks),
                    com.example.myhelper.support.ReliableTools.gate());
        }

        private AgentOrchestrator.Handle runSuccessfully() throws Exception {
            AgentOrchestrator.Handle handle = orchestrator.submit(new AgentOrchestrator.Request("root", Map.of(), "A", List.of("echo")));
            AgentOrchestrator.Outcome outcome = handle.result().get(10, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.SUCCESS, outcome.status(), outcome.error());
            return handle;
        }

        @Override
        public void close() {
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }
}
