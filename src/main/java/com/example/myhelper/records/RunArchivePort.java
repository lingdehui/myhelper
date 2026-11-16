package com.example.myhelper.records;

import com.example.myhelper.orchestration.ExecutionTraceStore;
import com.example.myhelper.orchestration.TaskPlan;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 任务外固化消费者读取已确认提交的固定归档；不读取循环的可变内存状态。 */
public interface RunArchivePort {
    Optional<ArchivedRun> readArchive(ArchiveRef ref);

    /** 发现尚未确认消费的完整用户运行；不是领取或分页快照，不含内部验证运行。 */
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
            /** 记录完整性，不表示业务成功或审核通过。 */
            boolean graphComplete,
            /** 不完整原因，禁止将缺图运行投入固化待办。 */
            List<String> gaps,
            /** 本运行的全部执行事实与验收证据，包括结束事件。 */
            List<TaskRecordPort.ExecutionEvent> events,
            /** 应用入口标识的内部验证来源，不能由普通 arguments 推定。 */
            boolean internalValidation) {
        public ArchivedRun {
            Objects.requireNonNull(ref);
            Objects.requireNonNull(endEvent);
            if (schemaVersion != 1 || !ref.taskId().equals(endEvent.taskId())
                    || !ref.rootRunId().equals(endEvent.rootRunId())) {
                throw new IllegalArgumentException("归档版本或运行身份不匹配");
            }
            TaskRecordPort.RunFinalization checked =
                    new TaskRecordPort.RunFinalization(endEvent, graph, graphComplete, gaps);
            graph = checked.graph();
            gaps = checked.gaps();
            events = List.copyOf(events);
            boolean ended = false;
            for (TaskRecordPort.ExecutionEvent event : events) {
                if (!ref.taskId().equals(event.taskId()) || !ref.rootRunId().equals(event.rootRunId())) {
                    throw new IllegalArgumentException("归档混入其他运行的事件");
                }
                if (event.equals(endEvent)) { ended = true; }
            }
            if (!ended) { throw new IllegalArgumentException("归档缺少结束事件"); }
        }
    }
}
