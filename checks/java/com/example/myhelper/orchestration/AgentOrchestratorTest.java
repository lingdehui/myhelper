package com.example.myhelper.orchestration;

import com.example.myhelper.agent.AgentCaller;
import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.support.TestModelAdapter;
import com.example.myhelper.tasklog.TaskLogPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import static org.junit.jupiter.api.Assertions.*;

class AgentOrchestratorTest {
    // 普通流程测试关闭能力复用；专门的复用用例自行提供匹配器。
    private static final CapabilityLookup.Matcher NEVER_MATCH = new CapabilityLookup.Matcher() {
        @Override
        public boolean matches(CapabilityLookup.Query query, CapabilityLookup.Candidate candidate) {
            return false;
        }
    };
    private static final AgentOrchestrator.Checks CHECKS = new AgentOrchestrator.Checks() {
        public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) { return true; }
        public boolean verify(String goal, Map<String, Object> args, Object output) { return true; }
    };

    @Test void entryOnceAndDatabaseSearchAfterEveryPlan() throws Exception {
        Repo repo = new Repo();
        AtomicInteger calls = new AtomicInteger();
        try (Harness h = new Harness(repo, NEVER_MATCH, new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                assertEquals(Map.of(), context.worldContext());
                int round = calls.incrementAndGet();
                return round <= 2 ? plan("模型计划" + round, step("s", "模型子任务" + round, TaskPlan.Kind.TOOL)) : complete("结束");
            }
        }, new AgentOrchestrator.ActionExecutor() {
            @Override
            public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
                return success("done");
            }
        }, 2)) {
            var handle = h.submit();
            assertEquals(AgentOrchestrator.Status.SUCCESS, handle.result().get(5, TimeUnit.SECONDS).status());
            assertEquals(List.of("用户目标", "模型计划1", "模型子任务1", "模型计划2", "模型子任务2"), queryGoals(repo.queries));
            assertEquals(1, entryQueryCount(repo.queries));
            assertEquals(3, calls.get());
            assertTrue(h.traces.snapshot(handle.rootRunId()).closed());
        }
    }

    @Test void worldContextIsReadForEachTaskAndPlanningRound() throws Exception {
        List<AgentOrchestrator.PlanningContext> queries = new CopyOnWriteArrayList<>();
        WorldContextPort world = new WorldContextPort() {
            @Override
            public Map<String, Object> snapshot(AgentOrchestrator.PlanningContext context) {
                assertTrue(context.worldContext().isEmpty());
                queries.add(context);
                return Map.of("observedFor", context.arguments().getOrDefault("room", "root"),
                        "round", context.history().size() + 1);
            }
        };
        try (Harness h = new Harness(new Repo(), NEVER_MATCH, new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                switch (context.goal()) {
                    case "子任务 A":
                        assertEquals(Map.of("observedFor", "A", "round", 1), context.worldContext());
                        return complete("A");
                    case "子任务 B":
                        assertEquals(Map.of("observedFor", "B", "round", 1), context.worldContext());
                        return complete("B");
                    default:
                        assertEquals(Map.of("observedFor", "root", "round", context.history().size() + 1),
                                context.worldContext());
                        if (context.history().isEmpty()) {
                            return plan("分解",
                                    new TaskPlan.Step("a", "子任务 A", TaskPlan.Kind.TASK,
                                            null, Map.of("room", "A"), List.of(), Map.of()),
                                    new TaskPlan.Step("b", "子任务 B", TaskPlan.Kind.TASK,
                                            null, Map.of("room", "B"), List.of(), Map.of()));
                        }
                        return complete("完成");
                }
            }
        }, new AgentOrchestrator.ActionExecutor() {
            @Override
            public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
                throw new AssertionError("本任务只需规划与子任务答复");
            }
        }, 2, world)) {
            var handle = h.submit();
            assertEquals(AgentOrchestrator.Status.SUCCESS, handle.settled().get(5, TimeUnit.SECONDS).status());
            assertEquals(4, queries.size());
            String parentRun = queries.getFirst().runId();
            assertEquals(parentRun, queries.getLast().runId());
            assertNotEquals(queries.get(1).runId(), queries.get(2).runId());
            assertNotEquals(parentRun, queries.get(1).runId());
            assertNotEquals(parentRun, queries.get(2).runId());
        }
    }

    @Test void entryWholeCapabilitySkipsModel() throws Exception {
        Repo repo = new Repo();
        repo.def = new CapabilityLookup.Definition(new CapabilityLookup.Ref("test", "whole", 1),
                new TaskPlan("固化流程", List.of(step("a", "执行固化", TaskPlan.Kind.TOOL))));
        AtomicInteger executions = new AtomicInteger();
        try (Harness h = new Harness(repo, new CapabilityLookup.Matcher() {
            @Override
            public boolean matches(CapabilityLookup.Query q, CapabilityLookup.Candidate c) {
                return true;
            }
        }, new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                fail("入口整条命中不应调用模型"); return null;
            }
        },
                new AgentOrchestrator.ActionExecutor() {
                    @Override
                    public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
                        executions.incrementAndGet(); return success("ok");
                    }
                }, 1)) {
            assertEquals(AgentOrchestrator.Status.SUCCESS, h.submit().result().get(5, TimeUnit.SECONDS).status());
            assertEquals(1, repo.queries.size());
            assertEquals(1, executions.get());
        }
    }

    @Test void newlyDiscoveredStepCapabilitySkipsRecursiveModel() throws Exception {
        Repo repo = new Repo();
        repo.def = new CapabilityLookup.Definition(new CapabilityLookup.Ref("test", "sub", 1),
                new TaskPlan("固化子流程", List.of(step("leaf", "实际工作", TaskPlan.Kind.TOOL))));
        AtomicInteger calls = new AtomicInteger();
        try (Harness h = new Harness(repo, new CapabilityLookup.Matcher() {
            @Override
            public boolean matches(CapabilityLookup.Query q, CapabilityLookup.Candidate c) {
                return q.goal().equals("可复用子任务");
            }
        }, new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                calls.incrementAndGet();
                return context.history().isEmpty() ? plan("计划", step("child", "可复用子任务", TaskPlan.Kind.TASK)) : complete("完成");
            }
        }, new AgentOrchestrator.ActionExecutor() {
            @Override
            public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
                return success("ok");
            }
        }, 1)) {
            var handle = h.submit();
            assertEquals(AgentOrchestrator.Status.SUCCESS, handle.result().get(5, TimeUnit.SECONDS).status());
            assertEquals(2, calls.get());
            assertEquals(3, repo.queries.size());
            assertEquals(1, repo.loads.get());
            assertTrue(hasUnitNode(h.traces.snapshot(handle.rootRunId())));
        }
    }

    @Test void recursiveTasksDoNotRepeatEntryOrStarveSingleWorker() throws Exception {
        Repo repo = new Repo();
        try (Harness h = new Harness(repo, NEVER_MATCH, new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                if (!context.goal().equals("用户目标")) return complete(context.goal());
                return context.history().isEmpty() ? plan("分解", step("a", "子任务 A", TaskPlan.Kind.TASK), step("b", "子任务 B", TaskPlan.Kind.TASK)) : complete("完成");
            }
        }, new AgentOrchestrator.ActionExecutor() {
            @Override
            public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
                return success(null);
            }
        }, 1)) {
            assertEquals(AgentOrchestrator.Status.SUCCESS, h.submit().result().get(5, TimeUnit.SECONDS).status());
            assertEquals(1, entryQueryCount(repo.queries));
        }
    }

    @Test void independentToolsOverlapAndJoinBindsOutputs() throws Exception {
        CountDownLatch bothStarted = new CountDownLatch(2);
        List<String> ran = new CopyOnWriteArrayList<>();
        TaskPlan.Step a = new TaskPlan.Step("a", "A", TaskPlan.Kind.TOOL, "echo", Map.of("id", "a"), List.of(), Map.of());
        TaskPlan.Step b = new TaskPlan.Step("b", "B", TaskPlan.Kind.TOOL, "echo", Map.of("id", "b"), List.of(), Map.of());
        TaskPlan.Step join = new TaskPlan.Step("join", "汇合", TaskPlan.Kind.TOOL, "echo", Map.of("id", "join"), List.of("a", "b"),
                Map.of("left", new TaskPlan.InputBinding("a", "/value"), "right", new TaskPlan.InputBinding("b", "/value")));
        try (Harness h = new Harness(new Repo(), NEVER_MATCH,
                new AgentOrchestrator.Planner() {
                    @Override
                    public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                        return context.history().isEmpty() ? plan("并发计划", join, b, a) : complete("完成");
                    }
                }, new AgentOrchestrator.ActionExecutor() {
                    @Override
                    public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
                        String id = (String) args.get("id");
                        if (!id.equals("join")) {
                            bothStarted.countDown();
                            try { assertTrue(bothStarted.await(2, TimeUnit.SECONDS), "两个独立调用应该同时启动"); }
                            catch (InterruptedException error) { throw new RuntimeException(error); }
                            ran.add(id);
                            return success(Map.of("value", id));
                        }
                        assertEquals(2, ran.size());
                        assertEquals("a", args.get("left"));
                        assertEquals("b", args.get("right"));
                        return success("joined");
                    }
                }, 3)) {
            var handle = h.submit();
            assertEquals(AgentOrchestrator.Status.SUCCESS, handle.result().get(5, TimeUnit.SECONDS).status());
            var snapshot = h.traces.snapshot(handle.rootRunId());
            assertEquals(2, dataEdgeCount(snapshot));
            assertThrows(UnsupportedOperationException.class, new Executable() {
                @Override
                public void execute() throws Throwable {
                    snapshot.nodes().clear();
                }
            });
        }
    }

    @Test void dependencyFailureSkipsDependentAndReplansWithoutRepeatingEntry() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        var dependent = new TaskPlan.Step("b", "不应运行", TaskPlan.Kind.TOOL, "echo", Map.of(), List.of("a"), Map.of());
        Repo repo = new Repo();
        try (Harness h = new Harness(repo, NEVER_MATCH, new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                return context.history().isEmpty()
                ? plan("有失败", step("a", "失败步骤", TaskPlan.Kind.TOOL), dependent)
                : new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.NEEDS_INPUT, null, "需要处理失败");
            }
        },
                new AgentOrchestrator.ActionExecutor() {
                    @Override
                    public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
                        executions.incrementAndGet(); return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.FAILED, null, "明确失败");
                    }
                }, 2)) {
            assertEquals(AgentOrchestrator.Status.NEEDS_INPUT, h.submit().result().get(5, TimeUnit.SECONDS).status());
            assertEquals(1, executions.get());
            assertEquals(1, entryQueryCount(repo.queries));
        }
    }

    @Test void unknownEffectsStopAutomaticReplanning() throws Exception {
        AtomicInteger models = new AtomicInteger();
        try (Harness h = new Harness(new Repo(), NEVER_MATCH, new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                models.incrementAndGet(); return plan("计划", step("a", "动作", TaskPlan.Kind.TOOL));
            }
        }, new AgentOrchestrator.ActionExecutor() {
            @Override
            public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
                return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.UNKNOWN, null, "响应丢失");
            }
        }, 1)) {
            assertEquals(AgentOrchestrator.Status.UNKNOWN, h.submit().result().get(5, TimeUnit.SECONDS).status());
            assertEquals(1, models.get());
        }
    }

    @Test void thrownExecutionErrorAlsoStopsAutomaticReplanning() throws Exception {
        AtomicInteger models = new AtomicInteger();
        try (Harness h = new Harness(new Repo(), NEVER_MATCH, new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                models.incrementAndGet(); return plan("计划", step("a", "动作", TaskPlan.Kind.TOOL));
            }
        }, new AgentOrchestrator.ActionExecutor() {
            @Override
            public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
                throw new IllegalStateException("提交后响应丢失");
            }
        }, 1)) {
            assertEquals(AgentOrchestrator.Status.UNKNOWN, h.submit().result().get(5, TimeUnit.SECONDS).status());
            assertEquals(1, models.get());
        }
    }

    @Test void modelAdapterAndRealCallbackAreConnected() throws Exception {
        AtomicInteger models = new AtomicInteger();
        AtomicInteger tools = new AtomicInteger();
        ToolCallback echo = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("echo").description("回声").inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) { tools.incrementAndGet(); return "{\"ok\":true}"; }
        };
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                boolean first = models.incrementAndGet() == 1;
                String args = first ? encode(new TaskPlan("模型新目标", List.of(step("a", "实际回声", TaskPlan.Kind.TOOL))))
                        : "{\"kind\":\"COMPLETE\",\"output\":\"完成\"}";
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("c" + models.get(), "function", first ? "submit_plan" : "finish_task", args))).build())));
            }
        };
        AgentCallerPlanner planner = new AgentCallerPlanner(new AgentCaller(new Function<String, ChatModel>() {
            @Override
            public ChatModel apply(String ref) {
                return model;
            }
        }, List.of(echo)));
        SpringToolExecutor executor = new SpringToolExecutor(List.of(echo), new SpringToolExecutor.ResourceResolver() {
            @Override
            public List<String> keys(String tool, Map<String, Object> args) {
                return List.of();
            }
        }, 32);
        Repo repo = new Repo();
        try (Harness h = new Harness(repo, NEVER_MATCH, planner, executor, 2)) {
            assertEquals(AgentOrchestrator.Status.SUCCESS, h.submit().result().get(5, TimeUnit.SECONDS).status());
            assertEquals(2, models.get());
            assertEquals(1, tools.get());
            assertEquals(List.of("用户目标", "模型新目标", "实际回声"), queryGoals(repo.queries));
        }
    }

    @Test void cyclesRejectedBeforeAnyExecutionAndInputsAreDeepImmutable() {
        var a = new TaskPlan.Step("a", "A", TaskPlan.Kind.TOOL, "echo", Map.of(), List.of("b"), Map.of());
        var b = new TaskPlan.Step("b", "B", TaskPlan.Kind.TOOL, "echo", Map.of(), List.of("a"), Map.of());
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                new TaskPlan("环", List.of(a, b));
            }
        });
        List<String> mutable = new ArrayList<>(List.of("old"));
        var request = new AgentOrchestrator.Request("目标", Map.of("nested", mutable), "m", List.of());
        mutable.add("new");
        assertEquals(List.of("old"), request.arguments().get("nested"));
    }

    @Test void cancellationDoesNotCloseTraceUntilInFlightActionFinishes() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Harness h = new Harness(new Repo(), NEVER_MATCH, new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                return plan("计划", step("a", "慢动作", TaskPlan.Kind.TOOL));
            }
        },
                new AgentOrchestrator.ActionExecutor() {
                    @Override
                    public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args, AgentOrchestrator.CallContext context) {
                        started.countDown();
                        try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        return success("实际完成");
                    }
                }, 1)) {
            var handle = h.submit();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            handle.cancel().run();
            assertEquals(AgentOrchestrator.Status.CANCELLED, handle.result().get(1, TimeUnit.SECONDS).status());
            assertFalse(h.traces.snapshot(handle.rootRunId()).closed());
            assertThrows(IllegalStateException.class, new Executable() {
                @Override
                public void execute() throws Throwable {
                    h.traces.remove(handle.rootRunId());
                }
            });
            release.countDown();
        } finally { release.countDown(); }
    }

    private static TaskPlan.Step step(String id, String goal, TaskPlan.Kind kind) {
        return new TaskPlan.Step(id, goal, kind, kind == TaskPlan.Kind.TOOL ? "echo" : null, Map.of(), List.of(), Map.of());
    }
    private static AgentOrchestrator.Decision plan(String goal, TaskPlan.Step... steps) {
        return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN, new TaskPlan(goal, List.of(steps)), null);
    }
    private static AgentOrchestrator.Decision complete(Object value) { return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, value); }
    private static AgentOrchestrator.Outcome success(Object value) { return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, value, null); }
    private static String encode(Object value) { try { return new ObjectMapper().writeValueAsString(value); } catch (Exception error) { throw new RuntimeException(error); } }

    private static final class Repo implements CapabilityLookup.Repository {
        private final List<CapabilityLookup.Query> queries = new CopyOnWriteArrayList<>();
        private final AtomicInteger loads = new AtomicInteger();
        private CapabilityLookup.Definition def;
        public List<CapabilityLookup.Candidate> search(CapabilityLookup.Query query) {
            queries.add(query);
            return def == null || query.planRevision() == 0 && def.ref().id().equals("sub") ? List.of()
                    : List.of(new CapabilityLookup.Candidate(def.ref(), "候选", Map.of()));
        }
        public Optional<CapabilityLookup.Definition> load(CapabilityLookup.Ref ref) { loads.incrementAndGet(); return Optional.ofNullable(def); }
        public boolean isAvailable(CapabilityLookup.Ref ref) { return true; }
    }

    private static final class Harness implements AutoCloseable {
        private final java.util.concurrent.ExecutorService workers;
        private final java.util.concurrent.ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        private final ExecutionTraceStore traces = new ExecutionTraceStore(10, 1000, 10000);
        private final AgentOrchestrator runtime;
        private Harness(Repo repo, CapabilityLookup.Matcher matcher, AgentOrchestrator.Planner planner,
                        AgentOrchestrator.ActionExecutor actions, int threads) {
            this(repo, matcher, planner, actions, threads, WorldContextPort.disabled());
        }

        private Harness(Repo repo, CapabilityLookup.Matcher matcher, AgentOrchestrator.Planner planner,
                        AgentOrchestrator.ActionExecutor actions, int threads, WorldContextPort world) {
            workers = Executors.newFixedThreadPool(threads);
            CapabilityLookup lookup = new CapabilityLookup(repo, matcher, 8, Duration.ofMinutes(1));
            runtime = new AgentOrchestrator(planner, lookup, com.example.myhelper.support.ReliableTools.bind(actions),
                    CHECKS, traces, workers, timer, new AgentOrchestrator.Limits(5, 5, 20, 20, 30, 30, Duration.ofSeconds(10)),
                    new TestModelAdapter(List.of("model")), com.example.myhelper.records.TaskRecordPort.disabled(),
                    new com.example.myhelper.capability.CheckedCapabilityUse(lookup, CHECKS),
                    com.example.myhelper.support.ReliableTools.gate(), TaskLogPort.disabled(), RunActivityPort.disabled(), world);
        }
        private AgentOrchestrator.Handle submit() { return runtime.submit(new AgentOrchestrator.Request("用户目标", Map.of(), "model", List.of("echo"))); }
        public void close() {
            workers.shutdown();
            try { workers.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            workers.shutdownNow(); timer.shutdownNow();
        }
    }
    // 用循环读取断言需要的值，避免把筛选回调塞进测试断言。
    private static List<String> queryGoals(List<CapabilityLookup.Query> queries) {
        List<String> goals = new ArrayList<>();
        for (CapabilityLookup.Query query : queries) {
            goals.add(query.goal());
        }
        return goals;
    }

    private static long entryQueryCount(List<CapabilityLookup.Query> queries) {
        long count = 0;
        for (CapabilityLookup.Query query : queries) {
            if (query.planRevision() == 0) count++;
        }
        return count;
    }

    private static boolean hasUnitNode(ExecutionTraceStore.Snapshot snapshot) {
        for (ExecutionTraceStore.Node node : snapshot.nodes().values()) {
            if (node.kind() == ExecutionTraceStore.NodeKind.UNIT) return true;
        }
        return false;
    }

    private static long dataEdgeCount(ExecutionTraceStore.Snapshot snapshot) {
        long count = 0;
        for (ExecutionTraceStore.Edge edge : snapshot.edges()) {
            if (edge.kind() == ExecutionTraceStore.EdgeKind.DATA_FROM) count++;
        }
        return count;
    }
}
