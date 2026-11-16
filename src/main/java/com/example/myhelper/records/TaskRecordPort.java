package com.example.myhelper.records;

import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.orchestration.TaskPlan;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 外部任务记录与恢复事项模块的合同，由宿主提供权威仓储实现。
 * 原任务、事件、进度、问题状态、版本及恢复领取的权威数据由实现方管理。
 * 循环只发送事实并读取结论，不从失败码推断回滚，也不自行关闭待处理问题。
 */
public interface TaskRecordPort {
    /** 显式区分未装配与已经装配但查询无待办；启用后发生故障必须报告。 */
    boolean enabled();

    /** 可选指令版本端口；必须与本记录模块共用任务、领取和提交边界。 */
    default TaskInstructionPort instructions() {
        return TaskInstructionPort.disabled();
    }

    /** 持久登记原目标并返回稳定 taskId；同一任务的后续运行使用 load。 */
    TaskSnapshot create(TaskSpec task);

    /** 读取已登记任务；未知身份必须明确失败，不能静默创建新任务。 */
    TaskSnapshot load(String taskId);

    /**
     * 原子登记并领取本次运行，校验已有领取/租约和恢复资格，返回不可伪造的领取凭据。
     * 实现方负责跨进程互斥及失效领取校验；若采用租约须负责续期，显式结束模式不得按时间自动接管。
     * 对外取消不证明旧动作已停止；真实在途工作结束前不能允许冲突恢复者接管。
     */
    RunClaim beginRun(String taskId, String rootRunId);

    /** 查询当前子任务需要的最新问题、进度及接续结论；返回版本用于后续审核。 */
    RecoverySnapshot query(RunClaim claim, String runId);

    /**
     * 原子接受事件，并更新由它派生的进度、证据关联和待办；同一 eventId 必须幂等。
     * 正常返回表示已按生产实现约定持久提交，不能只是放入进程内异步队列。
     * INTENT 必须在实际派发前取得确认；失败时不得派发新的受保证动作。
     * RETURNED 与 VERIFIED 分别保存实际事实和验收结论；未知效果不能被覆盖成未执行。
     * 应校验 claim 与事件身份；取消后仍接收真实在途动作的迟到回执。
     * 本地记录事务不与外部工具动作自动构成原子事务，崩溃间隙须由实现方保留为待核对。
     */
    void append(RunClaim claim, ExecutionEvent event);

    /**
     * 接收模型提出的处理结论，由外部验收和记录模块核验证据、版本和实际状态。
     * 必须校验 expectedRevision，防止旧提案覆盖并发新增问题；不能信任模型自填 resolved。
     * 返回审核后的权威快照；拒绝或证据不足时保留问题，并给出下一步结论和原因。
     */
    RecoverySnapshot review(RunClaim claim, ResolutionProposal proposal);

    /**
     * 原子保存 RUN_ENDED 事实并结束领取；必须在真实在途工作结束后调用。
     * 已提前返回取消仍需保存迟到回执；本方法正常返回前不得先释放领取再尝试保存。
     * 重复提交同一结束事件应幂等；任务结束不等于全部待处理事项已经解决。
     */
    void endRun(RunClaim claim, ExecutionEvent event);

    /** 同一事务保存归档和结束事件、释放领取。图和证据均完整的用户运行才可进入固化待办。 */
    default void endRun(RunClaim claim, RunFinalization finalization) {
        // 兼容旧端口；只有覆盖本重载的实现才提供图归档保证。
        endRun(claim, finalization.event());
    }

    record RunFinalization(
            /** 一次提交稳定复用的 RUN_ENDED 身份和载荷。 */
            ExecutionEvent event,
            /** 图未创建或读取失败时为空，不伪造空成功图。 */
            com.example.myhelper.orchestration.ExecutionTraceStore.Snapshot graph,
            /** 执行图已封闭且结构完整时为 true。 */
            boolean graphComplete,
            /** 执行图结构或快照的缺口。 */
            List<String> gaps,
            /** 未取得写入确认的逐阶段事实。 */
            List<String> evidenceGaps) {
        public RunFinalization(ExecutionEvent event, com.example.myhelper.orchestration.ExecutionTraceStore.Snapshot graph,
                               boolean graphComplete, List<String> gaps) {
            this(event, graph, graphComplete, gaps, List.of());
        }

        public RunFinalization {
            Objects.requireNonNull(event);
            if (event.kind() != EventKind.RUN_ENDED) {
                throw new IllegalArgumentException("最终提交必须包含 RUN_ENDED");
            }
            gaps = List.copyOf(gaps);
            evidenceGaps = List.copyOf(evidenceGaps);
            if (graph != null) {
                graph = new com.example.myhelper.orchestration.ExecutionTraceStore.Snapshot(
                        Map.copyOf(graph.nodes()), List.copyOf(graph.edges()), List.copyOf(graph.events()), graph.closed());
            }
            if (graphComplete && (graph == null || !graph.closed() || !gaps.isEmpty())) {
                throw new IllegalArgumentException("完整归档必须持有封闭且无缺口的执行图");
            }
            if (!graphComplete && gaps.isEmpty()) {
                throw new IllegalArgumentException("不完整归档必须说明缺口");
            }
            if (graphComplete) {
                if (graph.nodes().isEmpty()) { throw new IllegalArgumentException("完整归档不能是空图"); }
                for (var entry : graph.nodes().entrySet()) {
                    var node = entry.getValue();
                    if (!entry.getKey().equals(node.id()) || node.endedAt() == null
                            || node.status() == com.example.myhelper.orchestration.ExecutionTraceStore.NodeStatus.RUNNING
                            || (node.parentId() != null && !graph.nodes().containsKey(node.parentId()))) {
                        throw new IllegalArgumentException("完整归档中节点未终止或身份不完整");
                    }
                }
                for (var edge : graph.edges()) {
                    if (!graph.nodes().containsKey(edge.from()) || !graph.nodes().containsKey(edge.to())) {
                        throw new IllegalArgumentException("完整归档中关系端点缺失");
                    }
                }
            }
        }

        /** 本次运行没有已知写入未确认的事实。 */
        public boolean evidenceComplete() {
            return evidenceGaps.isEmpty();
        }
    }

    /**
     * 最终记录提交后的后台固化唤醒。实现方按运行来源排除内部验证任务。
     * 运行提交时应已保存待处理标识；唤醒失败不改变已提交的运行结果。
     */
    default void wakeConsolidation(RunClaim claim) {
    }

    /** 兼容尚未装配记录模块的调用；没有恢复能力，不能把空查询宣传为持久保证。 */
    static TaskRecordPort disabled() {
        return DisabledTaskRecords.INSTANCE;
    }

    /** 外部模块给出的接续建议；循环不能由 pending 数量自行发明业务阻断。 */
    enum Disposition {
        /** 允许按原目标继续，可能仍有已隔离或由其他工作处理的问题。 */
        CONTINUE,
        /** 需将问题和证据交模型判断，模型仍可安排不受影响的工作。 */
        CONSULT_MODEL,
        /** 当前需要等待外部条件或确认，不通过重复模型请求忙等。 */
        WAIT
    }

    /** 事件记录发生过什么；是否产生待办及怎样变化由外部模块决定。 */
    enum EventKind {
        /** 本轮已经取得模型绑定及其任务；不证明请求已发出，也不表示新建了分支绑定。 */
        MODEL_TASK_ASSIGNED,
        /** 通过本轮预算和停止检查，准备进入一次规划模型调用。 */
        MODEL_CALL_STARTED,
        /** 规划模型正常返回决策；任务是否完成仍由后续执行和验收判定。 */
        MODEL_CALL_RETURNED,
        /** 已解析参数和目标，尚未派发实际动作。 */
        INTENT,
        /** 实际调用返回，包括失败、未知及已知产物。 */
        RETURNED,
        /** 独立验收的结论、原因和证据。 */
        VERIFIED,
        /** 其他观察，例如模型决策或当前状态查询的结果。 */
        OBSERVED,
        /** 模型调用失败，由外部模块决定是否保留等待模型的事项。 */
        MODEL_FAILED,
        /** 本次运行请求停止新派发；不表示真实在途动作已结束。 */
        RUN_INTERRUPTION_REQUESTED,
        /** 真实工作链已经结束，不等于对外 Future 提前取消。 */
        RUN_ENDED
    }

    /** 原始任务配置；不保存 Java 会话、线程、Future 或客户端实例。 */
    record TaskSpec(
            /** 用户原始目标。 */
            String goal,
            /** 初始参数的深层只读快照。 */
            Map<String, Object> arguments,
            /** 模型配置引用，恢复时重新解析。 */
            String modelRef,
            /** 本任务允许使用的工具范围。 */
            List<String> toolNames,
            /** 由受信任应用入口提供；内部验证运行不进入自动固化待办。 */
            boolean internalValidation) {
        public TaskSpec(String goal, Map<String, Object> arguments, String modelRef, List<String> toolNames) {
            this(goal, arguments, modelRef, toolNames, false);
        }

        public TaskSpec {
            TaskPlan.requireText(goal, "goal");
            TaskPlan.requireText(modelRef, "modelRef");
            arguments = TaskPlan.immutableMap(arguments);
            toolNames = List.copyOf(toolNames);
            for (String toolName : toolNames) {
                TaskPlan.requireText(toolName, "toolName");
            }
        }
    }

    /** 外部模块登记的稳定任务身份及原始配置。 */
    record TaskSnapshot(
            /** 跨运行保持不变的任务身份。 */
            String taskId,
            /** 权威原始目标和配置。 */
            TaskSpec task) {
        public TaskSnapshot {
            TaskPlan.requireText(taskId, "taskId");
            Objects.requireNonNull(task);
        }
    }

    /** 本次恢复领取的外部凭据；只作为身份传递，循环不解释 token。 */
    record RunClaim(
            /** 原任务身份。 */
            String taskId,
            /** 本次根运行身份，每次恢复均新建。 */
            String rootRunId,
            /** 外部领取或租约凭据。 */
            String token) {
        public RunClaim {
            TaskPlan.requireText(taskId, "taskId");
            TaskPlan.requireText(rootRunId, "rootRunId");
            TaskPlan.requireText(token, "token");
        }
    }

    /** 外部模块的一致性快照；查询和审核都返回本类型。 */
    record RecoverySnapshot(
            /** 供审核校验的外部状态版本。 */
            long revision,
            /** 外部明确给出的接续结论。 */
            Disposition disposition,
            /** 待处理事项，不由循环增删。 */
            List<RecoveryItem> pending,
            /** 外部任务进度及证据引用的深层只读快照。 */
            Map<String, Object> progress,
            /** 接续结论的原因；无额外原因时可为空。 */
            String reason) {
        public RecoverySnapshot {
            requireRevision(revision);
            Objects.requireNonNull(disposition);
            pending = List.copyOf(pending);
            progress = TaskPlan.immutableMap(progress);
        }

        /** 未装配或外部明确没有额外状态时使用；不表示存在持久记录。 */
        public static RecoverySnapshot empty() {
            return new RecoverySnapshot(0, Disposition.CONTINUE, List.of(), Map.of(), null);
        }
    }

    /** 问题内容和状态来自独立模块，不在循环规定领域状态机。 */
    record RecoveryItem(
            /** 稳定问题身份，处理步骤通过它关联原始失败。 */
            String id,
            /** 外部模块定义的状态，例如等待核对或等待模型。 */
            String state,
            /** 模型可以理解的问题说明。 */
            String description,
            /** 实际操作、版本、差异及缺口等证据数据。 */
            Map<String, Object> evidence) {
        public RecoveryItem {
            TaskPlan.requireText(id, "incidentId");
            TaskPlan.requireText(state, "incidentState");
            TaskPlan.requireText(description, "incidentDescription");
            evidence = TaskPlan.immutableMap(evidence);
        }
    }

    /** 模型处理结论只是外部审核的输入，不是写入 RESOLVED 的命令。 */
    record ResolutionProposal(
            /** 提出结论的子任务身份。 */
            String runId,
            /** 模型结论所依据的记录版本。 */
            long expectedRevision,
            /** 此提案涉及的问题，不能无条件清空其他问题。 */
            List<String> incidentIds,
            /** 模型结论及引用证据的深层只读快照。 */
            Object conclusion) {
        public ResolutionProposal {
            TaskPlan.requireText(runId, "runId");
            requireRevision(expectedRevision);
            incidentIds = List.copyOf(incidentIds);
            for (String incidentId : incidentIds) {
                TaskPlan.requireText(incidentId, "incidentId");
            }
            conclusion = TaskPlan.immutable(conclusion);
        }
    }

    /** 完整执行事实；不同阶段使用新 eventId，并保留相同动作 nodeId。 */
    record ExecutionEvent(
            /** 本事件的独立去重身份。 */
            String eventId,
            /** 稳定任务身份。 */
            String taskId,
            /** 本次根运行身份。 */
            String rootRunId,
            /** 所属子任务；根运行级事件可为空。 */
            String runId,
            /** 执行节点；工具节点同时作为 operationId，根运行级事件可为空。 */
            String nodeId,
            /** 所属逻辑分支；根运行级事件可为空。 */
            String branchId,
            /** 对应计划修订，尚未规划时为零。 */
            long planRevision,
            /** 发生的事实阶段。 */
            EventKind kind,
            /** 真实输入、返回、错误、验收及凭据；未知数据须明确保留未知。 */
            Map<String, Object> details) {
        public ExecutionEvent {
            TaskPlan.requireText(eventId, "eventId");
            TaskPlan.requireText(taskId, "taskId");
            TaskPlan.requireText(rootRunId, "rootRunId");
            requireRevision(planRevision);
            Objects.requireNonNull(kind);
            details = TaskPlan.immutableMap(details);
        }
    }

    /** 统一校验外部状态版本与计划修订。 */
    private static void requireRevision(long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException(LlmTexts.INVALID_RECORD_REVISION);
        }
    }
}

/** 只表达未装配；不创建生产内存待办、不伪造领取和恢复成功。 */
final class DisabledTaskRecords implements TaskRecordPort {
    /** 无状态实例，可供旧入口共用。 */
    static final DisabledTaskRecords INSTANCE = new DisabledTaskRecords();

    private DisabledTaskRecords() {
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public TaskSnapshot create(TaskSpec task) {
        throw unavailable();
    }

    @Override
    public TaskSnapshot load(String taskId) {
        throw unavailable();
    }

    @Override
    public RunClaim beginRun(String taskId, String rootRunId) {
        throw unavailable();
    }

    @Override
    public RecoverySnapshot query(RunClaim claim, String runId) {
        return RecoverySnapshot.empty();
    }

    @Override
    public void append(RunClaim claim, ExecutionEvent event) {
        // 未装配时没有记录接收方，调用方不得宣称事件已持久化。
    }

    @Override
    public RecoverySnapshot review(RunClaim claim, ResolutionProposal proposal) {
        throw unavailable();
    }

    @Override
    public void endRun(RunClaim claim, ExecutionEvent event) {
        // 未装配时不存在领取，不生成任何“已恢复”状态。
    }

    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException(LlmTexts.TASK_RECORDS_REQUIRED);
    }
}
