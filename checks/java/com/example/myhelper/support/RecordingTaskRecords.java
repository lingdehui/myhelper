package com.example.myhelper.support;

import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.records.TaskInstructionPort;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 只供检查的进程内记录替身；多个编排器实例可以共用它模拟跨运行接续。
 * synchronized 只保证本替身的原子行为，不代表生产持久化、跨进程租约或崩溃恢复已实现。
 */
public final class RecordingTaskRecords implements TaskRecordPort {
    /** 所有任务的检查数据，不在生产循环中维护。 */
    private final Map<String, RecordedTask> tasks = new LinkedHashMap<>();
    /** 稳定、便于断言的身份序列。 */
    private long sequence;
    /** 只有检查显式设置时，才模拟外部验收通过。 */
    private boolean approveReviews;
    /** 可指定某类写入失败，以检查派发前确认和结束原子性。 */
    private EventKind rejectedEventKind;
    /** 仅检查显式启用时提供指令、版本和恢复扫描替身。 */
    private RecordingTaskInstructions instructionRecords;
    /** 仅供检查读取本次最终归档信封。 */
    private final Map<String, RunFinalization> finalizations = new LinkedHashMap<>();
    /** 检查完整归档成功后是否通知后台。 */
    private final List<String> consolidationWakes = new ArrayList<>();

    /** 启用后与本记录替身共用监视器，不能把它作为生产持久模块。 */
    public synchronized RecordingTaskInstructions enableInstructions() {
        if (instructionRecords == null) {
            instructionRecords = new RecordingTaskInstructions(this);
        }
        return instructionRecords;
    }

    @Override
    public synchronized TaskInstructionPort instructions() {
        if (instructionRecords == null) {
            return TaskInstructionPort.disabled();
        }
        return instructionRecords;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public synchronized TaskSnapshot create(TaskSpec task) {
        Objects.requireNonNull(task);
        String taskId = "task-" + ++sequence;
        TaskSnapshot snapshot = new TaskSnapshot(taskId, task);
        tasks.put(taskId, new RecordedTask(snapshot));
        return snapshot;
    }

    @Override
    public synchronized TaskSnapshot load(String taskId) {
        return task(taskId).original;
    }

    @Override
    public synchronized RunClaim beginRun(String taskId, String rootRunId) {
        RecordedTask task = task(taskId);
        if (task.active != null) {
            throw new IllegalStateException("Task already claimed: " + taskId);
        }
        if (task.claims.containsKey(rootRunId)) {
            throw new IllegalArgumentException("Root run already used: " + rootRunId);
        }
        RunClaim claim = new RunClaim(taskId, rootRunId, "claim-" + ++sequence);
        task.claims.put(rootRunId, claim);
        task.active = claim;
        // 此检查入口模拟应用在模型恢复可用后领取，不实现生产后台唤醒策略。
        if (task.recovery.disposition() == Disposition.WAIT && waitingForModel(task.recovery)) {
            task.recovery = new RecoverySnapshot(task.recovery.revision() + 1,
                    Disposition.CONSULT_MODEL, task.recovery.pending(), task.recovery.progress(),
                    "Model retry was explicitly claimed by the application");
        }
        return claim;
    }

    @Override
    public synchronized RecoverySnapshot query(RunClaim claim, String runId) {
        return active(claim).recovery;
    }

    @Override
    public synchronized void append(RunClaim claim, ExecutionEvent event) {
        RecordedTask task = active(claim);
        validateEvent(claim, event);
        if (event.kind() == EventKind.RUN_ENDED) {
            throw new IllegalArgumentException("RUN_ENDED must use endRun");
        }
        accept(task, event);
    }

    @Override
    public synchronized RecoverySnapshot review(RunClaim claim, ResolutionProposal proposal) {
        RecordedTask task = active(claim);
        if (proposal.expectedRevision() != task.recovery.revision()) {
            throw new IllegalStateException("Stale recovery revision");
        }
        validateIncidents(task.recovery, proposal.incidentIds());
        task.reviews.add(proposal);
        List<RecoveryItem> remaining = new ArrayList<>();
        for (RecoveryItem item : task.recovery.pending()) {
            // 模型输出内容不参与批准判断，批准只来自检查显式模拟的外部验收。
            if (!approveReviews || !proposal.incidentIds().contains(item.id())) {
                remaining.add(item);
            }
        }
        Disposition disposition;
        String reason;
        if (!approveReviews) {
            disposition = Disposition.WAIT;
            reason = "External verification has not confirmed the proposed resolution";
        } else if (remaining.isEmpty()) {
            disposition = Disposition.CONTINUE;
            reason = "External verification confirmed the referenced incidents";
        } else {
            disposition = Disposition.CONSULT_MODEL;
            reason = "Other incidents still need a decision";
        }
        task.recovery = new RecoverySnapshot(task.recovery.revision() + 1, disposition,
                remaining, task.recovery.progress(), reason);
        return task.recovery;
    }

    @Override
    public synchronized void endRun(RunClaim claim, ExecutionEvent event) {
        validateEvent(claim, event);
        if (event.kind() != EventKind.RUN_ENDED) {
            throw new IllegalArgumentException("endRun requires RUN_ENDED");
        }
        RecordedTask task = task(claim.taskId());
        ExecutionEvent previous = task.events.get(event.eventId());
        if (task.ended.containsKey(claim.rootRunId())) {
            if (!claim.equals(task.ended.get(claim.rootRunId())) || !event.equals(previous)) {
                throw new IllegalStateException("Closed run cannot be changed");
            }
            // 重复旧结束事件不影响后来已经领取的新运行。
            return;
        }
        active(claim);
        // 保存失败会在 accept 中抛出，领取保持不变；成功后才在同一锁内释放。
        accept(task, event);
        task.ended.put(claim.rootRunId(), claim);
        task.active = null;
    }

    @Override
    public synchronized void endRun(RunClaim claim, RunFinalization finalization) {
        endRun(claim, finalization.event());
        finalizations.put(claim.rootRunId(), finalization);
    }

    @Override
    public synchronized void wakeConsolidation(RunClaim claim) {
        consolidationWakes.add(claim.rootRunId());
    }

    public synchronized RunFinalization finalization(String rootRunId) {
        return finalizations.get(rootRunId);
    }

    public synchronized boolean consolidationWoken(String rootRunId) {
        return consolidationWakes.contains(rootRunId);
    }

    /** 检查显式模拟外部验收批准；生产不能通过该开关实现验收。 */
    public synchronized void approveReviews(boolean approved) {
        approveReviews = approved;
    }

    /** null 清除注入的记录故障。 */
    public synchronized void failAppend(EventKind kind) {
        rejectedEventKind = kind;
    }

    /** 注入独立模块已有的权威状态，用于跨实例和版本冲突检查。 */
    public synchronized void setRecovery(String taskId, RecoverySnapshot recovery) {
        RecordedTask task = task(taskId);
        if (recovery.revision() < task.recovery.revision()) {
            throw new IllegalArgumentException("Recovery revision cannot move backwards");
        }
        task.recovery = recovery;
    }

    /** 检查读取当前快照，无需伪造活跃运行领取。 */
    public synchronized RecoverySnapshot recovery(String taskId) {
        return task(taskId).recovery;
    }

    /** 检查读取所有已提交事件，顺序为真实接受顺序。 */
    public synchronized List<ExecutionEvent> events(String taskId) {
        return List.copyOf(task(taskId).events.values());
    }

    /** 检查模型提案是否确实送到外部审核。 */
    public synchronized List<ResolutionProposal> reviews(String taskId) {
        return List.copyOf(task(taskId).reviews);
    }

    /** 检查取消时领取是否仍被真实在途工作持有。 */
    public synchronized RunClaim activeClaim(String taskId) {
        return task(taskId).active;
    }

    /** 检查不依赖编排器内部 map 即可找到已登记的任务。 */
    public synchronized List<TaskSnapshot> tasks() {
        List<TaskSnapshot> snapshots = new ArrayList<>();
        for (RecordedTask task : tasks.values()) {
            snapshots.add(task.original);
        }
        return List.copyOf(snapshots);
    }

    private RecordedTask task(String taskId) {
        RecordedTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Unknown task: " + taskId);
        }
        return task;
    }

    private RecordedTask active(RunClaim claim) {
        Objects.requireNonNull(claim);
        RecordedTask task = task(claim.taskId());
        if (!claim.equals(task.active)) {
            throw new IllegalStateException("Claim is not active");
        }
        return task;
    }

    private static void validateEvent(RunClaim claim, ExecutionEvent event) {
        Objects.requireNonNull(event);
        if (!claim.taskId().equals(event.taskId()) || !claim.rootRunId().equals(event.rootRunId())) {
            throw new IllegalArgumentException("Event does not belong to claim");
        }
    }

    private void accept(RecordedTask task, ExecutionEvent event) {
        ExecutionEvent previous = task.events.get(event.eventId());
        if (previous != null) {
            if (!previous.equals(event)) {
                throw new IllegalArgumentException("Event id was reused with different contents");
            }
            return;
        }
        if (event.kind() == rejectedEventKind) {
            throw new IllegalStateException("Injected record failure: " + event.kind());
        }
        task.events.put(event.eventId(), event);
        switch (event.kind()) {
            case RETURNED, VERIFIED -> observeOutcome(task, event);
            case MODEL_FAILED -> observeModelFailure(task, event);
            default -> {
                // INTENT、OBSERVED、RUN_ENDED 只是记录事实，不自行关闭任何问题。
            }
        }
        if (instructionRecords != null) {
            observeProgress(task, event);
        }
    }

    // 新指令检查从真实上报事实构建接续进度，未启用时保留旧替身行为。
    private static void observeProgress(RecordedTask task, ExecutionEvent event) {
        switch (event.kind()) {
            case RETURNED, OBSERVED, VERIFIED -> {
                List<Map<String, Object>> facts = new ArrayList<>();
                for (ExecutionEvent recorded : task.events.values()) {
                    switch (recorded.kind()) {
                        case RETURNED, OBSERVED, VERIFIED -> {
                            Map<String, Object> fact = new LinkedHashMap<>();
                            fact.put("eventId", recorded.eventId());
                            fact.put("rootRunId", recorded.rootRunId());
                            fact.put("nodeId", recorded.nodeId());
                            fact.put("kind", recorded.kind().name());
                            fact.put("details", recorded.details());
                            facts.add(fact);
                        }
                        default -> {
                            // 接续事实展示执行效果与证据，不把模型调用次数伪装成完成进度。
                        }
                    }
                }
                Map<String, Object> progress = new LinkedHashMap<>(task.recovery.progress());
                progress.put("executionFacts", facts);
                task.recovery = new RecoverySnapshot(task.recovery.revision() + 1,
                        task.recovery.disposition(), task.recovery.pending(), progress, task.recovery.reason());
            }
            default -> {
                // 指令版本由独立检查端口维护，不重写原始执行事件。
            }
        }
    }

    private static void observeOutcome(RecordedTask task, ExecutionEvent event) {
        Object value = event.details().get("outcome");
        if (!(value instanceof Map<?, ?> outcome)) {
            return;
        }
        Object status = outcome.get("status");
        if (!"FAILED".equals(status) && !"UNKNOWN".equals(status)) {
            return;
        }
        String incidentId = "incident-" + (event.nodeId() == null ? event.eventId() : event.nodeId());
        List<RecoveryItem> pending = new ArrayList<>();
        for (RecoveryItem item : task.recovery.pending()) {
            if (!item.id().equals(incidentId)) {
                pending.add(item);
            }
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("eventId", event.eventId());
        evidence.put("rootRunId", event.rootRunId());
        evidence.put("nodeId", event.nodeId());
        evidence.put("facts", event.details());
        pending.add(new RecoveryItem(incidentId, "OPEN", "Execution needs an evidence-based decision", evidence));
        task.recovery = new RecoverySnapshot(task.recovery.revision() + 1,
                Disposition.CONSULT_MODEL, pending, task.recovery.progress(), "Execution facts require a decision");
    }

    private static void observeModelFailure(RecordedTask task, ExecutionEvent event) {
        List<RecoveryItem> pending = new ArrayList<>();
        for (RecoveryItem item : task.recovery.pending()) {
            Map<String, Object> evidence = new LinkedHashMap<>(item.evidence());
            evidence.put("modelFailure", event.details());
            pending.add(new RecoveryItem(item.id(), "WAITING_MODEL", item.description(), evidence));
        }
        if (pending.isEmpty()) {
            pending.add(new RecoveryItem("incident-" + event.eventId(), "WAITING_MODEL",
                    "The original task still needs a model decision", event.details()));
        }
        task.recovery = new RecoverySnapshot(task.recovery.revision() + 1, Disposition.WAIT,
                pending, task.recovery.progress(), "Waiting for a new model attempt");
    }

    private static boolean waitingForModel(RecoverySnapshot snapshot) {
        for (RecoveryItem item : snapshot.pending()) {
            if ("WAITING_MODEL".equals(item.state())) {
                return true;
            }
        }
        return false;
    }

    private static void validateIncidents(RecoverySnapshot snapshot, List<String> incidentIds) {
        for (String incidentId : incidentIds) {
            boolean present = false;
            for (RecoveryItem item : snapshot.pending()) {
                if (item.id().equals(incidentId)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                throw new IllegalArgumentException("Unknown incident: " + incidentId);
            }
        }
    }

    /** 所有状态仅供此检查替身使用；生产仓储不复用此类。 */
    private static final class RecordedTask {
        /** 创建任务时保存的原始配置。 */
        private final TaskSnapshot original;
        /** 全部运行领取的检查历史。 */
        private final Map<String, RunClaim> claims = new LinkedHashMap<>();
        /** 已真实结束的领取，用于结束事件去重。 */
        private final Map<String, RunClaim> ended = new LinkedHashMap<>();
        /** 已提交事件，使用独立 eventId 去重。 */
        private final Map<String, ExecutionEvent> events = new LinkedHashMap<>();
        /** 已交外部审核的模型提案。 */
        private final List<ResolutionProposal> reviews = new ArrayList<>();
        /** 当前活跃领取。 */
        private RunClaim active;
        /** 当前权威快照的检查替身。 */
        private RecoverySnapshot recovery = RecoverySnapshot.empty();

        private RecordedTask(TaskSnapshot original) {
            this.original = original;
        }
    }
}
