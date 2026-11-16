package com.example.myhelper.capability;

import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.orchestration.TaskPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

/** 固化适用端口只负责本次判断，不能因替换接口而改变短路、异常或输入快照。 */
class CapabilityUsePortTest {
    @Test
    void revokedCapabilityShortCircuitsInjectedBusinessGate() {
        SearchStub search = new SearchStub();
        search.available = false;
        GateStub gate = new GateStub();
        gate.failure = new IllegalStateException("不应执行已经短路的门禁");
        CapabilityUsePort use = new CheckedCapabilityUse(search, gate);
        CapabilityUsePort.UseDecision decision = use.evaluate(request(Map.of(), Map.of()));
        assertFalse(decision.usable());
        assertEquals(LlmTexts.CAPABILITY_NOT_ALLOWED, decision.reason());
        assertEquals(1, search.availabilityCalls);
        assertEquals(0, gate.allowCalls);
    }

    @Test
    void availableCapabilityCanStillBeRejectedByExternalGate() {
        SearchStub search = new SearchStub();
        GateStub gate = new GateStub();
        gate.allowed = false;
        CapabilityUsePort use = new CheckedCapabilityUse(search, gate);
        CapabilityUsePort.UseRequest request = request(Map.of("version", 3), Map.of("pendingRecovery", true));
        CapabilityUsePort.UseDecision decision = use.evaluate(request);
        assertFalse(decision.usable());
        assertEquals(LlmTexts.CAPABILITY_NOT_ALLOWED, decision.reason());
        assertEquals(request.goal(), gate.goal);
        assertSame(request.arguments(), gate.arguments);
        assertEquals(request.ref(), gate.ref);
        assertEquals(1, gate.allowCalls);
        gate.allowed = true;
        assertTrue(use.evaluate(request).usable());
    }

    @Test
    void queryAndGateFailuresRemainErrorsInsteadOfBecomingUseDecisions() {
        SearchStub search = new SearchStub();
        GateStub gate = new GateStub();
        CapabilityUsePort use = new CheckedCapabilityUse(search, gate);
        IllegalStateException searchFailure = new IllegalStateException("状态来源暂不可用");
        search.failure = searchFailure;
        assertSame(searchFailure, assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                use.evaluate(request(Map.of(), Map.of()));
            }
        }));
        assertEquals(0, gate.allowCalls);
        search.failure = null;
        IllegalStateException gateFailure = new IllegalStateException("门禁暂不可用");
        gate.failure = gateFailure;
        assertSame(gateFailure, assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                use.evaluate(request(Map.of(), Map.of()));
            }
        }));
    }

    @Test
    void externalPolicyReadsAnImmutableSnapshotOfArgumentsAndRecoveryContext() {
        Map<String, Object> nestedArguments = new LinkedHashMap<>();
        nestedArguments.put("version", 1);
        List<Object> recovery = new ArrayList<>(List.of("pending-change"));
        CapabilityUsePort.UseRequest request = request(Map.of("entity", nestedArguments), Map.of("recovery", recovery));
        nestedArguments.put("version", 2);
        recovery.clear();
        Map<?, ?> actualArguments = (Map<?, ?>) request.arguments().get("entity");
        List<?> actualRecovery = (List<?>) request.context().get("recovery");
        assertEquals(1, actualArguments.get("version"));
        assertEquals(List.of("pending-change"), actualRecovery);
        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            public void execute() {
                actualArguments.clear();
            }
        });
        CapabilityUsePort external = new PendingRecoveryPolicy();
        CapabilityUsePort.UseDecision decision = external.evaluate(request);
        assertFalse(decision.usable());
        assertEquals("先核对本次恢复事项", decision.reason());
    }

    @Test
    void contextSearchOverloadsKeepExistingImplementationsWorking() {
        SearchStub search = new SearchStub();
        CapabilityLookup.Query query = new CapabilityLookup.Query("run", 0, "$entry", "goal", Map.of());
        TaskPlan plan = new TaskPlan("goal", List.of(new TaskPlan.Step(
                "one", "read", TaskPlan.Kind.TOOL, "read", Map.of(), List.of(), Map.of())));
        search.searchAtEntry(query, Map.of("taskId", "task"));
        search.searchAfterPlanning("run", 1, plan, Map.of(), Map.of("taskId", "task"));
        assertEquals(1, search.entryCalls);
        assertEquals(1, search.planCalls);
    }

    private static CapabilityUsePort.UseRequest request(Map<String, Object> arguments, Map<String, Object> context) {
        return new CapabilityUsePort.UseRequest("task", "root", "run", "goal", arguments,
                new CapabilityLookup.Ref("test", "unit", 1), context);
    }

    private static final class PendingRecoveryPolicy implements CapabilityUsePort {
        @Override
        public UseDecision evaluate(UseRequest request) {
            List<?> pending = (List<?>) request.context().get("recovery");
            return new UseDecision(pending.isEmpty(), "先核对本次恢复事项");
        }
    }

    private static final class SearchStub implements CapabilitySearchPort {
        /** 当前模拟的权威状态。 */
        private boolean available = true;
        /** 需要透传的外部查询异常。 */
        private RuntimeException failure;
        /** 执行资格查询次数。 */
        private int availabilityCalls;
        /** 入口旧方法调用次数。 */
        private int entryCalls;
        /** 规划后旧方法调用次数。 */
        private int planCalls;

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public CapabilityLookup.Match searchAtEntry(CapabilityLookup.Query query) {
            entryCalls++;
            return new CapabilityLookup.Match(List.of(), Optional.empty());
        }

        @Override
        public CapabilityLookup.PlanMatches searchAfterPlanning(String runId, long revision, TaskPlan plan, Map<String, Object> input) {
            planCalls++;
            return new CapabilityLookup.PlanMatches(new CapabilityLookup.Match(List.of(), Optional.empty()), Map.of());
        }

        @Override
        public boolean isAvailable(CapabilityLookup.Ref ref) {
            availabilityCalls++;
            if (failure != null) {
                throw failure;
            }
            return available;
        }
    }

    private static final class GateStub implements AgentOrchestrator.Checks {
        /** 当前模拟的业务门禁结果。 */
        private boolean allowed = true;
        /** 门禁需要上抛的异常。 */
        private RuntimeException failure;
        /** 门禁被实际调用的次数。 */
        private int allowCalls;
        /** 收到的目标。 */
        private String goal;
        /** 收到的不可变参数。 */
        private Map<String, Object> arguments;
        /** 收到的固定能力引用。 */
        private CapabilityLookup.Ref ref;

        @Override
        public boolean allow(String goal, Map<String, Object> arguments, CapabilityLookup.Ref ref) {
            allowCalls++;
            this.goal = goal;
            this.arguments = arguments;
            this.ref = ref;
            if (failure != null) {
                throw failure;
            }
            return allowed;
        }

        @Override
        public boolean verify(String goal, Map<String, Object> arguments, Object output) {
            throw new AssertionError("适用性检查不能代替执行后验收");
        }
    }
}
