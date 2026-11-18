package com.example.myhelper.orchestration;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.capability.CapabilityUsePort;
import com.example.myhelper.capability.CheckedCapabilityUse;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.skill.ToolReliability;
import com.example.myhelper.support.ReliableTools;
import com.example.myhelper.support.TestModelAdapter;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import com.example.myhelper.world.model.ExecutionStatistics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 候选分数和合同由查询端口给出；这里检查真实循环何时切链、何时必须先回模型或核对。 */
class CapabilityAlternativesTest {
    /** 该检查只观察调度顺序，业务授权和验收另由门禁检查覆盖。 */
    private static final AgentOrchestrator.Checks CHECKS = new AgentOrchestrator.Checks() {
        @Override
        public boolean allow(String goal, Map<String, Object> arguments, CapabilityLookup.Ref ref) {
            return true;
        }
        @Override
        public boolean verify(String goal, Map<String, Object> arguments, Object output) {
            return true;
        }
    };

    @Test
    void selectedEntryChainRevokedBeforeDispatchFallsThroughWithoutCallingModel() throws Exception {
        Alternatives search = Alternatives.atEntry(singleChain("A"), singleChain("B"));
        Actions actions = new Actions(null, null);
        RevokingUse use = new RevokingUse(search);
        try (Harness runtime = new Harness(search, new NoPlanning(), actions, use, false)) {
            assertEquals(AgentOrchestrator.Status.SUCCESS, runtime.run().status());
            assertEquals(List.of("B"), actions.stages());
            assertEquals(List.of("A", "B"), use.checked);
            assertEquals(0, runtime.models.openCalls());
            assertEquals(1, search.entryCalls.get(), "切换候选不应重复入口向量检索");
        }
    }

    @Test
    void actualFailureReturnsToPlannerAndNextPlanExcludesSameVersionForSameInput() throws Exception {
        Alternatives search = Alternatives.atEntry(singleChain("A"), singleChain("B"));
        search.wholeEnabled = true;
        Actions actions = new Actions("A", null);
        AtomicInteger plans = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                int call = plans.incrementAndGet();
                if (call == 1) {
                    assertEquals(List.of("A"), actions.stages(),
                            "A 已实际失败，B 必须等待模型重新规划，而非当场自动重做");
                    assertEquals(AgentOrchestrator.Status.FAILED, context.history().getLast().outcome().status());
                    return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN,
                            singleChain("original").plan(), null);
                }
                assertEquals(2, call);
                assertEquals(List.of("A", "B"), actions.stages());
                return complete();
            }
        };
        try (Harness runtime = new Harness(search, planner, actions, null, false)) {
            assertEquals(AgentOrchestrator.Status.SUCCESS, runtime.run().status());
            assertEquals(2, plans.get());
            assertEquals(List.of("A", "B"), actions.stages());
            assertEquals(1, search.afterPlanCalls.get());
            assertTrue(search.sawExcludedA(), "下一计划的选择必须带上失败固定版本");
        }
    }

    @Test
    void unknownEffectDoesNotTryAnotherChainOrBlindlyRestartPlanning() throws Exception {
        Alternatives search = Alternatives.atEntry(singleChain("A"), singleChain("B"));
        Actions actions = new Actions(null, "A");
        try (Harness runtime = new Harness(search, new NoPlanning(), actions, null, false)) {
            assertEquals(AgentOrchestrator.Status.UNKNOWN, runtime.run().status());
            assertEquals(List.of("A"), actions.stages());
            assertEquals(0, runtime.models.openCalls());
            assertEquals(0, search.afterPlanCalls.get());
        }
    }

    @Test
    void successfulPrefixPreventsWholeChainSwitchWhenLaterToolIsNotDispatched() throws Exception {
        TaskPlan.Step first = tool("first", "first", Map.of("stage", "A1"), List.of(), Map.of());
        TaskPlan.Step second = tool("second", "second", Map.of("stage", "A2"), List.of("first"), Map.of());
        CapabilityLookup.Definition partial = definition("A", new TaskPlan("root", List.of(first, second)));
        Alternatives search = Alternatives.atEntry(partial, singleChain("B"));
        Actions actions = new Actions(null, null);
        AtomicInteger calls = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                calls.incrementAndGet();
                assertEquals(List.of("A1"), actions.stages(),
                        "已有成功产物时不可因为第二步未派发就重做整条 B");
                Map<?, ?> output = (Map<?, ?>) context.history().getLast().outcome().value();
                Map<?, ?> saved = (Map<?, ?>) output.get("first");
                assertEquals("saved", ((Map<?, ?>) saved.get("value")).get("artifact"));
                Map<?, ?> rejected = (Map<?, ?>) output.get("second");
                assertEquals(false, ((Map<?, ?>) rejected.get("value")).get("executed"));
                return complete();
            }
        };
        try (Harness runtime = new Harness(search, planner, actions, null, true)) {
            assertEquals(AgentOrchestrator.Status.SUCCESS, runtime.run().status());
            assertEquals(1, calls.get());
            assertEquals(List.of("A1"), actions.stages());
        }
    }

    @Test
    void dependentToolSelectsChainOnlyAfterItsRealArgumentsAreBound() throws Exception {
        CapabilityLookup.Definition a = boundChain("A");
        CapabilityLookup.Definition b = boundChain("B");
        Alternatives search = new Alternatives(List.of(a, b), false);
        search.boundStepOnly = true;
        Actions actions = new Actions(null, null);
        AtomicInteger calls = new AtomicInteger();
        TaskPlan.Step prepare = tool("prepare", "prepare", Map.of("stage", "prepare"), List.of(), Map.of());
        TaskPlan.Step target = tool("target", "target", Map.of("stage", "original"), List.of("prepare"),
                Map.of("requested", new TaskPlan.InputBinding("prepare", "/value")));
        TaskPlan plan = new TaskPlan("root", List.of(prepare, target));
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                if (calls.incrementAndGet() == 1) {
                    return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN, plan, null);
                }
                assertEquals(List.of("prepare", "B"), actions.stages());
                return complete();
            }
        };
        try (Harness runtime = new Harness(search, planner, actions, null, false)) {
            assertEquals(AgentOrchestrator.Status.SUCCESS, runtime.run().status());
            assertEquals(List.of("prepare", "B"), actions.stages());
            assertEquals(7, actions.arguments.getLast().get("requested"));
            boolean checkedBound = false;
            for (CapabilityLookup.Query query : search.selectionQueries) {
                if ("target".equals(query.stepId()) && Integer.valueOf(7).equals(query.arguments().get("requested"))) {
                    assertNotNull(query.step());
                    assertTrue(query.step().bindings().isEmpty(), "实际参数查询不能仍冒充未绑定步骤");
                    assertEquals(query.arguments(), query.step().arguments());
                    checkedBound = true;
                }
            }
            assertTrue(checkedBound, "派发前必须用前驱实际输出重新选择适用链");
            assertEquals(1, search.afterPlanCalls.get(), "实际参数选择复用当前候选，不新增向量查询");
        }
    }


    @Test
    void pureToolReplacementDisappearingDoesNotStartUnroutedOriginalTask() throws Exception {
        Alternatives search = new Alternatives(List.of(singleChain("A")), false);
        search.initialToolReplacementForTask = true;
        Actions actions = new Actions(null, null);
        AtomicInteger calls = new AtomicInteger();
        TaskPlan plan = new TaskPlan("root", List.of(new TaskPlan.Step("target", "target",
                TaskPlan.Kind.TASK, null, Map.of("requested", 7), List.of(), Map.of())));
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                assertEquals("root", context.goal(), "纯工具布局不能悄悄启动原始 target 子任务模型");
                int call = calls.incrementAndGet();
                if (call == 1) {
                    return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN, plan, null);
                }
                assertEquals(2, call);
                assertTrue(actions.arguments.isEmpty());
                Map<?, ?> steps = (Map<?, ?>) context.history().getLast().outcome().value();
                Map<?, ?> rejected = (Map<?, ?>) steps.get("target");
                assertEquals(com.example.myhelper.llm.LlmTexts.CAPABILITY_ROUTING_REPLAN, rejected.get("error"));
                assertEquals(false, ((Map<?, ?>) rejected.get("value")).get("executed"));
                return complete();
            }
        };
        try (Harness runtime = new Harness(search, planner, actions, null, false)) {
            assertEquals(AgentOrchestrator.Status.SUCCESS, runtime.run().status());
            assertEquals(2, calls.get(), "只有父任务初始规划和接收未执行反馈后的规划");
            assertTrue(actions.arguments.isEmpty());
        }
    }


    @Test
    void rejectedToolPermissionCanTryNextChainBeforeAnyActualAction() throws Exception {
        for (boolean atDispatch : List.of(false, true)) {
            Alternatives search = Alternatives.atEntry(singleChain("A"), singleChain("B"));
            Actions actions = new Actions(null, null);
            ToolGate checks = new ToolGate(atDispatch);
            try (Harness runtime = new Harness(search, new NoPlanning(), actions, null, false, checks)) {
                assertEquals(AgentOrchestrator.Status.SUCCESS, runtime.run().status());
                assertEquals(List.of("B"), actions.stages(), "A 被可信工具门禁拒绝，没有实际动作可重做");
                assertEquals(List.of("A", "B"), checks.allowed);
                assertEquals(atDispatch ? List.of("A", "B") : List.of("B"), checks.dispatches);
                assertEquals(0, runtime.models.openCalls(), "确定未执行时可直接采用下一个已批准候选");
                assertEquals(1, search.entryCalls.get());
            }
        }
    }

    private static CapabilityLookup.Definition singleChain(String id) {
        return definition(id, new TaskPlan("root", List.of(
                tool("apply", "apply", Map.of("stage", id), List.of(), Map.of()))));
    }

    private static CapabilityLookup.Definition boundChain(String id) {
        return definition(id, new TaskPlan("target", List.of(
                tool("apply", "apply", Map.of("stage", id), List.of(),
                        Map.of("requested", new TaskPlan.InputBinding(TaskPlan.TASK_INPUT_SOURCE, "/requested"))))));
    }

    private static CapabilityLookup.Definition definition(String id, TaskPlan plan) {
        return new CapabilityLookup.Definition(new CapabilityLookup.Ref("alternatives", id, 1), plan);
    }

    private static TaskPlan.Step tool(String id, String goal, Map<String, Object> arguments,
                                     List<String> dependencies, Map<String, TaskPlan.InputBinding> bindings) {
        return new TaskPlan.Step(id, goal, TaskPlan.Kind.TOOL, "echo", arguments, dependencies, bindings);
    }

    private static AgentOrchestrator.Decision complete() {
        return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, "done");
    }

    private static CapabilityLookup.Match emptyMatch() {
        return new CapabilityLookup.Match(List.of(), Optional.empty());
    }

    private static final class NoPlanning implements AgentOrchestrator.Planner {
        @Override
        public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
            throw new AssertionError("本场景不应请求模型");
        }
    }

    /** 替身只提供已排序候选和选择合同，不实现真实链分数或数据库查询。 */
    private static final class Alternatives implements CapabilitySearchPort {
        /** 已经排好顺序的候选定义。 */
        private final List<CapabilityLookup.Definition> definitions;
        /** 是否根入口即可匹配。 */
        private final boolean entryEnabled;
        /** 规划后是否匹配整个计划。 */
        private boolean wholeEnabled;
        /** 只在依赖绑定后为 target 选择 B，用于验证派发参数。 */
        private boolean boundStepOnly;
        /** 检索时用纯工具替代 TASK，实际选择时模拟候选失效。 */
        private boolean initialToolReplacementForTask;
        /** 模拟选择之后的权威停用。 */
        private final Set<CapabilityLookup.Ref> unavailable = ConcurrentHashMap.newKeySet();
        /** 根入口语义检索次数。 */
        private final AtomicInteger entryCalls = new AtomicInteger();
        /** 实际 PLAN 后检索次数。 */
        private final AtomicInteger afterPlanCalls = new AtomicInteger();
        /** 观察循环传入的实际选择上下文。 */
        private final List<CapabilityLookup.Query> selectionQueries = new CopyOnWriteArrayList<>();
        /** 观察按固定版本排除的历史，复制后不受运行中集合变化影响。 */
        private final List<Set<CapabilityLookup.Ref>> exclusions = new CopyOnWriteArrayList<>();

        private Alternatives(List<CapabilityLookup.Definition> definitions, boolean entryEnabled) {
            this.definitions = List.copyOf(definitions);
            this.entryEnabled = entryEnabled;
        }

        private static Alternatives atEntry(CapabilityLookup.Definition first, CapabilityLookup.Definition second) {
            return new Alternatives(List.of(first, second), true);
        }

        private CapabilityLookup.Match candidates(boolean preselect) {
            List<CapabilityLookup.Candidate> values = new ArrayList<>();
            for (CapabilityLookup.Definition definition : definitions) {
                values.add(new CapabilityLookup.Candidate(definition.ref(), definition.ref().id(), Map.of()));
            }
            return new CapabilityLookup.Match(List.copyOf(values),
                    preselect ? Optional.of(definitions.getFirst()) : Optional.empty());
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public CapabilityLookup.Match searchAtEntry(CapabilityLookup.Query query) {
            entryCalls.incrementAndGet();
            return entryEnabled ? candidates(true) : emptyMatch();
        }

        @Override
        public CapabilityLookup.PlanMatches searchAfterPlanning(String runId, long revision, TaskPlan plan,
                                                                Map<String, Object> input) {
            afterPlanCalls.incrementAndGet();
            Map<String, CapabilityLookup.Match> steps = new LinkedHashMap<>();
            for (TaskPlan.Step step : plan.steps()) {
                if (initialToolReplacementForTask && "target".equals(step.id())) {
                    steps.put(step.id(), candidates(true));
                } else {
                    steps.put(step.id(), boundStepOnly && "target".equals(step.id()) ? candidates(false) : emptyMatch());
                }
            }
            return new CapabilityLookup.PlanMatches(wholeEnabled ? candidates(true) : emptyMatch(), Map.copyOf(steps));
        }

        @Override
        public CapabilityLookup.Match select(CapabilityLookup.Query query, CapabilityLookup.Match match,
                                             Set<CapabilityLookup.Ref> excluded) {
            selectionQueries.add(query);
            exclusions.add(Set.copyOf(excluded));
            if (match.candidates().isEmpty() || initialToolReplacementForTask) {
                return emptyMatch();
            }
            for (CapabilityLookup.Definition definition : definitions) {
                if (excluded.contains(definition.ref()) || !isAvailable(definition.ref())) {
                    continue;
                }
                if (boundStepOnly && (!"target".equals(query.stepId())
                        || !Integer.valueOf(7).equals(query.arguments().get("requested"))
                        || !"B".equals(definition.ref().id()))) {
                    continue;
                }
                return new CapabilityLookup.Match(match.candidates(), Optional.of(definition));
            }
            return new CapabilityLookup.Match(match.candidates(), Optional.empty());
        }

        @Override
        public boolean isAvailable(CapabilityLookup.Ref ref) {
            return !unavailable.contains(ref);
        }

        private boolean sawExcludedA() {
            CapabilityLookup.Ref a = definitions.getFirst().ref();
            for (Set<CapabilityLookup.Ref> refs : exclusions) {
                if (refs.contains(a)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class RevokingUse implements CapabilityUsePort {
        /** 候选的权威可用状态。 */
        private final Alternatives search;
        /** 实际复核过的版本，验证 A 未派发后才转向 B。 */
        private final List<String> checked = new CopyOnWriteArrayList<>();
        private RevokingUse(Alternatives search) {
            this.search = search;
        }
        @Override
        public UseDecision evaluate(UseRequest request) {
            checked.add(request.ref().id());
            if ("A".equals(request.ref().id())) {
                search.unavailable.add(request.ref());
                return new UseDecision(false, "revoked-before-dispatch");
            }
            return new UseDecision(true, null);
        }
    }


    /** 分别在普通许可和紧邻派发处拒绝 A，禁止以工具自报字段替代真实门禁状态。 */
    private static final class ToolGate implements AgentOrchestrator.Checks {
        /** true 在派发门禁拒绝，false 在初次工具许可拒绝。 */
        private final boolean atDispatch;
        /** 进入初次工具许可的实际参数顺序。 */
        private final List<String> allowed = new CopyOnWriteArrayList<>();
        /** 进入紧邻派发许可的实际参数顺序。 */
        private final List<String> dispatches = new CopyOnWriteArrayList<>();

        private ToolGate(boolean atDispatch) {
            this.atDispatch = atDispatch;
        }

        @Override
        public boolean allow(String goal, Map<String, Object> arguments, CapabilityLookup.Ref ref) {
            return true;
        }

        @Override
        public boolean verify(String goal, Map<String, Object> arguments, Object output) {
            return true;
        }

        @Override
        public boolean allowTool(com.example.myhelper.gate.RuntimeGate.ToolCall call) {
            String stage = String.valueOf(call.args().get("stage"));
            allowed.add(stage);
            return atDispatch || !"A".equals(stage);
        }

        @Override
        public boolean beforeToolDispatch(com.example.myhelper.gate.RuntimeGate.ToolCall call) {
            String stage = String.valueOf(call.args().get("stage"));
            dispatches.add(stage);
            return !atDispatch || !"A".equals(stage);
        }
    }

    private static final class Actions implements AgentOrchestrator.ActionExecutor {
        /** 此阶段模拟已实际返回明确失败。 */
        private final String failed;
        /** 此阶段模拟实际效果未知。 */
        private final String unknown;
        /** 每项对应一个已经进入工具执行器的调用。 */
        private final List<Map<String, Object>> arguments = new CopyOnWriteArrayList<>();
        private Actions(String failed, String unknown) {
            this.failed = failed;
            this.unknown = unknown;
        }
        @Override
        public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args,
                                                 AgentOrchestrator.CallContext context) {
            arguments.add(args);
            String stage = String.valueOf(args.get("stage"));
            if (stage.equals(failed)) {
                return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.FAILED,
                        Map.of("attempted", stage, "executed", false), "confirmed-method-failure");
            }
            if (stage.equals(unknown)) {
                return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.UNKNOWN,
                        Map.of("attempted", stage), "unknown-effect");
            }
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS,
                    Map.of("value", 7, "artifact", "saved"), null);
        }

        private List<String> stages() {
            List<String> stages = new ArrayList<>();
            for (Map<String, Object> args : arguments) {
                stages.add(String.valueOf(args.get("stage")));
            }
            return List.copyOf(stages);
        }
    }

    private static final class Samples implements ToolReliability.Lookup {
        /** 只拒绝部分成功场景的第二步，其他工具均有充分已验收样本。 */
        private final boolean rejectSecond;
        private Samples(boolean rejectSecond) {
            this.rejectSecond = rejectSecond;
        }
        @Override
        public Optional<ToolReliability.Sample> findInVerifiedRange(ToolRef ref, Map<String, Object> args) {
            return findInVerifiedRange(ref.toolName(), args);
        }
        @Override
        public Optional<ToolReliability.Sample> findInVerifiedRange(String tool, Map<String, Object> args) {
            long successes = rejectSecond && "A2".equals(args.get("stage")) ? 0 : 10;
            return Optional.of(new ToolReliability.Sample(new ExecutionStatistics(successes, 0, List.of()), 0));
        }
    }

    private static final class Harness implements AutoCloseable {
        /** 单工作线程也必须能接续父计划与子能力，不能阻塞等自身任务。 */
        private final ExecutorService workers = Executors.newSingleThreadExecutor();
        /** 截止时间单独调度。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 验证入口直执是否省去模型会话。 */
        private final TestModelAdapter models = new TestModelAdapter(List.of("A"));
        /** 使用实际编排器而非复制调度实现。 */
        private final AgentOrchestrator orchestrator;

        private Harness(Alternatives search, AgentOrchestrator.Planner planner, Actions actions,
                        CapabilityUsePort use, boolean rejectSecond) {
            this(search, planner, actions, use, rejectSecond, CHECKS);
        }

        private Harness(Alternatives search, AgentOrchestrator.Planner planner, Actions actions,
                        CapabilityUsePort use, boolean rejectSecond, AgentOrchestrator.Checks checks) {
            CapabilityUsePort effectiveUse = use == null ? new CheckedCapabilityUse(search, checks) : use;
            orchestrator = new AgentOrchestrator(planner, search, ReliableTools.bind(actions), checks,
                    new ExecutionTraceStore(10, 1000, 10000), workers, timer,
                    new AgentOrchestrator.Limits(5, 5, 30, 30, 60, 60, Duration.ofSeconds(10)), models,
                    TaskRecordPort.disabled(), effectiveUse,
                    new ToolReliabilityGate(new ToolReliability(new Samples(rejectSecond)), 0.05));
        }

        private AgentOrchestrator.Outcome run() throws Exception {
            AgentOrchestrator.Handle handle = orchestrator.submit(
                    new AgentOrchestrator.Request("root", Map.of("requested", 7), "A", List.of("echo")));
            AgentOrchestrator.Outcome result = handle.result().get(5, TimeUnit.SECONDS);
            handle.settled().get(5, TimeUnit.SECONDS);
            return result;
        }

        @Override
        public void close() {
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }
}
