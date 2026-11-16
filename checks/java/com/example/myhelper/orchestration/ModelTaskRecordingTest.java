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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 模型任务事实必须保留每次关联及调用身份，不能把分配、响应和任务成功混为一项。 */
class ModelTaskRecordingTest {
    /** 同一个配置可被多条分支继承，仍必须分别记录任务。 */
    private static final String SHARED_MODEL = "provider/model/account-one";
    /** 根参数用于检查事件保存了本任务数据，没有混入某个子任务参数。 */
    private static final Map<String, Object> ROOT_ARGUMENTS = Map.of("target", "demo-project");

    @Test
    void rootAndChildTasksSharingAModelKeepSeparateCallIdentities() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        AtomicInteger calls = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                calls.incrementAndGet();
                if ("root".equals(context.goal()) && context.history().isEmpty()) {
                    return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN,
                            new TaskPlan("two-independent-tasks", List.of(
                                    taskStep("ha", "manage-ha", "home"),
                                    taskStep("data", "process-data", "report"))), null);
                }
                return complete(context.goal());
            }
        };
        try (Harness harness = new Harness(records, planner, 20, true, CapabilitySearchPort.disabled())) {
            AgentOrchestrator.Handle handle = harness.submit();
            assertEquals(AgentOrchestrator.Status.SUCCESS, await(handle).status());
            assertEquals(4, calls.get());
            List<ExecutionEvent> events = records.events(handle.taskId());
            List<ExecutionEvent> assigned = select(events, EventKind.MODEL_TASK_ASSIGNED);
            assertEquals(4, assigned.size());
            assertEquals(4, select(events, EventKind.MODEL_CALL_STARTED).size());
            assertEquals(4, select(events, EventKind.MODEL_CALL_RETURNED).size());
            Set<String> callIds = new HashSet<>();
            Set<String> childBranches = new HashSet<>();
            Set<String> childRuns = new HashSet<>();
            Set<String> childGoals = new HashSet<>();
            String rootRun = null;
            int rootAssociations = 0;
            for (ExecutionEvent association : assigned) {
                assertTrue(callIds.add(association.nodeId()), "不同调用必须保留不同 MODEL 节点身份");
                assertEquals(handle.taskId(), association.taskId());
                assertEquals(handle.rootRunId(), association.rootRunId());
                assertEquals(SHARED_MODEL, association.details().get("modelRef"));
                assertEquals(List.of("write"), association.details().get("toolNames"));
                assertEquals("NORMAL", association.details().get("phase"));
                assertFalse(association.details().containsKey("labels"), "记录接线不能自行伪造任务标签");
                String goal = (String) association.details().get("goal");
                if ("root".equals(goal)) {
                    rootAssociations++;
                    if (rootRun == null) {
                        rootRun = association.runId();
                    }
                    assertEquals(rootRun, association.runId());
                    assertEquals(handle.rootRunId(), association.branchId());
                    assertEquals("", association.details().get("parentBranchId"));
                    assertEquals("ROOT", association.details().get("modelAssignment"));
                    assertEquals(ROOT_ARGUMENTS, association.details().get("arguments"));
                } else {
                    childBranches.add(association.branchId());
                    childRuns.add(association.runId());
                    childGoals.add(goal);
                    assertEquals(handle.rootRunId(), association.details().get("parentBranchId"));
                    assertEquals("INHERITED", association.details().get("modelAssignment"));
                    Map<String, Object> expected = "manage-ha".equals(goal)
                            ? Map.of("target", "home") : Map.of("target", "report");
                    assertEquals(expected, association.details().get("arguments"));
                }
                assertNormalCallEvents(events, association);
            }
            assertEquals(2, rootAssociations);
            assertEquals(2, childBranches.size());
            assertEquals(2, childRuns.size());
            assertEquals(Set.of("manage-ha", "process-data"), childGoals);
            assertFalse(childRuns.contains(rootRun));
        }
    }

    @Test
    void repeatedPlanningKeepsEveryRoundInsteadOfOverwritingTheModelTaskPair() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        AtomicInteger calls = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                int round = calls.incrementAndGet();
                if (round < 3) {
                    return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN, writePlan(round), null);
                }
                return complete("finished-after-two-plans");
            }
        };
        try (Harness harness = new Harness(records, planner, 20, true, CapabilitySearchPort.disabled())) {
            AgentOrchestrator.Handle handle = harness.submit();
            assertEquals(AgentOrchestrator.Status.SUCCESS, await(handle).status());
            List<ExecutionEvent> events = records.events(handle.taskId());
            List<ExecutionEvent> assigned = select(events, EventKind.MODEL_TASK_ASSIGNED);
            assertEquals(3, assigned.size());
            Set<String> callIds = new HashSet<>();
            for (int index = 0; index < assigned.size(); index++) {
                ExecutionEvent event = assigned.get(index);
                assertTrue(callIds.add(event.nodeId()));
                assertEquals(index + 1L, event.planRevision());
                assertEquals(index + 1L, number(event.details().get("round")));
                assertEquals(assigned.getFirst().runId(), event.runId());
                assertEquals(assigned.getFirst().branchId(), event.branchId());
                assertEquals(ROOT_ARGUMENTS, event.details().get("arguments"));
                assertNormalCallEvents(events, event);
            }
            List<ExecutionEvent> returned = select(events, EventKind.MODEL_CALL_RETURNED);
            assertEquals("PLAN", returned.get(0).details().get("decisionKind"));
            assertEquals("PLAN", returned.get(1).details().get("decisionKind"));
            assertEquals("COMPLETE", returned.get(2).details().get("decisionKind"));
            assertEquals(2, harness.toolCalls.get());
        }
    }

    @Test
    void refusingAssignmentOrStartRecordPreventsTheModelRequest() throws Exception {
        for (EventKind rejected : List.of(EventKind.MODEL_TASK_ASSIGNED, EventKind.MODEL_CALL_STARTED)) {
            RecordingTaskRecords records = new RecordingTaskRecords();
            records.failAppend(rejected);
            AtomicInteger calls = new AtomicInteger();
            AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
                @Override
                public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                    calls.incrementAndGet();
                    return complete("should-not-be-called");
                }
            };
            try (Harness harness = new Harness(records, planner, 20, true, CapabilitySearchPort.disabled())) {
                AgentOrchestrator.Handle handle = harness.submit();
                AgentOrchestrator.Outcome outcome = await(handle);
                assertNotEquals(AgentOrchestrator.Status.SUCCESS, outcome.status());
                assertEquals(0, calls.get(), "记录未被接受前不能进入模型: " + rejected);
                List<ExecutionEvent> events = records.events(handle.taskId());
                int expectedAssignments = rejected == EventKind.MODEL_TASK_ASSIGNED ? 0 : 1;
                assertEquals(expectedAssignments, select(events, EventKind.MODEL_TASK_ASSIGNED).size());
                assertTrue(select(events, EventKind.MODEL_CALL_STARTED).isEmpty());
                assertTrue(select(events, EventKind.MODEL_CALL_RETURNED).isEmpty());
                assertTrue(select(events, EventKind.MODEL_FAILED).isEmpty());
                assertNull(records.activeClaim(handle.taskId()));
            }
        }
    }

    @Test
    void exhaustedBudgetRecordsAssociationWithoutInventingAStartedCall() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        AtomicInteger calls = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                calls.incrementAndGet();
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN, writePlan(1), null);
            }
        };
        try (Harness harness = new Harness(records, planner, 1, true, CapabilitySearchPort.disabled())) {
            AgentOrchestrator.Handle handle = harness.submit();
            AgentOrchestrator.Outcome outcome = await(handle);
            assertEquals(AgentOrchestrator.Status.FAILED, outcome.status());
            assertTrue(outcome.error().contains(LlmTexts.MODEL_CALL_LIMIT_REACHED));
            assertEquals(1, calls.get());
            List<ExecutionEvent> events = records.events(handle.taskId());
            List<ExecutionEvent> assigned = select(events, EventKind.MODEL_TASK_ASSIGNED);
            assertEquals(2, assigned.size());
            assertNormalCallEvents(events, assigned.getFirst());
            List<ExecutionEvent> deniedCall = modelEventsFor(events, assigned.getLast().nodeId());
            assertEquals(1, deniedCall.size());
            assertEquals(EventKind.MODEL_TASK_ASSIGNED, deniedCall.getFirst().kind());
            assertTrue(select(events, EventKind.MODEL_FAILED).isEmpty());
        }
    }

    @Test
    void normalModelReturnDoesNotDeclareTheTaskVerified() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                return complete("model-claims-done");
            }
        };
        try (Harness harness = new Harness(records, planner, 20, false, CapabilitySearchPort.disabled())) {
            AgentOrchestrator.Handle handle = harness.submit();
            // 普通 COMPLETE 验收失败，下一轮恢复提案被外部拒绝，原问题仍待处理。
            assertEquals(AgentOrchestrator.Status.WAITING, await(handle).status());
            List<ExecutionEvent> events = records.events(handle.taskId());
            List<ExecutionEvent> returned = select(events, EventKind.MODEL_CALL_RETURNED);
            assertEquals(2, returned.size());
            assertEquals("NORMAL", returned.getFirst().details().get("phase"));
            assertEquals("RECOVERY", returned.getLast().details().get("phase"));
            for (ExecutionEvent response : returned) {
                assertEquals("COMPLETE", response.details().get("decisionKind"));
            }
            List<ExecutionEvent> verified = select(events, EventKind.VERIFIED);
            assertEquals(1, verified.size());
            Map<?, ?> outcome = assertInstanceOf(Map.class, verified.getFirst().details().get("outcome"));
            assertEquals("FAILED", outcome.get("status"));
            assertFalse(records.recovery(handle.taskId()).pending().isEmpty());
        }
    }

    @Test
    void failedModelRequestRetainsTheSameCallAndTaskIdentity() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                throw new IllegalStateException("supplier-unavailable");
            }
        };
        try (Harness harness = new Harness(records, planner, 20, true, CapabilitySearchPort.disabled())) {
            AgentOrchestrator.Handle handle = harness.submit();
            assertEquals(AgentOrchestrator.Status.WAITING, await(handle).status());
            List<ExecutionEvent> events = records.events(handle.taskId());
            ExecutionEvent assigned = only(events, EventKind.MODEL_TASK_ASSIGNED);
            ExecutionEvent started = only(events, EventKind.MODEL_CALL_STARTED);
            ExecutionEvent failed = only(events, EventKind.MODEL_FAILED);
            assertSameCall(assigned, started);
            assertSameCall(assigned, failed);
            assertEquals(SHARED_MODEL, failed.details().get("modelRef"));
            assertEquals("root", failed.details().get("goal"));
            assertTrue(number(failed.details().get("elapsedNanos")) >= 0);
            assertTrue(failed.details().get("error").toString().contains("supplier-unavailable"));
            assertTrue(select(events, EventKind.MODEL_CALL_RETURNED).isEmpty());
        }
    }

    @Test
    void pureToolCapabilityDoesNotCreateModelUsageSamples() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        AtomicInteger calls = new AtomicInteger();
        AgentOrchestrator.Planner planner = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                calls.incrementAndGet();
                return complete("unexpected-model");
            }
        };
        try (Harness harness = new Harness(records, planner, 20, true, new EntryCapability())) {
            AgentOrchestrator.Handle handle = harness.submit();
            assertEquals(AgentOrchestrator.Status.SUCCESS, await(handle).status());
            assertEquals(0, calls.get());
            assertEquals(0, harness.models.openCalls());
            assertEquals(1, harness.toolCalls.get());
            for (ExecutionEvent event : records.events(handle.taskId())) {
                assertFalse(isModelEvent(event.kind()), "纯工具不能伪造模型使用事实: " + event.kind());
            }
        }
    }

    private static TaskPlan.Step taskStep(String id, String goal, String target) {
        return new TaskPlan.Step(id, goal, TaskPlan.Kind.TASK, null,
                Map.of("target", target), List.of(), Map.of());
    }

    private static TaskPlan writePlan(int round) {
        return new TaskPlan("write-round-" + round, List.of(new TaskPlan.Step("write", "write", TaskPlan.Kind.TOOL,
                "write", Map.of("round", round), List.of(), Map.of())));
    }

    private static AgentOrchestrator.Decision complete(Object value) {
        return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, value);
    }

    private static AgentOrchestrator.Outcome await(AgentOrchestrator.Handle handle) throws Exception {
        return handle.result().get(10, TimeUnit.SECONDS);
    }

    private static long number(Object value) {
        return assertInstanceOf(Number.class, value).longValue();
    }

    private static List<ExecutionEvent> select(List<ExecutionEvent> events, EventKind kind) {
        List<ExecutionEvent> result = new ArrayList<>();
        for (ExecutionEvent event : events) {
            if (event.kind() == kind) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }

    private static ExecutionEvent only(List<ExecutionEvent> events, EventKind kind) {
        List<ExecutionEvent> selected = select(events, kind);
        assertEquals(1, selected.size(), kind.name());
        return selected.getFirst();
    }

    private static List<ExecutionEvent> modelEventsFor(List<ExecutionEvent> events, String nodeId) {
        List<ExecutionEvent> result = new ArrayList<>();
        for (ExecutionEvent event : events) {
            if (nodeId.equals(event.nodeId()) && isModelEvent(event.kind())) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }

    private static boolean isModelEvent(EventKind kind) {
        return switch (kind) {
            case MODEL_TASK_ASSIGNED, MODEL_CALL_STARTED, MODEL_CALL_RETURNED, MODEL_FAILED -> true;
            default -> false;
        };
    }

    private static void assertSameCall(ExecutionEvent assigned, ExecutionEvent actual) {
        assertEquals(assigned.nodeId(), actual.nodeId());
        assertEquals(assigned.runId(), actual.runId());
        assertEquals(assigned.branchId(), actual.branchId());
        assertEquals(assigned.taskId(), actual.taskId());
        assertEquals(assigned.rootRunId(), actual.rootRunId());
        assertEquals(assigned.planRevision(), actual.planRevision());
    }

    private static void assertNormalCallEvents(List<ExecutionEvent> events, ExecutionEvent assigned) {
        List<ExecutionEvent> call = modelEventsFor(events, assigned.nodeId());
        assertEquals(3, call.size());
        assertEquals(EventKind.MODEL_TASK_ASSIGNED, call.get(0).kind());
        assertEquals(EventKind.MODEL_CALL_STARTED, call.get(1).kind());
        assertEquals(EventKind.MODEL_CALL_RETURNED, call.get(2).kind());
        for (ExecutionEvent event : call) {
            assertSameCall(assigned, event);
            for (String field : List.of("goal", "arguments", "toolNames", "modelRef", "parentBranchId",
                    "modelAssignment", "phase", "round")) {
                assertTrue(assigned.details().containsKey(field), "分配事件必须具有字段: " + field);
                assertEquals(assigned.details().get(field), event.details().get(field), field);
            }
        }
        Instant.parse((String) call.get(1).details().get("startedAt"));
        Instant.parse((String) call.get(2).details().get("completedAt"));
        assertTrue(number(call.get(2).details().get("elapsedNanos")) >= 0);
    }

    /** 入口直接命中纯工具固化，不为记录采样强行调用模型。 */
    private static final class EntryCapability implements CapabilitySearchPort {
        /** 固定定义仅用于检查，实际执行继续使用生产编排器。 */
        private final CapabilityLookup.Definition definition = new CapabilityLookup.Definition(
                new CapabilityLookup.Ref("checks", "direct-write", 1), writePlan(1));

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
            throw new AssertionError("入口纯工具固化不应触发模型计划后的检索");
        }

        @Override
        public boolean isAvailable(CapabilityLookup.Ref ref) {
            return definition.ref().equals(ref);
        }
    }

    /** 仅检查用的装配，外部记录和模型均为替身。 */
    private static final class Harness implements AutoCloseable {
        /** 允许独立子任务同时进入同一个模型引用。 */
        private final ExecutorService workers = Executors.newFixedThreadPool(4);
        /** 真实计时器保证被测运行按正常生命周期结束。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 唯一模型使两个子分支继承同一个连接，检验记录没有按模型键覆盖。 */
        private final TestModelAdapter models = new TestModelAdapter(List.of(SHARED_MODEL));
        /** 已实际派发的工具次数。 */
        private final AtomicInteger toolCalls = new AtomicInteger();
        /** 负责本次调用的真实循环。 */
        private final AgentOrchestrator orchestrator;

        private Harness(RecordingTaskRecords records, AgentOrchestrator.Planner planner, int modelBudget,
                        boolean verify, CapabilitySearchPort lookup) {
            AgentOrchestrator.ActionExecutor actions = new AgentOrchestrator.ActionExecutor() {
                @Override
                public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args,
                                                         AgentOrchestrator.CallContext context) {
                    toolCalls.incrementAndGet();
                    return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, args, null);
                }
            };
            AgentOrchestrator.Checks checks = new AgentOrchestrator.Checks() {
                @Override
                public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) {
                    return true;
                }

                @Override
                public boolean verify(String goal, Map<String, Object> args, Object output) {
                    return verify;
                }
            };
            AgentOrchestrator.Limits limits = new AgentOrchestrator.Limits(8, 10, 40, 20,
                    modelBudget, 10, Duration.ofSeconds(15));
            orchestrator = new AgentOrchestrator(planner, lookup, com.example.myhelper.support.ReliableTools.bind(actions), checks,
                    new ExecutionTraceStore(3, 500, 4000), workers, timer, limits, models, records,
                    new com.example.myhelper.capability.CheckedCapabilityUse(lookup, checks),
                    com.example.myhelper.support.ReliableTools.gate());
        }

        private AgentOrchestrator.Handle submit() {
            return orchestrator.submit(new AgentOrchestrator.Request("root", ROOT_ARGUMENTS,
                    SHARED_MODEL, List.of("write")));
        }

        @Override
        public void close() {
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }
}
