package com.example.myhelper.records;

import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.records.TaskRecordPort.EventKind;
import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.records.TaskRecordPort.RecoveryItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具重放的独立纯判断策略。实现只能读取上下文并返回决定，
 * 不得调用模型、数据库、网络或产生其他外部副作用。
 * 仓储可能在 CAS 竞争或未知回包确认时重复调用，决定须只依赖本次上下文。
 */
public interface ToolReplayPolicy {
    /** 返回当前事实下是否允许登记新的工具意图，不修改任何待核对事项。 */
    Decision check(Context context);

    /** 默认只保护未知、未返回和未验收的相同动作。 */
    static ToolReplayPolicy safeDefault() {
        return new DefaultToolReplayPolicy();
    }

    record Context(
            /** 即将登记的新 INTENT；尚未派发实际工具。 */
            ExecutionEvent requested,
            /** 当前仍未由独立审核关闭的事项。 */
            List<RecoveryItem> pending,
            /** 当前任务的全部已提交事实，保持提交顺序。 */
            List<ExecutionEvent> events) {
        public Context {
            Objects.requireNonNull(requested);
            pending = List.copyOf(pending);
            events = List.copyOf(events);
        }
    }

    record Decision(
            /** true 表示允许登记意图，不表示工具执行已通过验收。 */
            boolean allowed,
            /** 拒绝时交回调用方的原因；允许时可为空。 */
            String reason) {
        public Decision {
            if (!allowed) {
                TaskPlan.requireText(reason, "replayReason");
            }
        }
    }
}

/** 默认重放规则与仓储分离；明确 FAILED 仍待核对，但不据此禁止所有同参重试。 */
final class DefaultToolReplayPolicy implements ToolReplayPolicy {
    /** 仅用于规范化比较参数，处理 JSON 整数往返及对象键顺序。 */
    private final RecordCodec codec = new RecordCodec();

    @Override
    public Decision check(Context context) {
        ExecutionEvent requested = context.requested();
        Object tool = requested.details().get("tool");
        if (tool == null || !requested.details().containsKey("arguments")) {
            return new Decision(true, null);
        }
        Map<String, ExecutionEvent> eventsById = indexEvents(context.events());
        for (RecoveryItem incident : context.pending()) {
            ExecutionEvent source = eventsById.get(incident.evidence().get("eventId"));
            if (source == null || source.nodeId() == null || !requiresReconciliation(incident, source)) {
                continue;
            }
            if (containsMatchingIntent(context.events(), source, requested)) {
                return new Decision(false, LlmTexts.RECORDS_UNCONFIRMED_EFFECT);
            }
        }
        return new Decision(true, null);
    }

    private Map<String, ExecutionEvent> indexEvents(List<ExecutionEvent> events) {
        Map<String, ExecutionEvent> result = new LinkedHashMap<>();
        for (ExecutionEvent event : events) {
            result.put(event.eventId(), event);
        }
        return result;
    }

    private boolean requiresReconciliation(RecoveryItem incident, ExecutionEvent source) {
        if (Boolean.FALSE.equals(source.details().get("dispatchStarted"))) {
            return false;
        }
        return switch (incident.state()) {
            case RecordState.UNCONFIRMED, RecordState.UNVERIFIED, RecordState.UNKNOWN -> true;
            // 兼容现有持久 OPEN：事实明确 UNKNOWN 才套用默认重放保护。
            case RecordState.OPEN -> hasUnknownOutcome(source);
            default -> false;
        };
    }

    private boolean hasUnknownOutcome(ExecutionEvent source) {
        Object outcome = source.details().get("outcome");
        return outcome instanceof Map<?, ?> value && RecordState.UNKNOWN.equals(value.get("status"));
    }

    private boolean containsMatchingIntent(List<ExecutionEvent> events, ExecutionEvent source,
                                           ExecutionEvent requested) {
        for (ExecutionEvent original : events) {
            if (original.kind() != EventKind.INTENT) {
                continue;
            }
            if (!original.rootRunId().equals(source.rootRunId())
                    || !Objects.equals(original.nodeId(), source.nodeId())) {
                continue;
            }
            if (codec.same(requested.details().get("tool"), original.details().get("tool"))
                    && codec.same(requested.details().get("arguments"), original.details().get("arguments"))) {
                return true;
            }
        }
        return false;
    }
}
