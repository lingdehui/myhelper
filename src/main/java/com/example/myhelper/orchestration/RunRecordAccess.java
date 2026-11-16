package com.example.myhelper.orchestration;

import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.records.TaskInstructionPort;
import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.records.TaskRecordPort.EventKind;
import com.example.myhelper.records.TaskRecordPort.RecoveryItem;
import com.example.myhelper.records.TaskRecordPort.RecoverySnapshot;
import com.example.myhelper.records.TaskRecordPort.RunClaim;
import com.example.myhelper.records.TaskRecordPort.TaskSnapshot;
import com.example.myhelper.records.TaskRecordPort.TaskSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 本次运行的记录端口接线；不保存权威进度、待办表或自行认定恢复完成。 */
final class RunRecordAccess {
    /** 结束提交可能已生效但响应丢失；仅以同一幂等信封最多重试三次。 */
    private static final int FINALIZATION_ATTEMPTS = 3;
    /** 可信入口给出的运行来源，内部验证不触发固化循环。 */
    private final boolean internalValidation;
    /** 记录与恢复事项由调用方装配，可明确关闭。 */
    private final TaskRecordPort port;
    /** 稳定任务身份，来自外部登记或恢复读取。 */
    private final String taskId;
    /** 每次实际执行重新生成的根运行身份。 */
    private final String rootRunId;
    /** 外部模块授予的本次运行权，不在循环内部实现租约。 */
    private final RunClaim claim;
    /** 本运行领取时固定的用户指令；不会被后来输入原地改写。 */
    private final TaskInstructionPort.Snapshot instructions;
    /** 各工作分支未取得写入确认的事件，随最终归档记录缺口。 */
    private final ConcurrentLinkedQueue<String> unconfirmedEvents = new ConcurrentLinkedQueue<>();
    /** 防止同一工作链重复发送结束通知。 */
    private boolean ended;
    /** 首次收尾信封，异常重试复用相同身份、时间和固定内容。 */
    private TaskRecordPort.RunFinalization pendingEnd;

    RunRecordAccess(TaskRecordPort port, String rootRunId, AgentOrchestrator.Request request, TaskSnapshot previous) {
        this.port = port;
        this.rootRunId = rootRunId;
        this.internalValidation = request.internalValidation();
        // 未接入记录模块的旧调用仍能运行，但不提供跨运行恢复。
        if (!port.enabled()) {
            if (previous != null) {
                throw new IllegalStateException(LlmTexts.TASK_RECORDS_REQUIRED);
            }
            this.taskId = rootRunId;
            this.claim = null;
            this.instructions = TaskInstructionPort.Snapshot.empty();
            return;
        }
        // 新任务交外部模块登记；恢复沿用读到的原任务身份及输入。
        TaskSnapshot task = previous;
        if (task == null) {
            task = port.create(new TaskSpec(request.goal(), request.arguments(), request.modelRef(), request.toolNames(), request.internalValidation()));
        }
        this.taskId = task.taskId();
        // 外部负责并发领取；旧运行尚在途时不得授予冲突运行权。
        this.claim = port.beginRun(taskId, rootRunId);
        if (!taskId.equals(claim.taskId()) || !rootRunId.equals(claim.rootRunId())) {
            throw new IllegalStateException(LlmTexts.INVALID_RECORD_CLAIM);
        }
        try {
            this.instructions = port.instructions().enabled()
                    ? port.instructions().bind(claim) : TaskInstructionPort.Snapshot.empty();
        } catch (RuntimeException failure) {
            // 领取成功但版本绑定失败时尚未执行；仍须结束这次领取，不能泄漏运行权。
            try {
                end(new AgentOrchestrator.Outcome(AgentOrchestrator.Status.WAITING, null, failure.getMessage()));
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    boolean enabled() {
        return port.enabled();
    }

    String taskId() {
        return taskId;
    }

    TaskInstructionPort.Snapshot instructions() {
        return instructions;
    }

    TaskInstructionPort.Directive directive() {
        if (!enabled() || !port.instructions().enabled()) {
            return TaskInstructionPort.Directive.PROCEED;
        }
        return port.instructions().check(claim, instructions.revision());
    }

    boolean applyPlan(String runId, long revision, Map<String, Object> plan) {
        return !enabled() || !port.instructions().enabled()
                || port.instructions().applied(claim, instructions.revision(), runId, revision, plan);
    }

    boolean complete(long expectedRecoveryRevision, AgentOrchestrator.Outcome outcome) {
        return !enabled() || !port.instructions().enabled()
                || port.instructions().complete(claim, instructions.revision(), expectedRecoveryRevision, outcome.asMap());
    }

    // 查询最新快照，不在循环缓存另一份需要同步的待处理事项表。
    RecoverySnapshot query(String runId) {
        if (!enabled()) {
            return RecoverySnapshot.empty();
        }
        return port.query(claim, runId);
    }

    // 同步返回表示外部已经按合同接受记录；发生异常时不悄悄退回内存。
    void emit(String runId, String nodeId, String branchId, long revision, EventKind kind, Map<String, Object> details) {
        if (enabled()) {
            ExecutionEvent fact = event(runId, nodeId, branchId, revision, kind, details);
            try {
                port.append(claim, fact);
            } catch (RuntimeException failure) {
                unconfirmedEvents.add("事件写入未确认 " + fact.kind()
                        + " nodeId=" + fact.nodeId() + " eventId=" + fact.eventId());
                throw failure;
            }
        }
    }

    // 模型的 COMPLETE 只是恢复结论提案；外部审核和版本检查决定待办是否改变。
    RecoverySnapshot review(String runId, RecoverySnapshot basedOn, Object conclusion) {
        List<String> incidentIds = new ArrayList<>();
        for (RecoveryItem item : basedOn.pending()) {
            incidentIds.add(item.id());
        }
        return port.review(claim, new TaskRecordPort.ResolutionProposal(
                runId, basedOn.revision(), List.copyOf(incidentIds), conclusion));
    }

    // 为外部固化适用判断提供当前任务和问题上下文，循环不解释问题是否使能力失效。
    Map<String, Object> context(String runId, RecoverySnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId);
        result.put("rootRunId", rootRunId);
        result.put("runId", runId);
        result.put("recovery", snapshotData(snapshot));
        result.put("instructions", instructionData());
        return TaskPlan.immutableMap(result);
    }

    Map<String, Object> instructionData() {
        List<Map<String, Object>> commands = new ArrayList<>();
        if (instructions != null) {
            for (TaskInstructionPort.Command command : instructions.instructions()) {
                commands.add(Map.of("commandId", command.commandId(), "kind", command.kind().name(),
                        "text", command.text(), "expectedRevision", command.expectedRevision()));
            }
        }
        return Map.of("revision", instructions == null ? 0L : instructions.revision(),
                "appliedRevision", instructions == null ? 0L : instructions.appliedRevision(), "commands", commands);
    }

    // 对外等待结果使用 JSON 数据，避免把端口 DTO 塞进只接受 JSON 的 Outcome。
    static Map<String, Object> snapshotData(RecoverySnapshot snapshot) {
        List<Map<String, Object>> pending = new ArrayList<>();
        for (RecoveryItem item : snapshot.pending()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", item.id());
            data.put("state", item.state());
            data.put("description", item.description());
            data.put("evidence", item.evidence());
            pending.add(TaskPlan.immutableMap(data));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", snapshot.revision());
        result.put("disposition", snapshot.disposition().name());
        result.put("pending", pending);
        result.put("progress", snapshot.progress());
        result.put("reason", snapshot.reason());
        return TaskPlan.immutableMap(result);
    }

    // 真正工作结束后才通知外部释放运行权；对外取消提前返回时不调用。
    synchronized void end(AgentOrchestrator.Outcome outcome) {
        end(outcome, null, List.of(LlmTexts.ARCHIVE_NO_GRAPH));
    }

    synchronized void end(AgentOrchestrator.Outcome outcome, ExecutionTraceStore.Snapshot graph, List<String> knownGaps) {
        if (ended) {
            return;
        }
        if (enabled()) {
            if (pendingEnd == null) {
                List<String> gaps = new ArrayList<>(archiveGaps(graph, knownGaps));
                gaps.addAll(unconfirmedEvents);
                pendingEnd = new TaskRecordPort.RunFinalization(
                        event(rootRunId, rootRunId, rootRunId, 0, EventKind.RUN_ENDED,
                                Map.of("outcome", outcome.asMap())), graph, gaps.isEmpty(), gaps);
            }
            // 正常返回才认为提交确认；异常时保留 pendingEnd，不能先结束领取或发唤醒。
            for (int attempt = 1; attempt <= FINALIZATION_ATTEMPTS; attempt++) {
                try {
                    port.endRun(claim, pendingEnd);
                    break;
                } catch (RuntimeException failure) {
                    if (attempt == FINALIZATION_ATTEMPTS) { throw failure; }
                }
            }
        }
        ended = true;
        if (enabled() && pendingEnd.graphComplete() && !internalValidation) {
            try {
                port.wakeConsolidation(claim);
            } catch (RuntimeException failure) {
                System.getLogger(RunRecordAccess.class.getName()).log(System.Logger.Level.WARNING,
                        "固化唤醒失败，保留已提交记录供重试", failure);
            }
        }
    }

    // 完整性与业务结果分开：失败的任务也能有完整图，缺图不能冒充可消费归档。
    private static List<String> archiveGaps(ExecutionTraceStore.Snapshot graph, List<String> knownGaps) {
        List<String> gaps = new ArrayList<>(knownGaps);
        if (graph == null) {
            if (!gaps.contains(LlmTexts.ARCHIVE_NO_GRAPH)) { gaps.add(LlmTexts.ARCHIVE_NO_GRAPH); }
            return List.copyOf(gaps);
        }
        if (!graph.closed()) { gaps.add(LlmTexts.ARCHIVE_NOT_CLOSED); }
        if (graph.nodes().isEmpty()) { gaps.add(LlmTexts.ARCHIVE_EMPTY_GRAPH); }
        for (Map.Entry<String, ExecutionTraceStore.Node> entry : graph.nodes().entrySet()) {
            ExecutionTraceStore.Node node = entry.getValue();
            if (!entry.getKey().equals(node.id()) || node.endedAt() == null
                    || node.status() == ExecutionTraceStore.NodeStatus.RUNNING
                    || (node.parentId() != null && !graph.nodes().containsKey(node.parentId()))) {
                gaps.add(LlmTexts.ARCHIVE_INCOMPLETE_NODE + entry.getKey());
            }
        }
        for (ExecutionTraceStore.Edge edge : graph.edges()) {
            if (!graph.nodes().containsKey(edge.from()) || !graph.nodes().containsKey(edge.to())) {
                gaps.add(LlmTexts.ARCHIVE_INCOMPLETE_EDGE);
            }
        }
        return List.copyOf(gaps);
    }

    // 每个阶段有不同事件身份，同一次工具的 INTENT/RETURNED/VERIFIED 不互相去重。
    private ExecutionEvent event(String runId, String nodeId, String branchId, long revision,
                                 EventKind kind, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("recordedAt", Instant.now().toString());
        // 旧运行的迟到事实仍带旧指令版本；版本不从最新用户消息反向覆盖。
        payload.put("instructionRevision", instructions == null ? 0L : instructions.revision());
        return new ExecutionEvent(UUID.randomUUID().toString(), taskId, rootRunId, runId,
                nodeId, branchId, revision, kind, payload);
    }
}
