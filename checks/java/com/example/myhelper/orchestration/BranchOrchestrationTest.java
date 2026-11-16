package com.example.myhelper.orchestration;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.llm.ConfiguredModelAdapter;
import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.llm.ModelAdapter;
import com.example.myhelper.llm.ModelRoutingProvider;
import com.example.myhelper.support.TestModelAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import static org.junit.jupiter.api.Assertions.*;

/** 只在 checks 中验证分支模型路由；这里的替身不作为生产装配。 */
class BranchOrchestrationTest {
    @Test
    void routesEachBranchUsingItsRemainingTaskGoalsAndAllowedTools() throws Exception {
        RecordingPlanner planner = new RecordingPlanner() {
            @Override
            AgentOrchestrator.Decision next(AgentOrchestrator.PlanningContext context) {
                if (!context.goal().equals("root") || !context.history().isEmpty()) {
                    return complete(context.goal());
                }
                TaskPlan plan = new TaskPlan("routed-fork", List.of(
                        task("left", "prepare report", List.of()),
                        task("left-next", "render report", List.of("left")),
                        task("replaced", "prepare reference", List.of()),
                        task("right", "look up reference", List.of("replaced"))),
                        new PlanBranches.Routing(Map.of("left", "report", "left-next", "report",
                                "replaced", "lookup", "right", "lookup"),
                                List.of(new PlanBranches.Fork("fork", PlanBranches.ROOT_BRANCH,
                                        List.of("report", "lookup"), null))));
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN, plan, null);
            }
        };
        List<String> tools = List.of("search");
        List<ModelAdapter.BranchRequest> requests = new CopyOnWriteArrayList<>();
        ModelRoutingProvider provider = new ModelRoutingProvider() {
            @Override
            public Route route(ModelAdapter.BranchRequest branch) {
                requests.add(branch);
                if (branch.tasks().getFirst().goal().equals("prepare report")) {
                    return new Route(List.of("report"), List.of("C"));
                }
                return new Route(List.of("lookup"), List.of("B"));
            }
        };
        ChatModel unusedClient = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new AssertionError("RecordingPlanner handles model decisions in this check");
            }
        };
        ModelAdapter adapter = new ConfiguredModelAdapter(List.of(
                new ConfiguredModelAdapter.ModelConfig("A", unusedClient, true, true),
                new ConfiguredModelAdapter.ModelConfig("B", unusedClient, true, true),
                new ConfiguredModelAdapter.ModelConfig("C", unusedClient, true, true)), provider);
        CapabilityLookup.Definition replacement = new CapabilityLookup.Definition(
                new CapabilityLookup.Ref("checks", "prepare-reference", 1),
                new TaskPlan("prepare-reference", List.of(new TaskPlan.Step("search", "search reference",
                        TaskPlan.Kind.TOOL, "search", Map.of(), List.of(), Map.of()))));
        AtomicInteger toolCalls = new AtomicInteger();
        AgentOrchestrator.ActionExecutor actions = new AgentOrchestrator.ActionExecutor() {
            @Override
            public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args,
                                                     AgentOrchestrator.CallContext context) {
                assertEquals("search", tool);
                toolCalls.incrementAndGet();
                return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, "reference", null);
            }
        };

        try (Harness harness = new Harness(planner, replaceAfterPlanning("replaced", replacement),
                actions, adapter, tools, 1)) {
            harness.runSuccessfully();
            assertEquals(1, toolCalls.get());
            assertTrue(planner.calls("prepare reference").isEmpty());
            assertEquals(2, requests.size());
            assertNotEquals(requests.getFirst().branchId(), requests.getLast().branchId());
            assertEquals(List.of(new ModelAdapter.RoutingTask("prepare report", tools),
                    new ModelAdapter.RoutingTask("render report", tools)), requests.getFirst().tasks());
            assertEquals(List.of(new ModelAdapter.RoutingTask("look up reference", tools)), requests.getLast().tasks());
            assertEquals(List.of("C"), planner.models("prepare report"));
            assertEquals(List.of("C"), planner.models("render report"));
            assertEquals(List.of("B"), planner.models("look up reference"));
            assertEquals(List.of("A", "A"), planner.models("root"));
        }
    }

    private static CapabilitySearchPort replaceAfterPlanning(String stepId, CapabilityLookup.Definition replacement) {
        return new CapabilitySearchPort() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public CapabilityLookup.Match searchAtEntry(CapabilityLookup.Query query) {
                return CapabilitySearchPort.disabled().searchAtEntry(query);
            }

            @Override
            public CapabilityLookup.PlanMatches searchAfterPlanning(String runId, long revision, TaskPlan plan,
                                                                   Map<String, Object> input) {
                CapabilityLookup.PlanMatches empty = CapabilitySearchPort.disabled()
                        .searchAfterPlanning(runId, revision, plan, input);
                Map<String, CapabilityLookup.Match> matches = new LinkedHashMap<>(empty.steps());
                matches.put(stepId, new CapabilityLookup.Match(List.of(), Optional.of(replacement)));
                return new CapabilityLookup.PlanMatches(empty.whole(), matches);
            }

            @Override
            public boolean isAvailable(CapabilityLookup.Ref ref) {
                return replacement.ref().equals(ref);
            }
        };
    }

    @Test
    void nestedForkRestoresImmediateParentAndThenRootModel() throws Exception {
        // 保持 C 活动到 D/E 都开工，避免检查依赖线程完成先后。
        NestedPlanner planner = new NestedPlanner(true);
        try (Harness harness = new Harness(planner, List.of("A", "B", "C", "D", "E"), 4)) {
            AgentOrchestrator.Handle handle = harness.runSuccessfully();
            assertEquals(List.of("A", "A"), planner.models("root"));
            assertEquals(List.of("B", "B"), planner.models("left"));
            assertEquals(List.of("C"), planner.models("right"));
            assertEquals(List.of("D"), planner.models("deep-left"));
            assertEquals(List.of("E"), planner.models("deep-right"));
            assertTrue(harness.traces.snapshot(handle.rootRunId()).closed());
            // B 的第二轮只接收自己的子计划反馈，A 的第二轮保留自己的反馈。
            assertEquals("left-plan", planner.calls("left").getLast().history().getFirst().plan().goal());
            assertEquals("root-plan", planner.calls("root").getLast().history().getFirst().plan().goal());
        }
    }

    @Test
    void linearTasksAndSingleRecursiveTaskKeepTheCurrentModel() throws Exception {
        RecordingPlanner planner = new RecordingPlanner() {
            @Override
            AgentOrchestrator.Decision next(AgentOrchestrator.PlanningContext context) {
                return switch (context.goal()) {
                    case "root" -> {
                        if (!context.history().isEmpty()) {
                            yield complete("finished");
                        }
                        TaskPlan.Step first = task("first", "first", List.of());
                        TaskPlan.Step second = new TaskPlan.Step("second", "second", TaskPlan.Kind.TASK, null,
                                Map.of("branch", "second"), List.of("first"),
                                Map.of("previous", new TaskPlan.InputBinding("first", "/value")));
                        yield plan("linear-plan", first, second);
                    }
                    case "first" -> {
                        if (context.history().isEmpty()) {
                            yield plan("single-child-plan", task("only", "single-child", List.of()));
                        }
                        yield complete(Map.of("value", "first-result"));
                    }
                    case "single-child" -> complete("leaf-result");
                    case "second" -> {
                        assertEquals("first-result", context.arguments().get("previous"));
                        yield complete("second-result");
                    }
                    default -> throw new AssertionError("Unexpected goal: " + context.goal());
                };
            }
        };
        try (Harness harness = new Harness(planner, List.of("A", "B", "C"), 2)) {
            harness.runSuccessfully();
            assertEquals(List.of("A", "A"), planner.models("root"));
            assertEquals(List.of("A", "A"), planner.models("first"));
            assertEquals(List.of("A"), planner.models("single-child"));
            assertEquals(List.of("A"), planner.models("second"));
        }
    }

    @Test
    void partialModelSupplyUsesOtherModelThenParentModel() throws Exception {
        ParallelPlanner planner = new ParallelPlanner();
        try (Harness harness = new Harness(planner, List.of("A", "B"), 2)) {
            harness.runSuccessfully();
            assertEquals(List.of("B"), planner.models("left"));
            assertEquals(List.of("A"), planner.models("right"));
            assertEquals(List.of("A", "A"), planner.models("root"));
            // 两个模型都已进入调用才结束，证明不足时没有把分叉改成串行。
            assertEquals(0, planner.childrenStarted.getCount());
        }
    }

    @Test
    void oneModelStillRunsIndependentBranchesWithSeparateContexts() throws Exception {
        ParallelPlanner planner = new ParallelPlanner();
        try (Harness harness = new Harness(planner, List.of("A"), 2)) {
            harness.runSuccessfully();
            AgentOrchestrator.PlanningContext left = planner.calls("left").getFirst();
            AgentOrchestrator.PlanningContext right = planner.calls("right").getFirst();
            assertEquals("A", left.modelRef());
            assertEquals("A", right.modelRef());
            assertNotEquals(left.runId(), right.runId());
            assertNotSame(left.arguments(), right.arguments());
            assertEquals(Map.of("branch", "left"), left.arguments());
            assertEquals(Map.of("branch", "right"), right.arguments());
            assertTrue(left.history().isEmpty());
            assertTrue(right.history().isEmpty());
            // 父任务能收到两项实际输出，而不是某个分支覆盖另一个。
            AgentOrchestrator.Feedback feedback = planner.calls("root").getLast().history().getFirst();
            Map<?, ?> outputs = (Map<?, ?>) feedback.outcome().value();
            assertEquals("left-result", ((Map<?, ?>) outputs.get("left")).get("value"));
            assertEquals("right-result", ((Map<?, ?>) outputs.get("right")).get("value"));
        }
    }

    @Test
    void nestedForkDoesNotBlockItsOnlyWorkerWhileWaitingForChildren() throws Exception {
        // 不在替身里等待其他模型；单工作线程也必须能依次推进嵌套子任务。
        NestedPlanner planner = new NestedPlanner(false);
        try (Harness harness = new Harness(planner, List.of("A", "B", "C", "D", "E"), 1)) {
            harness.runSuccessfully();
            assertEquals(List.of("A", "A"), planner.models("root"));
            assertEquals(List.of("B", "B"), planner.models("left"));
            assertEquals(1, planner.calls("deep-left").size());
            assertEquals(1, planner.calls("deep-right").size());
        }
    }

    @Test
    void completedForkMakesItsModelsAvailableToNextPlan() throws Exception {
        RecordingPlanner planner = new RecordingPlanner() {
            @Override
            AgentOrchestrator.Decision next(AgentOrchestrator.PlanningContext context) {
                if (!context.goal().equals("root")) {
                    return complete(context.goal());
                }
                return switch (context.history().size()) {
                    case 0 -> plan("first-fork", task("left", "first-left", List.of()),
                            task("right", "first-right", List.of()));
                    case 1 -> plan("second-fork", task("left", "second-left", List.of()),
                            task("right", "second-right", List.of()));
                    default -> complete("finished");
                };
            }
        };
        try (Harness harness = new Harness(planner, List.of("A", "B", "C"), 2)) {
            harness.runSuccessfully();
            assertEquals(List.of("A", "A", "A"), planner.models("root"));
            assertEquals(List.of("B"), planner.models("first-left"));
            assertEquals(List.of("C"), planner.models("first-right"));
            assertEquals(List.of("B"), planner.models("second-left"));
            assertEquals(List.of("C"), planner.models("second-right"));
        }
    }

    @Test
    void localJoinRestoresParentBeforeAnotherForkInTheSamePlan() throws Exception {
        RecordingPlanner planner = new RecordingPlanner() {
            @Override
            AgentOrchestrator.Decision next(AgentOrchestrator.PlanningContext context) {
                if (!context.goal().equals("root")) {
                    return complete(context.goal());
                }
                if (!context.history().isEmpty()) {
                    return complete("finished");
                }
                return plan("two-local-forks",
                        task("first-left", "first-left", List.of()),
                        task("first-right", "first-right", List.of()),
                        task("join", "join", List.of("first-left", "first-right")),
                        task("next-left", "next-left", List.of("join")),
                        task("next-right", "next-right", List.of("join")));
            }
        };
        try (Harness harness = new Harness(planner, List.of("A", "B", "C"), 2)) {
            harness.runSuccessfully();
            assertEquals(List.of("B"), planner.models("first-left"));
            assertEquals(List.of("C"), planner.models("first-right"));
            assertEquals(List.of("A"), planner.models("join"));
            assertEquals(List.of("B"), planner.models("next-left"));
            assertEquals(List.of("C"), planner.models("next-right"));
            assertEquals(List.of("A", "A"), planner.models("root"));
        }
    }

    @Test
    void pureToolCapabilityDoesNotOpenAModelSession() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger adapterOpens = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        CountDownLatch bothToolsStarted = new CountDownLatch(2);
        TaskPlan pureTools = new TaskPlan("pure-tools", List.of(
                new TaskPlan.Step("left", "left-tool", TaskPlan.Kind.TOOL, "echo", Map.of("id", "left"), List.of(), Map.of()),
                new TaskPlan.Step("right", "right-tool", TaskPlan.Kind.TOOL, "echo", Map.of("id", "right"), List.of(), Map.of())));
        CapabilityLookup.Definition capability = new CapabilityLookup.Definition(
                new CapabilityLookup.Ref("checks", "pure-tools", 1), pureTools);
        CapabilitySearchPort lookup = new CapabilitySearchPort() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public CapabilityLookup.Match searchAtEntry(CapabilityLookup.Query query) {
                return new CapabilityLookup.Match(List.of(), Optional.of(capability));
            }

            @Override
            public CapabilityLookup.PlanMatches searchAfterPlanning(String runId, long revision, TaskPlan plan, Map<String, Object> input) {
                throw new AssertionError("A fully matched tool capability needs no model plan search");
            }

            @Override
            public boolean isAvailable(CapabilityLookup.Ref ref) {
                return capability.ref().equals(ref);
            }
        };
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                modelCalls.incrementAndGet();
                throw new AssertionError("Pure tool capability must skip model calls");
            }
        };
        ModelAdapter adapter = new ModelAdapter() {
            @Override
            public Session openRun(RunRequest request) {
                adapterOpens.incrementAndGet();
                throw new IllegalStateException("Model adapter is unavailable");
            }

            @Override
            public ChatModel resolve(String modelRef) {
                throw new AssertionError("Pure tool execution does not resolve a model");
            }
        };
        AgentOrchestrator.ActionExecutor actions = new AgentOrchestrator.ActionExecutor() {
            @Override
            public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
                assertEquals("echo", tool);
                toolCalls.incrementAndGet();
                bothToolsStarted.countDown();
                await(bothToolsStarted);
                return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, args.get("id"), null);
            }
        };
        try (Harness harness = new Harness(planner, lookup, actions, adapter, List.of("echo"), 2)) {
            AgentOrchestrator.Handle handle = harness.runSuccessfully();
            assertEquals(2, toolCalls.get());
            assertEquals(0, modelCalls.get());
            assertEquals(0, adapterOpens.get());
            assertTrue(harness.traces.snapshot(handle.rootRunId()).closed());
        }
    }

    @Test
    void missingModelAdapterFailsBeforeThePlannerIsCalled() throws Exception {
        AtomicInteger plannerCalls = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                plannerCalls.incrementAndGet();
                return complete("must-not-run");
            }
        };
        try (Harness harness = new Harness(planner, CapabilitySearchPort.disabled(), new RejectOrdinaryTools(), null, List.of(), 1)) {
            AgentOrchestrator.Handle handle = harness.submit();
            AgentOrchestrator.Outcome outcome = handle.result().get(5, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.FAILED, outcome.status());
            assertTrue(outcome.error().contains(LlmTexts.MODEL_ADAPTER_REQUIRED), outcome.error());
            assertEquals(0, plannerCalls.get());
            assertTrue(harness.traces.snapshot(handle.rootRunId()).closed());
        }
    }

    @Test
    void openingAdapterFailureIsReportedWithoutInventingAFallback() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                throw new AssertionError("Planner must not run when adapter opening fails");
            }
        };
        ModelAdapter broken = new ModelAdapter() {
            @Override
            public Session openRun(RunRequest request) {
                opens.incrementAndGet();
                throw new IllegalStateException("adapter-open-failed");
            }

            @Override
            public ChatModel resolve(String modelRef) {
                throw new AssertionError("Adapter resolution must not run");
            }
        };
        try (Harness harness = new Harness(planner, CapabilitySearchPort.disabled(), new RejectOrdinaryTools(), broken, List.of(), 1)) {
            AgentOrchestrator.Outcome outcome = harness.submit().result().get(5, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.FAILED, outcome.status());
            assertTrue(outcome.error().contains("adapter-open-failed"), outcome.error());
            assertEquals(1, opens.get());
        }
    }

    @Test
    void mixedCapabilityOpensItsModelSessionOnlyAtTheFirstTask() throws Exception {
        TestModelAdapter adapter = new TestModelAdapter(List.of("A", "B"));
        AtomicInteger tools = new AtomicInteger();
        AtomicInteger models = new AtomicInteger();
        TaskPlan mixed = new TaskPlan("mixed-capability", List.of(
                new TaskPlan.Step("prepare", "prepare", TaskPlan.Kind.TOOL, "echo", Map.of(), List.of(), Map.of()),
                new TaskPlan.Step("child", "mixed-child", TaskPlan.Kind.TASK, null, Map.of(), List.of("prepare"),
                        Map.of("prepared", new TaskPlan.InputBinding("prepare", "/value")))));
        CapabilityLookup.Definition capability = new CapabilityLookup.Definition(
                new CapabilityLookup.Ref("checks", "mixed-capability", 1), mixed);
        AgentOrchestrator.ActionExecutor actions = new AgentOrchestrator.ActionExecutor() {
            @Override
            public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
                assertEquals(0, adapter.openCalls(), "The preceding tool should not open a model session");
                tools.incrementAndGet();
                return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, Map.of("value", "ready"), null);
            }
        };
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                assertEquals("mixed-child", context.goal());
                assertEquals("A", context.modelRef());
                assertEquals("ready", context.arguments().get("prepared"));
                assertEquals(1, adapter.openCalls());
                models.incrementAndGet();
                return complete("child-result");
            }
        };
        try (Harness harness = new Harness(planner, entryCapability(capability), actions, adapter, List.of("echo"), 2)) {
            harness.runSuccessfully();
            assertEquals(1, tools.get());
            assertEquals(1, models.get());
            assertEquals(1, adapter.openCalls());
            assertEquals(1, adapter.sessions().getFirst().closeCalls());
        }
    }

    @Test
    void cancellationClosesTheSessionOnlyAfterTheActualModelReturns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestModelAdapter adapter = new TestModelAdapter(List.of("A"));
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                started.countDown();
                await(release);
                return complete("late-result");
            }
        };
        try (Harness harness = new Harness(planner, CapabilitySearchPort.disabled(), new RejectOrdinaryTools(), adapter, List.of(), 1)) {
            AgentOrchestrator.Handle handle = harness.submit();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            TestModelAdapter.TestSession session = adapter.sessions().getFirst();
            handle.cancel().run();
            assertEquals(AgentOrchestrator.Status.CANCELLED, handle.result().get(2, TimeUnit.SECONDS).status());
            assertFalse(session.closed());
            assertEquals(0, session.closeCalls());
            assertFalse(harness.traces.snapshot(handle.rootRunId()).closed());
            release.countDown();
            assertTrue(session.awaitClosed(5));
            assertEquals(1, session.closeCalls());
        } finally {
            release.countDown();
        }
    }

    @Test
    void sessionCloseFailureCompletesUnknownAndPreservesTheActualValue() throws Exception {
        FaultyAdapter adapter = new FaultyAdapter(AdapterFault.CLOSE);
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                return complete(Map.of("actual", "preserved"));
            }
        };
        try (Harness harness = new Harness(planner, CapabilitySearchPort.disabled(), new RejectOrdinaryTools(), adapter, List.of(), 1)) {
            AgentOrchestrator.Handle handle = harness.submit();
            AgentOrchestrator.Outcome outcome = handle.result().get(5, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.UNKNOWN, outcome.status());
            assertEquals(Map.of("actual", "preserved"), outcome.value());
            assertTrue(outcome.error().contains("adapter-close-failed"), outcome.error());
            assertEquals(1, adapter.delegate.sessions().getFirst().closeCalls());
            assertTrue(harness.traces.snapshot(handle.rootRunId()).closed());
        }
    }

    @Test
    void branchReleaseFailurePreservesPlanOutputsAndStopsParentReplanning() throws Exception {
        FaultyAdapter adapter = new FaultyAdapter(AdapterFault.RELEASE);
        RecordingPlanner planner = new RecordingPlanner() {
            @Override
            AgentOrchestrator.Decision next(AgentOrchestrator.PlanningContext context) {
                if (context.goal().equals("root")) {
                    if (context.history().isEmpty()) {
                        return plan("release-failure-plan", task("left", "left", List.of()), task("right", "right", List.of()));
                    }
                    return complete("parent-must-not-continue");
                }
                return complete(context.goal() + "-result");
            }
        };
        try (Harness harness = new Harness(planner, CapabilitySearchPort.disabled(), new RejectOrdinaryTools(), adapter, List.of(), 2)) {
            AgentOrchestrator.Handle handle = harness.submit();
            AgentOrchestrator.Outcome outcome = handle.result().get(5, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.UNKNOWN, outcome.status());
            assertTrue(outcome.error().contains("adapter-release-failed"), outcome.error());
            Map<?, ?> outputs = (Map<?, ?>) outcome.value();
            assertEquals("left-result", ((Map<?, ?>) outputs.get("left")).get("value"));
            assertEquals("right-result", ((Map<?, ?>) outputs.get("right")).get("value"));
            assertEquals("SUCCESS", ((Map<?, ?>) outputs.get("left")).get("status"));
            assertEquals("SUCCESS", ((Map<?, ?>) outputs.get("right")).get("status"));
            assertEquals(List.of("A"), planner.models("root"));
            assertEquals(List.of("B"), planner.models("left"));
            assertEquals(List.of("C"), planner.models("right"));
            assertEquals(1, adapter.delegate.sessions().getFirst().closeCalls());
            assertTrue(adapter.delegate.sessions().getFirst().snapshot().isEmpty());
            assertTrue(harness.traces.snapshot(handle.rootRunId()).closed());
        }
    }

    @Test
    void malformedForkResultsNeverReachAnyChildModel() throws Exception {
        for (AdapterFault fault : List.of(AdapterFault.HALF_FORK, AdapterFault.WRONG_PARENT)) {
            FaultyAdapter adapter = new FaultyAdapter(fault);
            AtomicInteger childModels = new AtomicInteger();
            AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
                @Override
                public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                    if (!context.goal().equals("root")) {
                        childModels.incrementAndGet();
                        return complete("must-not-run");
                    }
                    if (context.history().isEmpty()) {
                        return plan("malformed-adapter-plan", task("left", "left", List.of()), task("right", "right", List.of()));
                    }
                    assertEquals(AgentOrchestrator.Status.FAILED, context.history().getFirst().outcome().status());
                    Map<?, ?> steps = (Map<?, ?>) context.history().getFirst().outcome().value();
                    for (Object step : steps.values()) {
                        String error = (String) ((Map<?, ?>) step).get("error");
                        assertTrue(error.contains(LlmTexts.MODEL_ADAPTER_INVALID_BINDING_PREFIX), error);
                    }
                    return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.NEEDS_INPUT, null, "adapter-contract-failed");
                }
            };
            try (Harness harness = new Harness(planner, CapabilitySearchPort.disabled(), new RejectOrdinaryTools(), adapter, List.of(), 2)) {
                AgentOrchestrator.Outcome outcome = harness.submit().result().get(5, TimeUnit.SECONDS);
                assertEquals(AgentOrchestrator.Status.NEEDS_INPUT, outcome.status(), outcome.error());
                assertEquals(0, childModels.get(), fault.name());
                assertEquals(1, adapter.delegate.sessions().getFirst().closeCalls());
                assertTrue(adapter.delegate.sessions().getFirst().snapshot().isEmpty());
            }
        }
    }

    private static CapabilitySearchPort entryCapability(CapabilityLookup.Definition capability) {
        return new CapabilitySearchPort() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public CapabilityLookup.Match searchAtEntry(CapabilityLookup.Query query) {
                return new CapabilityLookup.Match(List.of(), Optional.of(capability));
            }

            @Override
            public CapabilityLookup.PlanMatches searchAfterPlanning(String runId, long revision, TaskPlan plan, Map<String, Object> input) {
                return CapabilitySearchPort.disabled().searchAfterPlanning(runId, revision, plan, input);
            }

            @Override
            public boolean isAvailable(CapabilityLookup.Ref ref) {
                return capability.ref().equals(ref);
            }
        };
    }

    private static TaskPlan.Step task(String id, String goal, List<String> dependencies) {
        return new TaskPlan.Step(id, goal, TaskPlan.Kind.TASK, null, Map.of("branch", goal), dependencies, Map.of());
    }

    private static AgentOrchestrator.Decision plan(String goal, TaskPlan.Step... steps) {
        return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN, new TaskPlan(goal, List.of(steps)), null);
    }

    private static AgentOrchestrator.Decision complete(Object output) {
        return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, output);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Independent branches did not both start");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while checking parallel progress", failure);
        }
    }

    private abstract static class RecordingPlanner implements AgentOrchestrator.Planner {
        /** 记录实际进入规划器的上下文，而不是读取预期分配表。 */
        private final List<AgentOrchestrator.PlanningContext> recorded = new CopyOnWriteArrayList<>();

        @Override
        public final AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
            recorded.add(context);
            return next(context);
        }

        abstract AgentOrchestrator.Decision next(AgentOrchestrator.PlanningContext context);

        final List<AgentOrchestrator.PlanningContext> calls(String goal) {
            List<AgentOrchestrator.PlanningContext> matches = new ArrayList<>();
            for (AgentOrchestrator.PlanningContext context : recorded) {
                if (context.goal().equals(goal)) {
                    matches.add(context);
                }
            }
            return List.copyOf(matches);
        }

        final List<String> models(String goal) {
            List<String> result = new ArrayList<>();
            for (AgentOrchestrator.PlanningContext context : calls(goal)) {
                result.add(context.modelRef());
            }
            return List.copyOf(result);
        }
    }

    private static final class NestedPlanner extends RecordingPlanner {
        /** 多线程用例中，让 C 保持活动直到 D/E 都进入模型请求。 */
        private final boolean holdRightBranch;
        /** 两个更深子任务的到达信号。 */
        private final CountDownLatch nestedStarted = new CountDownLatch(2);

        private NestedPlanner(boolean holdRightBranch) {
            this.holdRightBranch = holdRightBranch;
        }

        @Override
        AgentOrchestrator.Decision next(AgentOrchestrator.PlanningContext context) {
            return switch (context.goal()) {
                case "root" -> {
                    if (context.history().isEmpty()) {
                        yield plan("root-plan", task("left", "left", List.of()), task("right", "right", List.of()));
                    }
                    yield complete("root-result");
                }
                case "left" -> {
                    if (context.history().isEmpty()) {
                        yield plan("left-plan", task("deep-left", "deep-left", List.of()),
                                task("deep-right", "deep-right", List.of()));
                    }
                    yield complete("left-result");
                }
                case "right" -> {
                    if (holdRightBranch) {
                        await(nestedStarted);
                    }
                    yield complete("right-result");
                }
                case "deep-left", "deep-right" -> {
                    nestedStarted.countDown();
                    yield complete(context.goal());
                }
                default -> throw new AssertionError("Unexpected goal: " + context.goal());
            };
        }
    }

    private static final class ParallelPlanner extends RecordingPlanner {
        /** 两个独立子请求都抵达才允许任一个完成。 */
        private final CountDownLatch childrenStarted = new CountDownLatch(2);

        @Override
        AgentOrchestrator.Decision next(AgentOrchestrator.PlanningContext context) {
            if (context.goal().equals("root")) {
                if (context.history().isEmpty()) {
                    return plan("parallel-plan", task("left", "left", List.of()), task("right", "right", List.of()));
                }
                return complete("root-result");
            }
            childrenStarted.countDown();
            await(childrenStarted);
            return complete(context.goal() + "-result");
        }
    }

    private static final class Harness implements AutoCloseable {
        /** 检查实际模型调用可在有限工作线程中前进。 */
        private final ExecutorService workers;
        /** 截止时间使用单独线程，不参与模型执行。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 每个检查只保存自己的内存执行图。 */
        private final ExecutionTraceStore traces = new ExecutionTraceStore(4, 500, 4000);
        /** 使用外部端口装配的被测编排器。 */
        private final AgentOrchestrator orchestrator;
        /** 此场景允许的普通工具；TASK 场景保持为空。 */
        private final List<String> toolNames;

        private Harness(AgentOrchestrator.Planner planner, List<String> models, int workerCount) {
            this(planner, CapabilitySearchPort.disabled(), new RejectOrdinaryTools(), new TestModelAdapter(models), List.of(), workerCount);
        }

        private Harness(AgentOrchestrator.Planner planner, CapabilitySearchPort lookup,
                        AgentOrchestrator.ActionExecutor actions, ModelAdapter adapter, List<String> toolNames, int workerCount) {
            workers = Executors.newFixedThreadPool(workerCount);
            this.toolNames = List.copyOf(toolNames);
            // 放行策略仅限该检查；真实门禁和验收由生产调用方提供。
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
            AgentOrchestrator.Limits limits = new AgentOrchestrator.Limits(6, 12, 40, 20, 100, 10, Duration.ofSeconds(15));
            // 显式提供可靠统计；adapter 为 null 时仍不能偷偷提供默认模型。
            orchestrator = new AgentOrchestrator(planner, lookup, com.example.myhelper.support.ReliableTools.bind(actions),
                    checks, traces, workers, timer, limits,
                    adapter, com.example.myhelper.records.TaskRecordPort.disabled(),
                    new com.example.myhelper.capability.CheckedCapabilityUse(lookup, checks),
                    com.example.myhelper.support.ReliableTools.gate());
        }

        private AgentOrchestrator.Handle runSuccessfully() throws Exception {
            AgentOrchestrator.Handle handle = submit();
            AgentOrchestrator.Outcome outcome = handle.result().get(10, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.SUCCESS, outcome.status(), outcome.error());
            return handle;
        }

        private AgentOrchestrator.Handle submit() {
            return orchestrator.submit(new AgentOrchestrator.Request(
                    "root", Map.of("privateRoot", "original"), "A", toolNames));
        }

        @Override
        public void close() {
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }

    private enum AdapterFault {
        CLOSE,
        RELEASE,
        HALF_FORK,
        WRONG_PARENT
    }

    /** 外部适配器合同故障替身，分配状态仍由检查会话维护。 */
    private static final class FaultyAdapter implements ModelAdapter {
        /** 用于观察正常回收次数和实际遗留状态。 */
        private final TestModelAdapter delegate = new TestModelAdapter(List.of("A", "B", "C"));
        /** 本场景故意违反的端口合同。 */
        private final AdapterFault fault;

        private FaultyAdapter(AdapterFault fault) {
            this.fault = fault;
        }

        @Override
        public Session openRun(RunRequest request) {
            TestModelAdapter.TestSession actual = delegate.openRun(request);
            return new Session() {
                @Override
                public Binding root() {
                    return actual.root();
                }

                @Override
                public Binding binding(String branchId) {
                    return actual.binding(branchId);
                }

                @Override
                public List<Binding> fork(Binding parent, List<String> childIds) {
                    List<Binding> allocated = actual.fork(parent, childIds);
                    return switch (fault) {
                        case HALF_FORK -> List.of(allocated.getFirst());
                        case WRONG_PARENT -> {
                            List<Binding> malformed = new ArrayList<>(allocated);
                            Binding first = allocated.getFirst();
                            malformed.set(0, new Binding(first.id(), "incorrect-parent", first.modelRef(), first.assignment()));
                            yield List.copyOf(malformed);
                        }
                        case CLOSE, RELEASE -> allocated;
                    };
                }

                @Override
                public boolean release(String branchId) {
                    if (fault == AdapterFault.RELEASE) {
                        throw new IllegalStateException("adapter-release-failed");
                    }
                    return actual.release(branchId);
                }

                @Override
                public void close() {
                    actual.close();
                    if (fault == AdapterFault.CLOSE) {
                        throw new IllegalStateException("adapter-close-failed");
                    }
                }
            };
        }

        @Override
        public ChatModel resolve(String modelRef) {
            return delegate.resolve(modelRef);
        }
    }

    /** 只有 TASK 的检查场景若意外触发工具，立即报告错误。 */
    private static final class RejectOrdinaryTools implements AgentOrchestrator.ActionExecutor {
        @Override
        public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
            throw new AssertionError("No ordinary tool should run in this scenario");
        }
    }
}
