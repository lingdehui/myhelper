package com.example.myhelper.tasklog;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** 运行观测入口。实现应快速接收，耗时的输出在独立消费线程完成。 */
@FunctionalInterface
public interface TaskLogPort {
    /** 无日志装配的旧调用路径复用同一个空实现。 */
    TaskLogPort DISABLED = new TaskLogPort() {
        @Override
        public void publish(Event event) { }
    };

    void publish(Event event);

    /** 可选观测不可改变任务结果，也不可替代调用方的持久写入确认。 */
    static void report(TaskLogPort port, Event event) {
        try {
            port.publish(event);
        } catch (RuntimeException ignored) {
            // 正式异步实现独立统计投递和消费失败。
        }
    }

    static TaskLogPort disabled() {
        return DISABLED;
    }

    /** 应用可额外装配的实时订阅。回调在消费线程运行，应立即接收并返回。 */
    @FunctionalInterface
    interface Listener {
        void onEvent(Event event);
    }

    /** 只用于预先选定的标识字段，不用于清洗任意业务文本。 */
    static String safeLabel(String value) {
        return value != null && !value.contains("://") && Event.LABEL.matcher(value).matches() ? value : "-";
    }

    enum Kind {
        RUN_STARTED, RUN_ENDED, STOP_REQUESTED, RUN_INTERRUPTION_REQUESTED, RECORD_UNCONFIRMED,
        WAITING, PLAN_ASSIGNED, PLAN_CALL_PENDING, PLAN_RETURNED, PLAN_APPLIED, PLAN_FAILED,
        TOOL_INTENT, TOOL_RETURNED, VERIFY_STARTED, VERIFIED, OBSERVED,
        MODEL_STARTED, MODEL_RETURNED, MODEL_FAILED
    }

    record Context(
            /** 稳定任务身份。未装配持久记录时采用根运行身份。 */
            String taskId,
            /** 本次根运行身份。 */
            String rootRunId,
            /** 本次递归任务身份。 */
            String runId,
            /** 发生该事件的执行节点。 */
            String nodeId,
            /** 逻辑分支身份。 */
            String branchId,
            /** 递归任务当前计划版本。 */
            long planRevision) { }

    record Event(
            /** 观测或权威事实的发生时间。旧归档缺少时间时允许为空。 */
            Instant at,
            /** 任务关联。独立模型调用可为空。 */
            Context context,
            /** 任务事件 ID、实际模型调用 ID，或单次实时状态的关联节点。 */
            String correlationId,
            /** 供消费者筛选的事件类型。 */
            Kind kind,
            /** 由生产者通过白名单字段组装的短摘要。 */
            String summary) {
        /** 标识仅允许常见 ASCII 标识字符，并限制长度。 */
        private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,95}");
        /** 日志行中统一替换的控制字符。 */
        private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
        /** 扩展生产者的摘要长度上限。 */
        private static final int MAX_SUMMARY = 320;

        public Event {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(summary);
            // 终端控制字符统一处理，来源仍需负责选择允许公开的内容。
            summary = CONTROL.matcher(summary.substring(0, Math.min(summary.length(), MAX_SUMMARY))).replaceAll(" ");
        }

        /** 终端和应用日志共享同一格式与标识校验。 */
        public String line() {
            String location = "";
            if (context != null) {
                location = " task=" + safeLabel(context.taskId()) + " root=" + safeLabel(context.rootRunId())
                        + " run=" + safeLabel(context.runId()) + " node=" + safeLabel(context.nodeId())
                        + " branch=" + safeLabel(context.branchId()) + " revision=" + context.planRevision();
            }
            return kind + " at=" + (at == null ? "-" : at) + location
                    + " ref=" + safeLabel(correlationId) + " " + summary;
        }
    }
}
