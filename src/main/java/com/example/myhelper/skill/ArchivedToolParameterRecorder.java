package com.example.myhelper.skill;

import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.records.RunArchivePort.ArchivedRun;
import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import com.example.myhelper.world.model.FailureCategory;
import com.example.myhelper.world.model.FailureCause;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 将实际派发、工具返回及验收事实合并成按执行身份去重的参数样本。 */
public final class ArchivedToolParameterRecorder {
    /** 工作区的统一存储适配。 */
    private final ToolParameterStore parameters;

    /** 与可靠度查询共用的批准范围选择。 */
    private final RecordedToolReliability approvedRanges;

    public ArchivedToolParameterRecorder(ToolParameterStore parameters, RecordedToolReliability.RangeCheck rangeCheck) {
        this.parameters = Objects.requireNonNull(parameters);
        this.approvedRanges = new RecordedToolReliability(parameters, rangeCheck);
    }

    public void record(ArchivedRun run) {
        if (run.internalValidation() || !run.graphComplete()) {
            return;
        }
        Map<String, ToolRef> bindings = new LinkedHashMap<>();
        Map<String, ExecutionEvent> returned = new LinkedHashMap<>();
        Map<String, ExecutionEvent> verified = new LinkedHashMap<>();
        for (ExecutionEvent event : run.events()) {
            switch (event.kind()) {
                case OBSERVED -> rememberBinding(event, bindings);
                case RETURNED -> {
                    if (!Boolean.FALSE.equals(event.details().get("dispatchStarted"))) {
                        returned.put(event.nodeId(), event);
                    }
                }
                case VERIFIED -> verified.put(event.nodeId(), event);
                default -> { }
            }
        }
        for (var entry : bindings.entrySet()) {
            ExecutionEvent result = returned.get(entry.getKey());
            if (result != null) {
                record(entry.getValue(), result, verified.get(entry.getKey()));
            }
        }
    }

    private void rememberBinding(ExecutionEvent event, Map<String, ToolRef> bindings) {
        if (!"TOOL_BINDING".equals(event.details().get("phase"))
                || !(event.details().get("evidence") instanceof Map<?, ?> binding)
                || !(binding.get("toolName") instanceof String toolName)
                || !(binding.get("sourceInstance") instanceof String sourceInstance)
                || !(binding.get("implementationVersion") instanceof String version)) {
            return;
        }
        // SpringToolExecutor 先于回调记录这一身份，工具自报信息不覆盖它。
        bindings.putIfAbsent(event.nodeId(), new ToolRef(toolName, sourceInstance, version));
    }

    private void record(ToolRef ref, ExecutionEvent returned, ExecutionEvent verified) {
        if (!ref.toolName().equals(returned.details().get("tool"))
                || !(returned.details().get("arguments") instanceof Map<?, ?> rawArgs)
                || !(returned.details().get("outcome") instanceof Map<?, ?> rawOutcome)) {
            throw new IllegalStateException("工具执行事实与派发绑定不一致");
        }
        if ("CANCELLED".equals(rawOutcome.get("status"))
                || "WAITING".equals(rawOutcome.get("status"))) {
            return;
        }
        Map<String, Object> arguments = arguments(rawArgs);
        ExecutionEvent evidence = verified == null ? returned : verified;
        Map<?, ?> verifiedOutcome = evidence.details().get("outcome") instanceof Map<?, ?> value ? value : Map.of();
        ToolParameterStore.Outcome outcome = toOutcome(rawOutcome, verified, verifiedOutcome);
        String error = error(verifiedOutcome, rawOutcome);
        FailureCause failure = null;
        if (outcome == ToolParameterStore.Outcome.FAILURE) {
            String reason = error == null || error.isBlank() ? "工具执行或验收失败，原因待核对" : error;
            // 现有归档只有错误描述时保留未知归因，不从自由文本推测为环境失败。
            failure = new FailureCause("unclassified-tool-failure", FailureCategory.UNKNOWN,
                    reason, null, 1, List.of());
        }
        String rangeId = parameters.exactRangeId(arguments);
        var execution = new ToolParameterStore.Execution(new ToolParameterStore.Scope(ref, rangeId),
                returned.rootRunId() + ":" + returned.nodeId(),
                returned.taskId() + ":" + returned.rootRunId() + ":" + evidence.eventId(),
                arguments, outcome, rawOutcome.get("value"), error, failure);
        if (parameters.record(execution).isEmpty()) {
            associateApprovedRange(execution);
        }
    }

    private void associateApprovedRange(ToolParameterStore.Execution execution) {
        if (execution.outcome() == ToolParameterStore.Outcome.UNKNOWN) {
            return;
        }
        var selection = approvedRanges.selectApprovedRange(execution.scope().tool(), execution.arguments());
        if (selection.rangeId().isPresent()) {
            parameters.associateApprovedRange(execution, selection.rangeId().get());
        }
    }

    private static ToolParameterStore.Outcome toOutcome(Map<?, ?> returned, ExecutionEvent verification,
                                                         Map<?, ?> verified) {
        if (verification != null && Boolean.TRUE.equals(verification.details().get("performed"))
                && "SUCCESS".equals(returned.get("status")) && "SUCCESS".equals(verified.get("status"))) {
            return ToolParameterStore.Outcome.SUCCESS;
        }
        if ("FAILED".equals(returned.get("status")) || "FAILED".equals(verified.get("status"))) {
            return ToolParameterStore.Outcome.FAILURE;
        }
        return ToolParameterStore.Outcome.UNKNOWN;
    }

    private static String error(Map<?, ?> evidence, Map<?, ?> returned) {
        if (evidence.get("error") instanceof String verdictError) {
            return verdictError;
        }
        return returned.get("error") instanceof String executionError ? executionError : null;
    }

    private static Map<String, Object> arguments(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String field)) {
                throw new IllegalStateException("工具执行事实包含非字符串参数名称");
            }
            result.put(field, entry.getValue());
        }
        return TaskPlan.immutableMap(result);
    }
}
