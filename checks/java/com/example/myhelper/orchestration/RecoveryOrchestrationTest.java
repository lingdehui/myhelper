package com.example.myhelper.orchestration;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.capability.CapabilityUsePort;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.records.TaskRecordPort.Disposition;
import com.example.myhelper.records.TaskRecordPort.EventKind;
import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.support.RecordingTaskRecords;
import com.example.myhelper.support.TestModelAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证循环与外部记录/审核的接线，不把进程内替身当成生产持久恢复。 */
class RecoveryOrchestrationTest {
    /** 模型只能提出保留修改的结论，不能凭 resolved 字段关闭问题。 */
    private static final Map<String, Object> KEEP_CHANGES = Map.of(
            "choice", "KEEP_CHANGES", "resolved", true, "reason", "已有 V2 修改仍有用，保留它并继续原目标");

    @Test
    void failedEntryCapabilityLetsModelKeepChangesAfterExternalApproval() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        records.approveReviews(true);
        EntryCapability lookup = new EntryCapability();
        WriteAction actions = new WriteAction();
        RecoveryThenFinish planner = new RecoveryThenFinish();
        try (Harness harness = new Harness(records, lookup, actions, planner, null)) {
            AgentOrchestrator.Handle handle = harness.submit();
            AgentOrchestrator.Outcome outcome = await(handle);
            assertEquals(AgentOrchestrator.Status.SUCCESS, outcome.status(), outcome.error());
            assertEquals(List.of(AgentOrchestrator.PlanningPhase.RECOVERY, AgentOrchestrator.PlanningPhase.NORMAL),
                    planner.phases());
            assertEquals(1, actions.calls.get());
            assertEquals(1, lookup.entryCalls.get());
            assertEquals(1, records.reviews(handle.taskId()).size());
            assertEquals(KEEP_CHANGES, records.reviews(handle.taskId()).getFirst().conclusion());
            assertTrue(records.recovery(handle.taskId()).pending().isEmpty());
            assertEquals(Disposition.CONTINUE, records.recovery(handle.taskId()).disposition());
            assertNull(records.activeClaim(handle.taskId()));
            for (AgentOrchestrator.PlanningContext context : planner.calls) {
                assertEquals(handle.taskId(), context.taskId());
                assertEquals("root", context.goal());
                assertEquals("sample.txt", context.arguments().get("target"));
            }
            assertWriteEvidence(records.events(handle.taskId()), handle.rootRunId());
        }
    }

    @Test
    void offlineModelLeavesPendingAndAnotherOrchestratorResumesSameTask() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        EntryCapability lookup = new EntryCapability();
        WriteAction actions = new WriteAction();
        AtomicInteger offlineCalls = new AtomicInteger();
        AgentOrchestrator.Planner offline = new AgentOrchestrator.Planner() {
            @Override
            public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                offlineCalls.incrementAndGet();
                assertEquals(AgentOrchestrator.PlanningPhase.RECOVERY, context.phase());
                // 模型请求失败之前，原有失败事实就已交给外部记录模块。
                assertFalse(records.recovery(context.taskId()).pending().isEmpty());
                throw new IllegalStateException("model-offline");
            }
        };
        AgentOrchestrator.Handle first;
        try (Harness firstHarness = new Harness(records, lookup, actions, offline, null)) {
            first = firstHarness.submit();
            assertEquals(AgentOrchestrator.Status.WAITING, await(first).status());
            assertEquals(1, offlineCalls.get());
            assertEquals(Disposition.WAIT, records.recovery(first.taskId()).disposition());
            assertFalse(records.recovery(first.taskId()).pending().isEmpty());
            for (TaskRecordPort.RecoveryItem item : records.recovery(first.taskId()).pending()) {
                assertEquals("WAITING_MODEL", item.state());
            }
            assertTrue(hasEvent(records.events(first.taskId()), EventKind.MODEL_FAILED, first.rootRunId()));
            assertNull(records.activeClaim(first.taskId()));
        }

        // 复用的是独立记录模块；新的循环实例没有上次运行的 Future 或内部 Map。
        records.approveReviews(true);
        RecoveryThenFinish available = new RecoveryThenFinish();
        try (Harness secondHarness = new Harness(records, lookup, actions, available, null)) {
            AgentOrchestrator.Handle second = secondHarness.orchestrator.resume(first.taskId());
            AgentOrchestrator.Outcome outcome = await(second);
            assertEquals(AgentOrchestrator.Status.SUCCESS, outcome.status(), outcome.error());
            assertEquals(first.taskId(), second.taskId());
            assertNotEquals(first.rootRunId(), second.rootRunId());
            assertEquals(1, records.tasks().size());
            // 恢复只处理已有影响并接续原目标，不再跑入口固化或重做写入。
            assertEquals(1, lookup.entryCalls.get());
            assertEquals(1, actions.calls.get());
            assertEquals(List.of(AgentOrchestrator.PlanningPhase.RECOVERY, AgentOrchestrator.PlanningPhase.NORMAL),
                    available.phases());
            assertTrue(records.recovery(second.taskId()).pending().isEmpty());
            assertTrue(hasEvent(records.events(second.taskId()), EventKind.RUN_ENDED, first.rootRunId()));
            assertTrue(hasEvent(records.events(second.taskId()), EventKind.RUN_ENDED, second.rootRunId()));
            assertNull(records.activeClaim(second.taskId()));
        }
    }

    @Test
    void modelClaimingResolvedCannotBypassExternalReview() throws Exception {
        // 替身默认拒绝审核；模型输出中的 resolved=true 不参与放行判断。
        RecordingTaskRecords records = new RecordingTaskRecords();
        EntryCapability lookup = new EntryCapability();
        WriteAction actions = new WriteAction();
        RecoveryThenFinish planner = new RecoveryThenFinish();
        try (Harness harness = new Harness(records, lookup, actions, planner, null)) {
            AgentOrchestrator.Handle handle = harness.submit();
            assertEquals(AgentOrchestrator.Status.WAITING, await(handle).status());
            assertEquals(List.of(AgentOrchestrator.PlanningPhase.RECOVERY), planner.phases());
            assertEquals(1, records.reviews(handle.taskId()).size());
            assertEquals(KEEP_CHANGES, records.reviews(handle.taskId()).getFirst().conclusion());
            assertFalse(records.recovery(handle.taskId()).pending().isEmpty());
            assertEquals(Disposition.WAIT, records.recovery(handle.taskId()).disposition());
            assertEquals(1, actions.calls.get());
            assertNull(records.activeClaim(handle.taskId()));
        }
    }

    @Test
    void rejectedIntentDoesNotDispatchTheTool() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        records.failAppend(EventKind.INTENT);
        EntryCapability lookup = new EntryCapability();
        WriteAction actions = new WriteAction();
        try (Harness harness = new Harness(records, lookup, actions, new NeedsInputPlanner(), null)) {
            AgentOrchestrator.Handle handle = harness.submit();
            assertNotEquals(AgentOrchestrator.Status.SUCCESS, await(handle).status());
            assertEquals(0, actions.calls.get());
            assertFalse(hasEvent(records.events(handle.taskId()), EventKind.INTENT, handle.rootRunId()));
            assertNull(records.activeClaim(handle.taskId()));
        }
    }

    @Test
    void externalCapabilityRejectionDoesNotExecuteItsTools() throws Exception {
        RecordingTaskRecords records = new RecordingTaskRecords();
        EntryCapability lookup = new EntryCapability();
        WriteAction actions = new WriteAction();
        AtomicReference<CapabilityUsePort.UseRequest> evaluated = new AtomicReference<>();
        CapabilityUsePort use = new CapabilityUsePort() {
            @Override
            public UseDecision evaluate(UseRequest request) {
                assertNull(evaluated.getAndSet(request));
                return new UseDecision(false, "本次状态不满足该固化的适用条件");
            }
        };
        try (Harness harness = new Harness(records, lookup, actions, new NeedsInputPlanner(), use)) {
            AgentOrchestrator.Handle handle = harness.submit();
            assertNotEquals(AgentOrchestrator.Status.SUCCESS, await(handle).status());
            assertEquals(0, actions.calls.get());
            CapabilityUsePort.UseRequest request = evaluated.get();
            assertNotNull(request);
            assertEquals(handle.taskId(), request.taskId());
            assertEquals(handle.rootRunId(), request.rootRunId());
            assertEquals(lookup.definition.ref(), request.ref());
            assertEquals("sample.txt", request.arguments().get("target"));
        }
    }

    private static AgentOrchestrator.Outcome await(AgentOrchestrator.Handle handle) throws Exception {
        return handle.result().get(10, TimeUnit.SECONDS);
    }

    private static boolean hasEvent(List<ExecutionEvent> events, EventKind kind, String rootRunId) {
        for (ExecutionEvent event : events) {
            if (event.kind() == kind && event.rootRunId().equals(rootRunId)) {
                return true;
            }
        }
        return false;
    }

    private static void assertWriteEvidence(List<ExecutionEvent> events, String rootRunId) {
        int intentIndex = -1;
        int returnedIndex = -1;
        int verifiedIndex = -1;
        String operation = null;
        boolean before = false;
        boolean after = false;
        for (int index = 0; index < events.size(); index++) {
            ExecutionEvent event = events.get(index);
            if (!rootRunId.equals(event.rootRunId())) {
                continue;
            }
            before = before || containsEntry(event.details(), "beforeRevision", "V1");
            after = after || containsEntry(event.details(), "afterRevision", "V2");
            if (event.kind() == EventKind.INTENT && intentIndex < 0) {
                intentIndex = index;
                operation = event.nodeId();
            }
            if (event.kind() == EventKind.RETURNED && operation != null && operation.equals(event.nodeId())) {
                returnedIndex = index;
                assertTrue(containsEntry(event.details(), "status", "SUCCESS"));
            }
            if (event.kind() == EventKind.VERIFIED && operation != null && operation.equals(event.nodeId())) {
                verifiedIndex = index;
                assertTrue(containsEntry(event.details(), "status", "FAILED"));
            }
        }
        assertNotNull(operation);
        assertTrue(intentIndex >= 0 && returnedIndex > intentIndex && verifiedIndex > returnedIndex,
                "同一工具动作须先确认 INTENT，再分别记录真实返回与验收失败");
        assertTrue(before, "执行前版本由工具上报并交外部记录");
        assertTrue(after, "实际修改版本由工具上报并交外部记录");
    }

    private static boolean containsEntry(Object value, String key, Object expected) {
        if (value instanceof Map<?, ?> map) {
            if (expected.equals(map.get(key))) {
                return true;
            }
            for (Object child : map.values()) {
                if (containsEntry(child, key, expected)) {
                    return true;
                }
            }
        } else if (value instanceof List<?> list) {
            for (Object child : list) {
                if (containsEntry(child, key, expected)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 入口确定命中固定能力；恢复时不允许再次入口匹配。 */
    private static final class EntryCapability implements CapabilitySearchPort {
        /** 检查根入口总共查询几次。 */
        private final AtomicInteger entryCalls = new AtomicInteger();
        /** 只含一个可观测写入动作的能力。 */
        private final CapabilityLookup.Definition definition = new CapabilityLookup.Definition(
                new CapabilityLookup.Ref("checks", "write-capability", 1),
                new TaskPlan("write-capability", List.of(new TaskPlan.Step("write", "write", TaskPlan.Kind.TOOL,
                        "write", Map.of("target", "sample.txt"), List.of(), Map.of()))));

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public CapabilityLookup.Match searchAtEntry(CapabilityLookup.Query query) {
            entryCalls.incrementAndGet();
            return new CapabilityLookup.Match(List.of(), Optional.of(definition));
        }

        @Override
        public CapabilityLookup.PlanMatches searchAfterPlanning(String runId, long revision, TaskPlan plan,
                                                               Map<String, Object> input) {
            throw new AssertionError("本检查模型只提出保留修改或完成，不提交新执行计划");
        }

        @Override
        public boolean isAvailable(CapabilityLookup.Ref ref) {
            return definition.ref().equals(ref);
        }
    }

    /** 模拟工具实际写入成功；行为验收在外部 Checks 中明确失败。 */
    private static final class WriteAction implements AgentOrchestrator.ActionExecutor {
        /** 用于证明恢复没有盲目重复写入或强制撤销。 */
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args,
                                                 AgentOrchestrator.CallContext context) {
            assertEquals("write", tool);
            calls.incrementAndGet();
            context.reportBefore(Map.of("target", "sample.txt", "beforeRevision", "V1"));
            context.reportAfter(Map.of("target", "sample.txt", "afterRevision", "V2", "receipt", "write-1"));
            return new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS,
                    Map.of("target", "sample.txt", "version", "V2"), null);
        }
    }

    /** 模型判断保留既有产物；外部审核允许后，才会收到 NORMAL 继续原目标。 */
    private static final class RecoveryThenFinish implements AgentOrchestrator.Planner {
        /** 检查请求次序与上下文，不在生产模块维护这份列表。 */
        private final List<AgentOrchestrator.PlanningContext> calls = new CopyOnWriteArrayList<>();

        @Override
        public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
            calls.add(context);
            if (context.phase() == AgentOrchestrator.PlanningPhase.RECOVERY) {
                assertEquals(Disposition.CONSULT_MODEL, context.recovery().disposition());
                assertFalse(context.recovery().pending().isEmpty());
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, KEEP_CHANGES);
            }
            assertTrue(context.recovery().pending().isEmpty());
            return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, "goal-complete");
        }

        private List<AgentOrchestrator.PlanningPhase> phases() {
            List<AgentOrchestrator.PlanningPhase> phases = new ArrayList<>();
            for (AgentOrchestrator.PlanningContext context : calls) {
                phases.add(context.phase());
            }
            return List.copyOf(phases);
        }
    }

    /** 拒绝派发场景无需继续修复，仅提供一个明确停止的模型响应。 */
    private static final class NeedsInputPlanner implements AgentOrchestrator.Planner {
        @Override
        public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
            return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.NEEDS_INPUT, null, "需要外部确认");
        }
    }

    /** 每个实例只有自己的调度资源，记录替身从外部传入并可跨实例复用。 */
    private static final class Harness implements AutoCloseable {
        /** 工具、模型和外部查询使用的工作线程。 */
        private final ExecutorService workers = Executors.newFixedThreadPool(3);
        /** 单独处理超时。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 本次循环实例的执行视图，不承担跨实例恢复。 */
        private final ExecutionTraceStore traces = new ExecutionTraceStore(3, 500, 4000);
        /** 被测的真实循环组件。 */
        private final AgentOrchestrator orchestrator;

        private Harness(RecordingTaskRecords records, CapabilitySearchPort lookup,
                        AgentOrchestrator.ActionExecutor actions, AgentOrchestrator.Planner planner,
                        CapabilityUsePort capabilityUse) {
            AgentOrchestrator.Checks checks = new AgentOrchestrator.Checks() {
                @Override
                public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) {
                    return true;
                }

                @Override
                public boolean verify(String goal, Map<String, Object> args, Object output) {
                    return !"write".equals(goal);
                }
            };
            AgentOrchestrator.Limits limits = new AgentOrchestrator.Limits(6, 12, 40, 20, 100, 10, Duration.ofSeconds(15));
            TestModelAdapter modelAdapter = new TestModelAdapter(List.of("A", "B"));
            CapabilityUsePort use = capabilityUse == null
                    ? new com.example.myhelper.capability.CheckedCapabilityUse(lookup, checks) : capabilityUse;
            orchestrator = new AgentOrchestrator(planner, lookup, com.example.myhelper.support.ReliableTools.bind(actions),
                    checks, traces, workers, timer,
                    limits, modelAdapter, records, use, com.example.myhelper.support.ReliableTools.gate());
        }

        private AgentOrchestrator.Handle submit() {
            return orchestrator.submit(new AgentOrchestrator.Request("root", Map.of("target", "sample.txt"),
                    "A", List.of("write")));
        }

        @Override
        public void close() {
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }
}
