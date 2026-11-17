package com.example.myhelper.records;

import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.orchestration.ExecutionTraceStore;
import com.example.myhelper.orchestration.TaskPlan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 任务外固化消费者读取已确认提交的固定归档；不读取循环的可变内存状态。 */
public interface RunArchivePort {
    /** 固定归档的持久合同版本；本次可读性整理仍使用原版本 1。 */
    int SCHEMA_VERSION = 1;

    Optional<ArchivedRun> readArchive(ArchiveRef ref);

    /** 发现图和证据均完整且尚未确认消费的用户运行。 */
    List<ArchiveRef> pendingConsolidations(int limit);

    /** 消费方完成幂等处理后确认；与技能发布跨实体，不声称恰好一次。 */
    void acknowledgeConsolidation(ArchiveRef ref);

    /** 提交后通知的可选接收方；丢失通知由 pendingConsolidations 重扫补齐。 */
    interface Listener {
        void afterCommit(ArchiveRef ref);
    }

    record ArchiveRef(
            /** 任务聚合身份。 */
            String taskId,
            /** 一次真实根运行，结束后归档内容不可覆盖。 */
            String rootRunId) {
        public ArchiveRef {
            TaskPlan.requireText(taskId, "taskId");
            TaskPlan.requireText(rootRunId, "rootRunId");
        }
    }

    record ArchivedRun(
            /** 稳定读取和消费去重身份。 */
            ArchiveRef ref,
            /** 当前归档合同版本。 */
            int schemaVersion,
            /** 同次提交的最终运行事件。 */
            TaskRecordPort.ExecutionEvent endEvent,
            /** 固定执行图；启动前失败允许为空。 */
            ExecutionTraceStore.Snapshot graph,
            /** 执行图结构完整性。 */
            boolean graphComplete,
            /** 执行图结构或快照的缺口。 */
            List<String> gaps,
            /** 本运行的全部执行事实与验收证据，包括结束事件。 */
            List<TaskRecordPort.ExecutionEvent> events,
            /** 应用入口标识的内部验证来源，不能由普通 arguments 推定。 */
            boolean internalValidation,
            /** 未取得写入确认的逐阶段事实，供归档核对。 */
            List<String> evidenceGaps) {
        public ArchivedRun(ArchiveRef ref, int schemaVersion, TaskRecordPort.ExecutionEvent endEvent,
                           ExecutionTraceStore.Snapshot graph, boolean graphComplete, List<String> gaps,
                           List<TaskRecordPort.ExecutionEvent> events, boolean internalValidation) {
            this(ref, schemaVersion, endEvent, graph, graphComplete, gaps, events, internalValidation, List.of());
        }

        public ArchivedRun {
            Objects.requireNonNull(ref);
            Objects.requireNonNull(endEvent);
            if (schemaVersion != SCHEMA_VERSION || !ref.taskId().equals(endEvent.taskId())
                    || !ref.rootRunId().equals(endEvent.rootRunId())) {
                throw new IllegalArgumentException(LlmTexts.ARCHIVE_IDENTITY_MISMATCH);
            }
            TaskRecordPort.RunFinalization checked =
                    new TaskRecordPort.RunFinalization(endEvent, graph, graphComplete, gaps, evidenceGaps);
            graph = checked.graph();
            gaps = checked.gaps();
            evidenceGaps = checked.evidenceGaps();
            events = List.copyOf(events);
            boolean ended = false;
            for (TaskRecordPort.ExecutionEvent event : events) {
                if (!ref.taskId().equals(event.taskId()) || !ref.rootRunId().equals(event.rootRunId())) {
                    throw new IllegalArgumentException(LlmTexts.ARCHIVE_EVENT_MISMATCH);
                }
                if (event.equals(endEvent)) {
                    ended = true;
                }
            }
            if (!ended) {
                throw new IllegalArgumentException(LlmTexts.ARCHIVE_END_EVENT_MISSING);
            }
        }

        public boolean evidenceComplete() {
            return evidenceGaps.isEmpty();
        }
    }
}
