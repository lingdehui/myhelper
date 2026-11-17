package com.example.myhelper.tasklog;

import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.records.TaskRecordPort.EventKind;
import com.example.myhelper.tasklog.TaskLogPort.Context;
import com.example.myhelper.tasklog.TaskLogPort.Event;
import com.example.myhelper.tasklog.TaskLogPort.Kind;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/** 任务事实的展示白名单，实时与历史共用同一投影。 */
public final class TaskLogProjection {
    private TaskLogProjection() { }

    public static Event from(ExecutionEvent fact) {
        Context context = new Context(fact.taskId(), fact.rootRunId(), fact.runId(),
                fact.nodeId(), fact.branchId(), fact.planRevision());
        return from(time(fact.details().get("recordedAt")), context, fact.eventId(), fact.kind(), fact.details());
    }

    /** 关闭记录时直接选取摘要字段，无需复制和验证业务载荷。 */
    public static Event from(Instant at, Context context, String ref, EventKind kind, Map<String, Object> data) {
        return switch (kind) {
            case MODEL_TASK_ASSIGNED -> new Event(at, context, ref, Kind.PLAN_ASSIGNED, "规划已分配");
            case MODEL_CALL_STARTED -> new Event(at, context, ref, Kind.PLAN_CALL_PENDING, "准备请求规划模型");
            case MODEL_CALL_RETURNED -> new Event(at, context, ref, Kind.PLAN_RETURNED, decision(data));
            case MODEL_FAILED -> new Event(at, context, ref, Kind.PLAN_FAILED, "规划调用或决策解析失败");
            case INTENT -> new Event(at, context, ref, Kind.TOOL_INTENT,
                    "准备执行工具 " + label(data.get("tool")));
            case RETURNED -> new Event(at, context, ref, Kind.TOOL_RETURNED,
                    (Boolean.FALSE.equals(data.get("dispatchStarted")) ? "工具未派发 " : "工具返回 ")
                            + label(data.get("tool")) + " status=" + status(data));
            case VERIFIED -> new Event(at, context, ref, Kind.VERIFIED, verification(data));
            case OBSERVED -> new Event(at, context, ref, Kind.OBSERVED, observation(data.get("phase")));
            case RUN_INTERRUPTION_REQUESTED -> new Event(at, context, ref, Kind.RUN_INTERRUPTION_REQUESTED,
                    "收到运行中断事实");
            case RUN_ENDED -> new Event(at, context, ref, Kind.RUN_ENDED, "运行收尾 status=" + status(data));
        };
    }

    private static String decision(Map<String, Object> data) {
        String kind = label(data.get("decisionKind"));
        return switch (kind) {
            case "PLAN" -> data.get("stepCount") instanceof Integer count && count >= 0
                    ? "收到计划 steps=" + count : "收到计划";
            case "COMPLETE" -> "模型提交完成提案，等待验收";
            case "NEEDS_INPUT" -> "模型需要补充输入";
            default -> "收到规划决策";
        };
    }

    private static String verification(Map<String, Object> data) {
        String status = status(data);
        if (Boolean.FALSE.equals(data.get("performed"))) {
            return "验收未执行 actionStatus=" + status;
        }
        return "验收结论 status=" + status;
    }

    private static String observation(Object phase) {
        return switch (label(phase)) {
            case "BEFORE" -> "收到动作前证据";
            case "AFTER" -> "收到动作后证据";
            default -> "收到执行观察";
        };
    }

    private static String status(Map<String, Object> data) {
        if (data.get("outcome") instanceof Map<?, ?> outcome) {
            String status = label(outcome.get("status"));
            return switch (status) {
                case "SUCCESS", "FAILED", "WAITING", "CANCELLED", "NEEDS_INPUT", "UNKNOWN", "BLOCKED" -> status;
                default -> "-";
            };
        }
        return "-";
    }

    private static String label(Object value) {
        return value instanceof String text ? TaskLogPort.safeLabel(text) : "-";
    }

    private static Instant time(Object value) {
        if (value instanceof String text) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ignored) {
                // 历史记录没有可用时间时，不用查询时间替代。
            }
        }
        return null;
    }
}
