package com.example.myhelper.orchestration;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.gate.RuntimeGate;
import com.example.myhelper.support.TestModelAdapter;
import com.example.myhelper.tools.ToolDirectory;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeGateDispatchTest {
    /** 固定于宿主绑定的工具身份。 */
    private static final ToolRef SENSOR = new ToolRef("test_sensor", "fixture-instance", "v2");
    /** 实际工具参数与回执。 */
    private static final Map<String, Object> READING = Map.of("power", true);

    @Test
    void toolAndModelAcceptanceUseTheActualBindingAndRunContext() throws Exception {
        try (Harness harness = new Harness(false)) {
            AgentOrchestrator.Handle handle = harness.submit();
            assertEquals(new AgentOrchestrator.Outcome(AgentOrchestrator.Status.SUCCESS, READING, null),
                    handle.settled().get(5, TimeUnit.SECONDS));
            GateProbe probe = harness.probe;
            assertEquals(List.of("allow", "resolve-resource", "dispatch-gate", "callback", "verify-tool",
                    "verify-completion"), probe.events);
            assertEquals(SENSOR, probe.allowedCall.toolRef());
            assertEquals(READING, probe.allowedCall.args());
            assertEquals(handle.taskId(), probe.allowedCall.taskId());
            assertEquals(handle.rootRunId(), probe.allowedCall.rootRunId());
            assertEquals(probe.allowedCall, probe.dispatchCall);
            assertEquals(probe.allowedCall, probe.verifiedCall);
            assertEquals(probe.allowedCall.operationId(), probe.callbackOperationId);
            assertEquals(READING, probe.verifiedOutput);
            assertEquals(handle.taskId(), probe.completion.taskId());
            assertEquals(handle.rootRunId(), probe.completion.rootRunId());
            assertEquals(probe.allowedCall.runId(), probe.completion.runId());
            assertNotEquals(probe.allowedCall.operationId(), probe.completion.operationId());
        }
    }

    @Test
    void permissionRevokedDuringResourceResolutionPreventsCallbackAndToolAcceptance() throws Exception {
        try (Harness harness = new Harness(true)) {
            assertEquals(AgentOrchestrator.Status.NEEDS_INPUT, harness.submit().settled().get(5, TimeUnit.SECONDS).status());
            GateProbe probe = harness.probe;
            assertEquals(List.of("allow", "resolve-resource", "dispatch-gate"), probe.events);
            assertEquals(SENSOR, probe.dispatchCall.toolRef());
            assertEquals(probe.allowedCall, probe.dispatchCall);
            assertNull(probe.callbackOperationId);
            assertNull(probe.verifiedCall);
            assertEquals(AgentOrchestrator.Status.FAILED, harness.planner.toolOutcome.status());
        }
    }

    private static final class Harness implements AutoCloseable {
        /** 为本次运行提供独立的工作线程。 */
        private final ExecutorService workers = Executors.newSingleThreadExecutor();
        /** 为本次运行提供独立的超时计时器。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 在真实编排与执行器接点观察调用。 */
        private final GateProbe probe;
        /** 只使用本地模型决策。 */
        private final PlannerStub planner = new PlannerStub();
        /** 使用实际资源锁、工具绑定和运行门禁的编排器。 */
        private final AgentOrchestrator runtime;

        private Harness(boolean revokeDuringResourceResolution) {
            probe = new GateProbe(revokeDuringResourceResolution);
            var executor = SpringToolExecutor.withBindings(List.of(new ToolDirectory.Binding(SENSOR, probe)), probe, 1);
            runtime = new AgentOrchestrator(planner, CapabilitySearchPort.disabled(), executor, probe,
                    new ExecutionTraceStore(10, 1000, 10000), workers, timer,
                    new AgentOrchestrator.Limits(3, 2, 4, 4, 4, 4, Duration.ofSeconds(10)),
                    new TestModelAdapter(List.of("model")));
        }

        private AgentOrchestrator.Handle submit() {
            return runtime.submit(new AgentOrchestrator.Request("查电源", Map.of(), "model", List.of(SENSOR.toolName())));
        }

        @Override public void close() {
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }

    private static final class PlannerStub implements AgentOrchestrator.Planner {
        /** 单步工具结果供区分已执行和被拒绝。 */
        private AgentOrchestrator.Outcome toolOutcome;

        @Override public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
            if (context.history().isEmpty()) {
                var step = new TaskPlan.Step("read", "读取电源", TaskPlan.Kind.TOOL, SENSOR.toolName(),
                        READING, List.of(), Map.of());
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN,
                        new TaskPlan("查电源", List.of(step)), null);
            }
            toolOutcome = context.history().getLast().outcome();
            if (toolOutcome.status() == AgentOrchestrator.Status.SUCCESS) {
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, READING);
            }
            return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.NEEDS_INPUT, null, "需要设备授权");
        }
    }

    private static final class GateProbe implements RuntimeGate, ToolCallback, SpringToolExecutor.ResourceResolver {
        /** 各接点的实际调用顺序。 */
        private final List<String> events = new CopyOnWriteArrayList<>();
        /** 是否在首次门禁判断后撤销工具权限。 */
        private final boolean revokeDuringResourceResolution;
        /** 当前工具派发许可。 */
        private boolean dispatchAllowed = true;
        /** 首次资格检查收到的实际绑定。 */
        private ToolCall allowedCall;
        /** 实际派发前检查收到的绑定。 */
        private ToolCall dispatchCall;
        /** 工具真实回调收到的操作编号。 */
        private Object callbackOperationId;
        /** 工具验收收到的绑定。 */
        private ToolCall verifiedCall;
        /** 工具验收收到的实际回包。 */
        private Object verifiedOutput;
        /** 模型完成验收使用的上下文。 */
        private CompletionContext completion;

        private GateProbe(boolean revokeDuringResourceResolution) {
            this.revokeDuringResourceResolution = revokeDuringResourceResolution;
        }

        @Override public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) {
            throw new AssertionError("固定版本工具门禁必须接收实际绑定");
        }

        @Override public boolean verify(String goal, Map<String, Object> args, Object output) {
            throw new AssertionError("运行验收必须接收实际调用上下文");
        }

        @Override public boolean allowTool(ToolCall call) {
            events.add("allow");
            allowedCall = call;
            return true;
        }

        @Override public List<String> keys(String tool, Map<String, Object> args) {
            events.add("resolve-resource");
            dispatchAllowed = !revokeDuringResourceResolution;
            return List.of("test-device");
        }

        @Override public boolean beforeToolDispatch(ToolCall call) {
            events.add("dispatch-gate");
            dispatchCall = call;
            return dispatchAllowed;
        }

        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(SENSOR.toolName()).description("检查用感知工具").inputSchema("{}").build();
        }

        @Override public String call(String input) {
            throw new AssertionError("工具需要本次调用的可信上下文");
        }

        @Override public String call(String input, ToolContext toolContext) {
            events.add("callback");
            callbackOperationId = toolContext.getContext().get("operationId");
            return input;
        }

        @Override public boolean verifyTool(ToolCall call, Object output, Map<String, Object> instructions) {
            events.add("verify-tool");
            verifiedCall = call;
            verifiedOutput = output;
            return READING.equals(output);
        }

        @Override public boolean verifyCompletion(CompletionContext context, Object output, Map<String, Object> instructions) {
            events.add("verify-completion");
            completion = context;
            return READING.equals(output);
        }
    }
}
