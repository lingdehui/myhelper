package com.example.myhelper.orchestration;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.capability.CheckedCapabilityUse;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.skill.ToolReliability;
import com.example.myhelper.support.TestModelAdapter;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import com.example.myhelper.world.model.ExecutionStatistics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证评分拒绝发生在实际动作前，且回退后保留真实执行进度。 */
class ToolReliabilityOrchestrationTest {
    /** 检查中的业务门禁与验收。 */
    private static final AgentOrchestrator.Checks CHECKS = new AgentOrchestrator.Checks() {
        @Override public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) { return true; }
        @Override public boolean verify(String goal, Map<String, Object> args, Object output) { return true; }
    };

    @Test
    void aboveThresholdExecutesAtEntryWithoutOpeningModelSession() throws Exception {
        FixedSearch search = new FixedSearch(plan("cached"));
        Scores scores = new Scores(6);
        Actions actions = new Actions();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                throw new AssertionError("可靠的入口固化应直接完成");
            }
        };
        try (Harness h = new Harness(search, scores, planner, actions, false)) {
            assertEquals(AgentOrchestrator.Status.SUCCESS, h.run().status());
            assertEquals("cached", actions.arguments.getFirst().get("stage"));
            assertEquals(1, scores.arguments.size());
            assertEquals(0, h.models.openCalls());
            assertEquals(0, search.afterPlanCalls.get());
        }
    }

    @Test
    void lowAndEqualScoresFallBackWithoutRepeatedAutomaticReuse() throws Exception {
        for (long success : List.of(4L, 5L)) {
            FixedSearch search = new FixedSearch(plan("cached"));
            Scores scores = new Scores(success);
            Actions actions = new Actions();
            AtomicInteger calls = new AtomicInteger();
            AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
                @Override public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                    if (calls.incrementAndGet() == 1) {
                        assertEquals(AgentOrchestrator.Status.FAILED, context.history().getLast().outcome().status());
                        assertTrue(actions.arguments.isEmpty());
                        return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN, plan("model"), null);
                    }
                    assertEquals(2, calls.get());
                    assertEquals(AgentOrchestrator.Status.SUCCESS, context.history().getLast().outcome().status());
                    return complete();
                }
            };
            try (Harness h = new Harness(search, scores, planner, actions, false)) {
                assertEquals(AgentOrchestrator.Status.SUCCESS, h.run().status());
                assertEquals(2, calls.get());
                assertEquals(1, search.afterPlanCalls.get());
                assertEquals(1, scores.arguments.size());
                assertEquals(1, actions.arguments.size());
                assertEquals("model", actions.arguments.getFirst().get("stage"));
            }
        }
    }

    @Test
    void planningReceivesApprovedHistoryForTheResolvedIdentityEvenWhenCurrentArgumentsHaveNoScore() throws Exception {
        FixedSearch search = new FixedSearch(plan("cached"));
        Scores scores = new Scores(0);
        scores.mode = 1;
        scores.planningSample = new ToolReliability.Sample(new ExecutionStatistics(9, 1, List.of()), 0);
        Actions actions = new Actions();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                assertEquals(Map.of("echo", List.of(new ToolReliability.PlanningReference("approved", 0.9, 9, false))),
                        context.toolReliability());
                assertTrue(actions.arguments.isEmpty());
                return complete();
            }
        };
        try (Harness h = new Harness(search, scores, planner, actions, false)) {
            assertEquals(AgentOrchestrator.Status.SUCCESS, h.run().status());
            assertEquals(List.of(new ToolRef("echo", "check-tools", "1")), scores.planningRefs);
            assertEquals(List.of(Map.of("stage", "cached")), scores.arguments);
        }
    }

    @Test
    void unknownQueryFailureAndOldConstructorAllReachModelBeforeAnyTool() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            FixedSearch search = new FixedSearch(plan("cached"));
            Scores scores = new Scores(6);
            scores.mode = mode == 0 ? 1 : 2;
            Actions actions = new Actions();
            AtomicInteger calls = new AtomicInteger();
            AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
                @Override public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                    calls.incrementAndGet();
                    assertTrue(actions.arguments.isEmpty());
                    Map<?, ?> output = (Map<?, ?>) context.history().getLast().outcome().value();
                    Map<?, ?> step = (Map<?, ?>) output.get("apply");
                    assertEquals(false, ((Map<?, ?>) step.get("value")).get("executed"));
                    return complete();
                }
            };
            try (Harness h = new Harness(search, scores, planner, actions, mode == 2)) {
                assertEquals(AgentOrchestrator.Status.SUCCESS, h.run().status());
                assertEquals(1, calls.get());
                assertTrue(actions.arguments.isEmpty());
                if (mode == 2) { assertTrue(scores.arguments.isEmpty()); }
            }
        }
    }

    @Test
    void partialResultAndFullyBoundArgumentsSurviveFallbackIncludingOfflineModel() throws Exception {
        for (boolean offline : List.of(false, true)) {
            TaskPlan.Step first = new TaskPlan.Step("first", "生成产物", TaskPlan.Kind.TOOL, "echo",
                    Map.of("stage", "first"), List.of(),
                    Map.of("requested", new TaskPlan.InputBinding(TaskPlan.TASK_INPUT_SOURCE, "/requested")));
            TaskPlan.Step second = new TaskPlan.Step("second", "处理产物", TaskPlan.Kind.TOOL, "echo",
                    Map.of("stage", "second"), List.of("first"),
                    Map.of("previous", new TaskPlan.InputBinding("first", "/id")));
            FixedSearch search = new FixedSearch(new TaskPlan("partial", List.of(first, second)));
            Scores scores = new Scores(6);
            scores.rejectSecond = true;
            Actions actions = new Actions();
            AtomicInteger calls = new AtomicInteger();
            AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
                @Override public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                    calls.incrementAndGet();
                    Map<?, ?> steps = (Map<?, ?>) context.history().getLast().outcome().value();
                    assertPartialResult(steps);
                    Map<?, ?> secondOutcome = (Map<?, ?>) steps.get("second");
                    assertEquals(false, ((Map<?, ?>) secondOutcome.get("value")).get("executed"));
                    assertEquals(1, actions.arguments.size());
                    if (offline) { throw new IllegalStateException("model offline"); }
                    return complete();
                }
            };
            try (Harness h = new Harness(search, scores, planner, actions, false)) {
                AgentOrchestrator.Outcome outcome = h.run();
                assertEquals(offline ? AgentOrchestrator.Status.FAILED : AgentOrchestrator.Status.SUCCESS, outcome.status());
                if (offline) { assertNotNull(outcome.value(), "模型不可用仍保留此前结果"); }
                assertEquals(1, calls.get());
                assertEquals(1, actions.arguments.size());
                assertEquals(7, actions.arguments.getFirst().get("requested"));
                assertEquals("created", scores.arguments.get(1).get("previous"));
            }
        }
    }

    private static void assertPartialResult(Map<?, ?> steps) {
        Map<?, ?> first = (Map<?, ?>) steps.get("first");
        Map<?, ?> value = (Map<?, ?>) first.get("value");
        assertEquals("created", value.get("id"));
        assertEquals(1, value.get("version"));
    }

    private static TaskPlan plan(String stage) {
        return new TaskPlan(stage, List.of(new TaskPlan.Step("apply", stage, TaskPlan.Kind.TOOL, "echo",
                Map.of("stage", stage), List.of(), Map.of())));
    }

    private static AgentOrchestrator.Decision complete() {
        return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, "done");
    }

    private static final class FixedSearch implements CapabilitySearchPort {
        /** 每次都返回同一版本，验证拒绝后不会反复接管新模型计划。 */
        private final CapabilityLookup.Definition definition;
        /** 实际规划后检索次数。 */
        private final AtomicInteger afterPlanCalls = new AtomicInteger();
        private FixedSearch(TaskPlan plan) {
            definition = new CapabilityLookup.Definition(new CapabilityLookup.Ref("test", "fixed", 1), plan);
        }
        @Override public boolean enabled() { return true; }
        private CapabilityLookup.Match match() { return new CapabilityLookup.Match(List.of(), Optional.of(definition)); }
        @Override public CapabilityLookup.Match searchAtEntry(CapabilityLookup.Query query) { return match(); }
        @Override public CapabilityLookup.PlanMatches searchAfterPlanning(String run, long revision, TaskPlan plan, Map<String, Object> input) {
            afterPlanCalls.incrementAndGet();
            Map<String, CapabilityLookup.Match> steps = new LinkedHashMap<>();
            for (TaskPlan.Step step : plan.steps()) { steps.put(step.id(), match()); }
            return new CapabilityLookup.PlanMatches(match(), Map.copyOf(steps));
        }
        @Override public boolean isAvailable(CapabilityLookup.Ref ref) { return definition.ref().equals(ref); }
    }

    private static final class Scores implements ToolReliability.Lookup {
        /** 成功次数由真实技能模块公式计算分数。 */
        private final long successes;
        /** 第二步使用临界值，前一步保持可靠。 */
        private boolean rejectSecond;
        /** 0 返回统计，1 无适用统计，2 查询失败。 */
        private int mode;
        /** 记录最终参数，验证查询发生在依赖绑定之后。 */
        private final List<Map<String, Object>> arguments = new ArrayList<>();
        /** 记录规划查分所用的执行绑定。 */
        private final List<ToolRef> planningRefs = new ArrayList<>();
        /** 与本次工具参数无关的已批准范围统计。 */
        private ToolReliability.Sample planningSample;
        private Scores(long successes) { this.successes = successes; }
        @Override public Optional<ToolReliability.Sample> findInVerifiedRange(
                ToolRef ref, Map<String, Object> args) {
            return findInVerifiedRange(ref.toolName(), args);
        }
        @Override public synchronized Map<String, ToolReliability.Sample> planningSamples(ToolRef ref) {
            planningRefs.add(ref);
            return planningSample == null ? Map.of() : Map.of("approved", planningSample);
        }
        @Override public synchronized Optional<ToolReliability.Sample> findInVerifiedRange(String tool, Map<String, Object> args) {
            arguments.add(args);
            if (mode == 1) { return Optional.empty(); }
            if (mode == 2) { throw new IllegalStateException("query failed"); }
            long count = rejectSecond && "second".equals(args.get("stage")) ? 5 : successes;
            return Optional.of(new ToolReliability.Sample(new ExecutionStatistics(count, 100 - count, List.of()), 0));
        }
    }

    private static final class Actions implements AgentOrchestrator.ActionExecutor {
        /** 只有实际派发动作才进入此列表。 */
        private final List<Map<String, Object>> arguments = new ArrayList<>();
        @Override public synchronized AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
            arguments.add(args);
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, Map.of("id", "created", "version", 1), null);
        }
    }

    private static final class Harness implements AutoCloseable {
        /** 工作线程。 */
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        /** 截止时间线程。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 观察是否实际开启模型会话。 */
        private final TestModelAdapter models = new TestModelAdapter(List.of("A"));
        /** 真实编排器。 */
        private final AgentOrchestrator orchestrator;
        private Harness(FixedSearch search, Scores scores, AgentOrchestrator.Planner planner, Actions actions, boolean legacy) {
            ExecutionTraceStore traces = new ExecutionTraceStore(10, 1000, 10000);
            AgentOrchestrator.Limits limits = new AgentOrchestrator.Limits(5, 5, 20, 20, 30, 30, Duration.ofSeconds(10));
            if (legacy) {
                orchestrator = new AgentOrchestrator(planner, search, actions, CHECKS, traces, workers, timer, limits, models);
            } else {
                orchestrator = new AgentOrchestrator(planner, search, com.example.myhelper.support.ReliableTools.bind(actions),
                        CHECKS, traces, workers, timer, limits,
                        models, TaskRecordPort.disabled(), new CheckedCapabilityUse(search, CHECKS),
                        new ToolReliabilityGate(new ToolReliability(scores), 0.05));
            }
        }
        private AgentOrchestrator.Outcome run() throws Exception {
            AgentOrchestrator.Handle handle = orchestrator.submit(new AgentOrchestrator.Request("root", Map.of("requested", 7), "A", List.of("echo")));
            AgentOrchestrator.Outcome outcome = handle.result().get(5, TimeUnit.SECONDS);
            handle.settled().get(5, TimeUnit.SECONDS);
            return outcome;
        }
        @Override public void close() { workers.shutdownNow(); timer.shutdownNow(); }
    }
}
