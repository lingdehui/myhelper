package com.example.myhelper.records;

import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.world.model.WorldEntity;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Neo4j 单实体 CAS 任务仓储。任务、指令、领取、事件和归档清单一次提交，
 * 不把跨实体普通覆盖写当成事务；无进程内权威缓存，无自动过期接管。
 */
public final class StoredTaskRecords implements TaskRecordPort, TaskInstructionPort, RunArchivePort {
    /** 防止持续竞争占满调用线程。 */
    private static final int MAX_CAS_ATTEMPTS = 32;
    /** 内部扫描每次最多读取一百个聚合；对外总量仍由调用方上限约束。 */
    private static final int SCAN_PAGE_SIZE = 100;
    /** 所有数据库访问统一经过此入口。 */
    private final DualWriteStore store;
    /** 独立审核在 CAS 外执行，不信任模型自述。 */
    private final ReviewPolicy reviews;
    /** 仅通知；待办与最终归档已在同一 CAS 内持久提交。 */
    private final Consumer<ArchiveRef> wake;
    /** 独立处理状态、时间和 JSON 规范化，不包含数据库访问。 */
    private final RecordCodec codec;
    /** 纯粹的重放判断；CAS 只原子落实它对当前快照作出的决定。 */
    private final ToolReplayPolicy replayPolicy;
    /** 宿主生命周期身份；null 表示仅允许外部人工核对旧运行。 */
    private final String ownerId;

    public StoredTaskRecords(DualWriteStore store) {
        this(store, new KeepPendingReviews(), new NoWake());
    }

    public StoredTaskRecords(DualWriteStore store, ReviewPolicy reviews, Consumer<ArchiveRef> wake) {
        this(store, reviews, wake, null);
    }

    public StoredTaskRecords(DualWriteStore store, ReviewPolicy reviews, Consumer<ArchiveRef> wake, String ownerId) {
        this(store, reviews, wake, ownerId, ToolReplayPolicy.safeDefault());
    }

    public StoredTaskRecords(DualWriteStore store, ReviewPolicy reviews, Consumer<ArchiveRef> wake,
                             String ownerId, ToolReplayPolicy replayPolicy) {
        if (ownerId != null) {
            TaskPlan.requireText(ownerId, "ownerId");
        }
        this.ownerId = ownerId;
        this.store = Objects.requireNonNull(store);
        this.reviews = Objects.requireNonNull(reviews);
        this.wake = Objects.requireNonNull(wake);
        this.codec = new RecordCodec();
        this.replayPolicy = Objects.requireNonNull(replayPolicy);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public TaskInstructionPort instructions() {
        return this;
    }

    @Override
    public TaskSnapshot create(TaskSpec task) {
        Objects.requireNonNull(task);
        String id = "task-record-" + UUID.randomUUID();
        RecordState state = new RecordState();
        state.taskId = id;
        state.task = task;
        WorldEntity entity = entity(state, null);
        try {
            if (!store.writeIfAbsent(DualWriteStore.Database.NEO4J, entity)) {
                throw new IllegalStateException(LlmTexts.RECORDS_TASK_ID_COLLISION);
            }
        } catch (RuntimeException uncertain) {
            try {
                Optional<WorldEntity> observed = store.read(DualWriteStore.Database.NEO4J, id);
                if (observed.isPresent() && codec.same(observed.get().data(), entity.data())) {
                    return new TaskSnapshot(id, task);
                }
            } catch (RuntimeException verificationFailure) {
                uncertain.addSuppressed(verificationFailure);
            }
            throw uncertain;
        }
        return new TaskSnapshot(id, task);
    }

    @Override
    public TaskSnapshot load(String taskId) {
        RecordState state = read(taskId).state();
        return new TaskSnapshot(state.taskId, state.task);
    }

    @Override
    public RunClaim beginRun(String taskId, String rootRunId) {
        RunClaim claim = new RunClaim(taskId, rootRunId, UUID.randomUUID().toString());
        return update(taskId, new Change<RunClaim>() {
            @Override
            public RunClaim apply(RecordState state) {
                return claimRun(state, claim);
            }
        });
    }

    @Override
    public RecoverySnapshot query(RunClaim claim, String runId) {
        RecordState state = read(claim.taskId()).state();
        active(state, claim);
        return recovery(state);
    }

    @Override
    public void append(RunClaim claim, ExecutionEvent event) {
        validateEvent(claim, event);
        if (event.kind() == EventKind.RUN_ENDED) {
            throw new IllegalArgumentException(LlmTexts.RECORDS_END_REQUIRES_FINALIZATION);
        }
        update(claim.taskId(), new Change<Void>() {
            @Override
            public Void apply(RecordState state) {
                appendClaimedEvent(state, claim, event);
                return null;
            }
        });
    }

    @Override
    public RecoverySnapshot review(RunClaim claim, ResolutionProposal proposal) {
        Loaded original = read(claim.taskId());
        active(original.state(), claim);
        RecoverySnapshot before = recovery(original.state());
        if (before.revision() != proposal.expectedRevision()) {
            throw new IllegalStateException(LlmTexts.RECORDS_REVIEW_STALE);
        }
        for (String id : proposal.incidentIds()) {
            if (!original.state().incidents.containsKey(id)) {
                throw new IllegalArgumentException(LlmTexts.RECORDS_REVIEW_ITEM_MISSING);
            }
        }
        // 策略可能查询外部证据；绝不放入 CAS 重试或数据库事务回调。
        ReviewDecision decision = Objects.requireNonNull(reviews.review(before, proposal));
        if (!proposal.incidentIds().containsAll(decision.approvedIds())) {
            throw new IllegalArgumentException(LlmTexts.RECORDS_REVIEW_OUTSIDE_PROPOSAL);
        }
        RecordState state = original.state();
        for (String id : decision.approvedIds()) {
            state.incidents.remove(id);
        }
        state.reviewHistory.add(proposal);
        state.reason = decision.reason();
        state.disposition = decision.disposition();
        state.recoveryRevision++;
        WorldEntity reviewed = entity(state, original.entity());
        try {
            if (!store.compareAndSet(DualWriteStore.Database.NEO4J, original.entity(), reviewed)) {
                throw new IllegalStateException(LlmTexts.RECORDS_REVIEW_CONFLICT);
            }
        } catch (RuntimeException uncertain) {
            try {
                Loaded observed = read(claim.taskId());
                if (codec.same(observed.entity().data(), reviewed.data())) {
                    return recovery(observed.state());
                }
            } catch (RuntimeException verificationFailure) {
                uncertain.addSuppressed(verificationFailure);
            }
            throw uncertain;
        }
        return recovery(state);
    }

    @Override
    public void endRun(RunClaim claim, ExecutionEvent event) {
        endRun(claim, new RunFinalization(event, null, false, List.of(RecordState.NO_FINAL_GRAPH)));
    }

    @Override
    public void endRun(RunClaim claim, RunFinalization finalization) {
        validateEvent(claim, finalization.event());
        if (finalization.event().kind() != EventKind.RUN_ENDED) {
            throw new IllegalArgumentException(LlmTexts.RECORDS_END_EVENT_MISSING);
        }
        update(claim.taskId(), new Change<Void>() {
            @Override
            public Void apply(RecordState state) {
                finalizeRun(state, claim, finalization);
                return null;
            }
        });
    }

    @Override
    public Optional<ArchivedRun> readArchive(ArchiveRef ref) {
        Objects.requireNonNull(ref);
        Optional<WorldEntity> entity = store.read(DualWriteStore.Database.NEO4J, ref.taskId());
        if (entity.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(codec.decode(entity.get()).archives.get(ref.rootRunId()));
    }

    @Override
    public List<ArchiveRef> pendingConsolidations(int limit) {
        List<ArchiveRef> result = new ArrayList<>();
        if (limit == 0) {
            return List.of();
        }
        for (RecordState state : scan("hasPendingConsolidation", limit)) {
            for (String root : state.pendingArchives) {
                result.add(new ArchiveRef(state.taskId, root));
                if (result.size() == limit) {
                    return List.copyOf(result);
                }
            }
        }
        return List.copyOf(result);
    }

    @Override
    public void acknowledgeConsolidation(ArchiveRef ref) {
        update(ref.taskId(), new Change<Void>() {
            @Override
            public Void apply(RecordState state) {
                acknowledgeArchive(state, ref);
                return null;
            }
        });
    }

    @Override
    public void wakeConsolidation(RunClaim claim) {
        RecordState state = read(claim.taskId()).state();
        if (!codec.same(state.claims.get(claim.rootRunId()), claim)) {
            throw new IllegalStateException(LlmTexts.RECORDS_CLAIM_MISMATCH);
        }
        ArchivedRun archive = state.archives.get(claim.rootRunId());
        if (archive != null && archive.graphComplete() && archive.evidenceComplete()
                && !archive.internalValidation() && state.pendingArchives.contains(claim.rootRunId())) {
            wake.accept(new ArchiveRef(claim.taskId(), claim.rootRunId()));
        }
    }

    @Override
    public Receipt accept(Command command) {
        Objects.requireNonNull(command);
        return update(command.taskId(), new Change<Receipt>() {
            @Override
            public Receipt apply(RecordState state) {
                return acceptInstruction(state, command);
            }
        });
    }

    @Override
    public Snapshot bind(RunClaim claim) {
        return update(claim.taskId(), new Change<Snapshot>() {
            @Override
            public Snapshot apply(RecordState state) {
                return bindInstructions(state, claim);
            }
        });
    }

    @Override
    public Directive check(RunClaim claim, long instructionRevision) {
        return directive(read(claim.taskId()).state(), claim, instructionRevision);
    }

    @Override
    public boolean applied(RunClaim claim, long instructionRevision, String runId,
                                     long planRevision, Map<String, Object> plan) {
        TaskPlan.requireText(runId, "runId");
        if (planRevision < 0) {
            throw new IllegalArgumentException(LlmTexts.RECORDS_PLAN_REVISION_NEGATIVE);
        }
        Map<String, Object> frozen = TaskPlan.immutableMap(plan);
        return update(claim.taskId(), new Change<Boolean>() {
            @Override
            public Boolean apply(RecordState state) {
                return applyPlan(state, claim, instructionRevision, runId, planRevision, frozen);
            }
        });
    }

    @Override
    public boolean complete(RunClaim claim, long instructionRevision, long expectedRecoveryRevision,
                                      Map<String, Object> outcome) {
        Map<String, Object> frozen = TaskPlan.immutableMap(outcome);
        return update(claim.taskId(), new Change<Boolean>() {
            @Override
            public Boolean apply(RecordState state) {
                return completeTask(state, claim, instructionRevision, expectedRecoveryRevision, frozen);
            }
        });
    }

    @Override
    public List<RecoveryCandidate> recoverable(int limit) {
        List<RecoveryCandidate> result = new ArrayList<>();
        if (limit == 0) {
            return List.of();
        }
        for (RecordState state : scan("recoverable", limit)) {
            if (state.active == null && runnable(state)) {
                result.add(new RecoveryCandidate(state.taskId, LlmTexts.RECORDS_RESUMABLE));
            }
        }
        return List.copyOf(result);
    }

    /** 单页扫描保留原数据库游标；失败候选不会遮住之后的页面。 */
    public record RecoveryPage(
            /** 本页仍可恢复的任务。 */
            List<RecoveryCandidate> candidates,
            /** 下一页原始游标；null 表示扫描结束。 */
            String nextCursor) {
        public RecoveryPage {
            candidates = List.copyOf(candidates);
        }
    }

    public RecoveryPage recoverablePage(int limit, String cursor) {
        StorageQuery.Filter filter = new StorageQuery.Filter(List.of(
                new StorageQuery.Match("/data/kind", List.of(RecordState.ENTITY_KIND)),
                new StorageQuery.Match("/data/recoverable", List.of(true))));
        StorageQuery.Cursor position = cursor == null ? null : new StorageQuery.Cursor(DualWriteStore.Database.NEO4J, cursor);
        StorageQuery.Page page = store.query(DualWriteStore.Database.NEO4J,
                new StorageQuery.PageRequest(filter, limit, position));
        List<RecoveryCandidate> candidates = new ArrayList<>();
        for (WorldEntity entity : page.entities()) {
            RecordState state = codec.decode(entity);
            if (state.active == null && runnable(state)) {
                candidates.add(new RecoveryCandidate(state.taskId, LlmTexts.RECORDS_RESUMABLE));
            }
        }
        return new RecoveryPage(candidates, page.nextCursor() == null ? null : page.nextCursor().position());
    }

    @Override
    public Snapshot query(String taskId) {
        return instructionsSnapshot(read(taskId).state());
    }

    /** 结构化执行事实供日志展示和诊断，不从文本日志反推权威进度。 */
    public List<ExecutionEvent> readEvents(String taskId) {
        return List.copyOf(read(taskId).state().events.values());
    }

    /** 有界事件读取，游标是上一页最后一项的原始事件编号。 */
    public record EventPage(
            /** 同一任务/运行的本页事实，保持提交顺序。 */
            List<ExecutionEvent> events,
            /** 有后续项时返回本页最后事件编号，否则为 null。 */
            String nextCursor) {
        public EventPage {
            events = List.copyOf(events);
        }
    }

    public EventPage readEvents(String taskId, String rootRunId, String afterEventId, int limit) {
        TaskPlan.requireText(rootRunId, "rootRunId");
        if (limit < 1 || limit > StorageQuery.MAX_RESULTS) {
            throw new IllegalArgumentException(LlmTexts.RECORDS_EVENT_PAGE_LIMIT);
        }
        RecordState state = read(taskId).state();
        if (afterEventId != null) {
            ExecutionEvent cursor = state.events.get(afterEventId);
            if (cursor == null || !rootRunId.equals(cursor.rootRunId())) {
                throw new IllegalArgumentException(LlmTexts.RECORDS_EVENT_CURSOR_INVALID);
            }
        }
        List<ExecutionEvent> events = new ArrayList<>();
        boolean after = afterEventId == null;
        for (ExecutionEvent event : state.events.values()) {
            if (!after) {
                if (event.eventId().equals(afterEventId)) {
                    after = true;
                }
                continue;
            }
            if (!rootRunId.equals(event.rootRunId())) {
                continue;
            }
            if (events.size() == limit) {
                return new EventPage(events, events.getLast().eventId());
            }
            events.add(event);
        }
        return new EventPage(events, null);
    }

    /** 宿主只可在核对旧执行已停止后回收这些领取。 */
    public record ActiveRun(
            /** 当前仍占有运行权的凭据，应用展示时不得泄露 token。 */
            RunClaim claim,
            /** 建立领取的宿主身份；null 由外部手工核对。 */
            String ownerId) { }

    public List<ActiveRun> activeRuns(int limit) {
        return findActive(null, limit);
    }

    /** 按宿主身份直接筛选，避免其他宿主的运行占满扫描页。 */
    public List<ActiveRun> activeRuns(String ownerId, int limit) {
        TaskPlan.requireText(ownerId, "ownerId");
        return findActive(ownerId, limit);
    }

    private List<ActiveRun> findActive(String owner, int limit) {
        if (limit == 0) {
            return List.of();
        }
        List<ActiveRun> result = new ArrayList<>();
        for (RecordState state : scan("hasActiveRun", limit, owner)) {
            if (state.active != null && (owner == null || owner.equals(state.activeOwner))) {
                result.add(new ActiveRun(state.active, state.activeOwner));
            }
        }
        return List.copyOf(result);
    }

    /** 没有独立核验服务时，只保留事项，不采信模型的自我声明。 */
    public static ReviewPolicy retainingReviews() {
        return new KeepPendingReviews();
    }

    /**
     * 宿主核对入口：调用方必须已确认旧本地进程停止，并提供证据；不代表远端副作用结束。
     * 此方法不因租约过期自动撤销在途动作；旧领取被 CAS fencing 后不能继续提交。
     */
    public void reconcileStoppedRun(String taskId, String rootRunId, Map<String, Object> evidence) {
        Map<String, Object> frozen = TaskPlan.immutableMap(evidence);
        if (frozen.isEmpty()) {
            throw new IllegalArgumentException(LlmTexts.RECORDS_STOP_EVIDENCE_REQUIRED);
        }
        update(taskId, new Change<Void>() {
            @Override
            public Void apply(RecordState state) {
                reconcileStoppedState(state, taskId, rootRunId, frozen);
                return null;
            }
        });
    }

    /** 登记唯一运行领取，仅移除明确的模型重试标记，保留真实执行问题。 */
    private RunClaim claimRun(RecordState state, RunClaim claim) {
        String rootRunId = claim.rootRunId();
        // 本次提交丢回包后的读回允许识别同一领取，外部重用 rootRunId 仍会拒绝。
        if (codec.same(state.active, claim)) {
            return claim;
        }
        if (state.active != null || state.claims.containsKey(rootRunId)) {
            throw new IllegalStateException(LlmTexts.RECORDS_RUN_ALREADY_CLAIMED);
        }
        if (!runnable(state)) {
            throw new IllegalStateException(LlmTexts.RECORDS_NOT_RESUMABLE);
        }
        state.active = claim;
        state.activeOwner = ownerId;
        state.claims.put(rootRunId, claim);
        // 新领取是一次明确的新模型尝试，历史模型调用失败仍在事件中保留。
        // 这里只移除等待重试标记，不清除任何实际效果/验收事项。
        if (waitingForModel(state)) {
            for (Iterator<RecoveryItem> it = state.incidents.values().iterator(); it.hasNext();) {
                if (RecordState.WAITING_MODEL.equals(it.next().state())) {
                    it.remove();
                }
            }
            state.disposition = state.incidents.isEmpty() ? Disposition.CONTINUE : Disposition.CONSULT_MODEL;
            state.reason = LlmTexts.RECORDS_MODEL_RETRY;
            state.recoveryRevision++;
        }
        return claim;
    }

    /** 先识别旧事件重放，再校验新事件领取并更新事实；不释放当前领取。 */
    private void appendClaimedEvent(RecordState state, RunClaim claim, ExecutionEvent event) {
        // 已提交旧事件重放不要求旧领取仍活跃，但仍验证原领取凭据与完整内容。
        if (state.events.containsKey(event.eventId())) {
            if (!codec.same(state.claims.get(claim.rootRunId()), claim)) {
                throw new IllegalStateException(LlmTexts.RECORDS_CLAIM_MISMATCH);
            }
            acceptEvent(state, event);
            return;
        }
        active(state, claim);
        acceptEvent(state, event);
    }

    /** 归档、结束事实、释放领取和固化待办在同一聚合提交。 */
    private void finalizeRun(RecordState state, RunClaim claim, RunFinalization finalization) {
        ArchivedRun existing = state.archives.get(claim.rootRunId());
        if (existing != null) {
            if (!codec.same(state.claims.get(claim.rootRunId()), claim)
                    || !codec.same(existing.endEvent(), finalization.event())
                    || !codec.same(existing.graph(), finalization.graph())
                    || existing.graphComplete() != finalization.graphComplete()
                    || !codec.same(existing.gaps(), finalization.gaps())
                    || !codec.same(existing.evidenceGaps(), finalization.evidenceGaps())) {
                throw new IllegalStateException(LlmTexts.RECORDS_ARCHIVE_IMMUTABLE);
            }
            return;
        }
        active(state, claim);
        acceptEvent(state, finalization.event());
        markUnreturnedIntents(state, claim.rootRunId());
        reconcileFinalOutcome(state, claim, finalization);
        List<ExecutionEvent> events = runEvents(state, claim.rootRunId());
        ArchivedRun archive = new ArchivedRun(new ArchiveRef(state.taskId, claim.rootRunId()), RunArchivePort.SCHEMA_VERSION,
                finalization.event(), finalization.graph(), finalization.graphComplete(),
                finalization.gaps(), events, state.task.internalValidation(), finalization.evidenceGaps());
        state.archives.put(claim.rootRunId(), archive);
        if (archive.graphComplete() && archive.evidenceComplete() && !archive.internalValidation()) {
            state.pendingArchives.add(claim.rootRunId());
        }
        state.active = null;
        state.activeOwner = null;
    }

    /** 确认消费只移除持久待办，不修改已封存归档。 */
    private void acknowledgeArchive(RecordState state, ArchiveRef ref) {
        if (!state.archives.containsKey(ref.rootRunId())) {
            throw new IllegalArgumentException(LlmTexts.RECORDS_ARCHIVE_MISSING);
        }
        state.pendingArchives.remove(ref.rootRunId());
    }

    /** 接收指令及其版本，保留运行期间的中断事实。 */
    private Receipt acceptInstruction(RecordState state, Command command) {
        Command old = state.commands.get(command.commandId());
        if (old != null) {
            if (!codec.same(old, command)) {
                throw new IllegalArgumentException(LlmTexts.RECORDS_COMMAND_CONFLICT);
            }
            return state.receipts.get(command.commandId());
        }
        if (command.expectedRevision() != state.instructionRevision) {
            throw new IllegalStateException(LlmTexts.RECORDS_INSTRUCTION_STALE);
        }
        state.instructionRevision++;
        Receipt receipt = new Receipt(state.taskId, command.commandId(), state.instructionRevision, command.kind());
        state.commands.put(command.commandId(), command);
        state.receipts.put(command.commandId(), receipt);
        state.latestKind = command.kind();
        state.completed = false;
        // 明确继续/修改可请求重新判断，但不能自行关闭现有待办。
        if ((command.kind() == Kind.CONTINUE || command.kind() == Kind.AMEND)
                && state.disposition == Disposition.WAIT) {
            state.disposition = state.incidents.isEmpty() ? Disposition.CONTINUE : Disposition.CONSULT_MODEL;
            state.recoveryRevision++;
        }
        if (state.active != null) {
            RunClaim claim = state.active;
            acceptEvent(state, new ExecutionEvent("instruction-" + command.commandId(), state.taskId,
                    claim.rootRunId(), claim.rootRunId(), null, null, 0, EventKind.RUN_INTERRUPTION_REQUESTED,
                    Map.of("commandId", command.commandId(), "instructionRevision", state.instructionRevision,
                            "kind", command.kind().name(), "text", command.text())));
        }
        return receipt;
    }

    /** 每次运行固定一份指令快照，后续请求复用同一绑定。 */
    private Snapshot bindInstructions(RecordState state, RunClaim claim) {
        active(state, claim);
        Snapshot bound = state.bindings.get(claim.rootRunId());
        if (bound != null) {
            return bound;
        }
        if (!runnable(state)) {
            throw new IllegalStateException(LlmTexts.RECORDS_BIND_FORBIDDEN);
        }
        Snapshot snapshot = instructionsSnapshot(state);
        state.bindings.put(claim.rootRunId(), snapshot);
        return snapshot;
    }

    /** 在当前领取和指令版本仍有效时登记计划，不覆盖同身份的不同内容。 */
    private boolean applyPlan(RecordState state, RunClaim claim, long instructionRevision,
            String runId, long planRevision, Map<String, Object> frozen) {
        if (directive(state, claim, instructionRevision) != Directive.PROCEED) {
            return false;
        }
        // JSON 数组序列化身份，避免 ID 包含分隔符造成冲突。
        String key = codec.encode(List.of(claim.rootRunId(), runId, planRevision));
        Map<String, Object> previous = state.plans.get(key);
        if (previous != null && !codec.same(previous, frozen)) {
            throw new IllegalArgumentException(LlmTexts.RECORDS_PLAN_CONFLICT);
        }
        state.plans.put(key, frozen);
        state.appliedRevision = instructionRevision;
        return true;
    }

    /** 只有已验收、无未核对事项且版本仍有效的结果才能提交根完成。 */
    private boolean completeTask(RecordState state, RunClaim claim, long instructionRevision,
            long expectedRecoveryRevision, Map<String, Object> frozen) {
        if (state.completed && Objects.equals(state.completedRun, claim.rootRunId())
                && state.instructionRevision == instructionRevision && codec.same(state.completion, frozen)) {
            return codec.same(state.active, claim);
        }
        if (directive(state, claim, instructionRevision) != Directive.PROCEED
                || state.recoveryRevision != expectedRecoveryRevision
                || state.disposition != Disposition.CONTINUE || !state.incidents.isEmpty()
                || !RecordState.SUCCESS.equals(frozen.get("status"))) {
            return false;
        }
        state.completed = true;
        state.completedRun = claim.rootRunId();
        state.completion = frozen;
        state.appliedRevision = instructionRevision;
        return true;
    }

    /** 宿主确认旧本地执行终止后封存不完整归档，未确认的远端影响继续保留。 */
    private void reconcileStoppedState(RecordState state, String taskId, String rootRunId, Map<String, Object> frozen) {
        ExecutionEvent previous = state.events.get("stopped-evidence-" + rootRunId);
        if (previous != null) {
            if (!codec.same(previous.details().get("evidence"), frozen)) {
                throw new IllegalArgumentException(LlmTexts.RECORDS_STOP_EVIDENCE_CONFLICT);
            }
            if (!state.archives.containsKey(rootRunId)) {
                throw new IllegalStateException(LlmTexts.RECORDS_STOP_ARCHIVE_INCOMPLETE);
            }
            return;
        }
        if (state.active == null || !state.active.rootRunId().equals(rootRunId)) {
            throw new IllegalStateException(LlmTexts.RECORDS_STOP_CLAIM_CHANGED);
        }
        acceptEvent(state, new ExecutionEvent("stopped-evidence-" + rootRunId, taskId, rootRunId,
                rootRunId, null, null, 0, EventKind.OBSERVED, Map.of("phase", RecordState.STOP_CONFIRMED, "evidence", frozen)));
        ExecutionEvent end = new ExecutionEvent("reconciled-end-" + rootRunId, taskId, rootRunId,
                rootRunId, null, null, 0, EventKind.RUN_ENDED,
                Map.of("outcome", Map.of("status", RecordState.UNKNOWN, "error", LlmTexts.RECORDS_UNCONFIRMED_EFFECT)));
        acceptEvent(state, end);
        markUnreturnedIntents(state, rootRunId);
        state.archives.put(rootRunId, new ArchivedRun(new ArchiveRef(taskId, rootRunId), RunArchivePort.SCHEMA_VERSION,
                end, null, false, List.of(RecordState.PROCESS_INTERRUPTED_NO_FINAL_GRAPH), runEvents(state, rootRunId),
                state.task.internalValidation()));
        state.active = null;
        state.activeOwner = null;
        // 缺少终图不推翻已提交的根完成；实际未确认动作由上面的核对置为未完成。
    }

    /** 外部核对策略返回经过独立验证的事项集合，不直接采用模型的 resolved 字段。 */
    public interface ReviewPolicy {
        ReviewDecision review(RecoverySnapshot snapshot, ResolutionProposal proposal);
    }

    public record ReviewDecision(
            /** 已由独立策略确认解决的事项；只能是提案涉及的子集。 */
            Set<String> approvedIds,
            /** 审核依据或未通过原因。 */
            String reason,
            /** 独立审核明确给出的接续决定，不能由仓储按事项数量改写。 */
            Disposition disposition) {
        public ReviewDecision {
            Objects.requireNonNull(disposition);
            approvedIds = Set.copyOf(approvedIds);
            TaskPlan.requireText(reason, "reason");
        }
    }

    private static final class KeepPendingReviews implements ReviewPolicy {
        @Override
        public ReviewDecision review(RecoverySnapshot snapshot, ResolutionProposal proposal) {
            return new ReviewDecision(Set.of(), LlmTexts.RECORDS_PENDING_REVIEW, Disposition.WAIT);
        }
    }
    private static final class NoWake implements Consumer<ArchiveRef> {
        @Override
        public void accept(ArchiveRef ref) {
            /* 待办持久存在，可由应用有界轮询。 */
        }
    }

    private Directive directive(RecordState state, RunClaim claim, long revision) {
        if (!codec.same(state.active, claim)) {
            return Directive.WAIT;
        }
        Snapshot bound = state.bindings.get(claim.rootRunId());
        if (bound == null || bound.revision() != revision) {
            return Directive.WAIT;
        }
        if (state.latestKind == Kind.PAUSE) {
            return Directive.PAUSE;
        }
        if (state.latestKind == Kind.CANCEL) {
            return Directive.CANCEL;
        }
        if (state.instructionRevision != revision) {
            return Directive.REPLAN;
        }
        return state.completed ? Directive.WAIT : Directive.PROCEED;
    }

    private static Snapshot instructionsSnapshot(RecordState state) {
        return new Snapshot(state.instructionRevision, state.appliedRevision, List.copyOf(state.commands.values()), state.latestKind);
    }

    private RecoverySnapshot recovery(RecordState state) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (ExecutionEvent event : state.events.values()) {
            switch (event.kind()) {
                case INTENT, RETURNED, OBSERVED, VERIFIED -> {
                    Map<String, Object> fact = new LinkedHashMap<>();
                    fact.put("eventId", event.eventId());
                    fact.put("rootRunId", event.rootRunId());
                    fact.put("nodeId", event.nodeId());
                    fact.put("kind", event.kind().name());
                    fact.put("details", event.details());
                    facts.add(fact);
                }
                default -> { }
            }
        }
        return new RecoverySnapshot(state.recoveryRevision, state.disposition,
                List.copyOf(state.incidents.values()), Map.of("executionFacts", facts), state.reason);
    }

    private void acceptEvent(RecordState state, ExecutionEvent event) {
        ExecutionEvent old = state.events.get(event.eventId());
        if (old != null) {
            if (!codec.same(old, event)) {
                throw new IllegalArgumentException(LlmTexts.RECORDS_EVENT_CONFLICT);
            }
            return;
        }
        if (event.kind() == EventKind.INTENT) {
            rejectUnreviewedReplay(state, event);
        }
        state.events.put(event.eventId(), event);
        state.eventOrder.add(event.eventId());
        switch (event.kind()) {
            case RETURNED, VERIFIED -> {
                state.recoveryRevision++;
                Object output = event.details().get("outcome");
                if (!Boolean.FALSE.equals(event.details().get("dispatchStarted"))
                        && output instanceof Map<?, ?> value
                        && (RecordState.FAILED.equals(value.get("status")) || RecordState.UNKNOWN.equals(value.get("status")))) {
                    String id = "effect-" + event.rootRunId() + "-" + event.nodeId();
                    state.incidents.put(id, new RecoveryItem(id, RecordState.OPEN, LlmTexts.RECORDS_EXECUTION_REVIEW,
                            Map.of("eventId", event.eventId(), "rootRunId", event.rootRunId(), "facts", event.details())));
                    state.disposition = Disposition.CONSULT_MODEL;
                    state.reason = LlmTexts.RECORDS_EXECUTION_REVIEW;
                }
            }
            case OBSERVED -> state.recoveryRevision++;
            case MODEL_FAILED -> {
                String id = "model-" + event.eventId();
                state.incidents.put(id, new RecoveryItem(id, RecordState.WAITING_MODEL, LlmTexts.RECORDS_MODEL_RETRY, event.details()));
                state.disposition = Disposition.WAIT;
                state.reason = LlmTexts.RECORDS_MODEL_RETRY;
                state.recoveryRevision++;
            }
            default -> {
                // 模型调用元数据、派发意图和收尾不使模型刚取得的审核依据自动过期。
            }
        }
    }

    // 根完成提交后还可能发生清理故障；结束事实不能继续伪装完全成功。
    private void reconcileFinalOutcome(RecordState state, RunClaim claim, RunFinalization finalization) {
        Object raw = finalization.event().details().get("outcome");
        String status = raw instanceof Map<?, ?> outcome ? String.valueOf(outcome.get("status")) : RecordState.UNKNOWN;
        boolean changedAfterCompletion = Objects.equals(state.completedRun, claim.rootRunId()) && !RecordState.SUCCESS.equals(status);
        if (changedAfterCompletion) {
            state.completed = false;
        }
        if (RecordState.UNKNOWN.equals(status) || changedAfterCompletion || !finalization.evidenceComplete()) {
            String id = "final-" + claim.rootRunId();
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("eventId", finalization.event().eventId());
            evidence.put("outcome", raw);
            evidence.put("evidenceGaps", finalization.evidenceGaps());
            state.incidents.put(id, new RecoveryItem(id, RecordState.OPEN, LlmTexts.RECORDS_EXECUTION_REVIEW, evidence));
            state.disposition = Disposition.CONSULT_MODEL;
            state.reason = LlmTexts.RECORDS_EXECUTION_REVIEW;
            state.recoveryRevision++;
            state.completed = false;
        }
    }

    /** 策略只读取不可变快照；拒绝与事件写入共用当前 CAS 聚合。 */
    private void rejectUnreviewedReplay(RecordState state, ExecutionEvent requested) {
        ToolReplayPolicy.Context context = new ToolReplayPolicy.Context(
                requested, List.copyOf(state.incidents.values()), List.copyOf(state.events.values()));
        ToolReplayPolicy.Decision decision = Objects.requireNonNull(replayPolicy.check(context));
        if (!decision.allowed()) {
            throw new IllegalStateException(decision.reason());
        }
    }

    /** 终止后按实际 operation 身份核对，不使用可被下一版计划复用的 stepId。 */
    private void markUnreturnedIntents(RecordState state, String root) {
        Map<String, ExecutionEvent> returned = new LinkedHashMap<>();
        Set<String> verified = new HashSet<>();
        for (ExecutionEvent event : state.events.values()) {
            if (!root.equals(event.rootRunId())) {
                continue;
            }
            if (event.kind() == EventKind.RETURNED) {
                returned.put(event.nodeId(), event);
            }
            if (event.kind() == EventKind.VERIFIED) {
                verified.add(event.nodeId());
            }
        }
        for (ExecutionEvent intent : state.events.values()) {
            if (!root.equals(intent.rootRunId()) || intent.kind() != EventKind.INTENT) {
                continue;
            }
            ExecutionEvent result = returned.get(intent.nodeId());
            // 中断发生在派发前已有明确事实，不能将它当成未知远端影响。
            if (result != null && Boolean.FALSE.equals(result.details().get("dispatchStarted"))) {
                continue;
            }
            boolean missingReturn = result == null;
            Object outcome = result == null ? null : result.details().get("outcome");
            boolean unverifiedSuccess = outcome instanceof Map<?, ?> value
                    && RecordState.SUCCESS.equals(value.get("status")) && !verified.contains(intent.nodeId());
            if (!missingReturn && !unverifiedSuccess) {
                continue;
            }
            String id = (missingReturn ? "unconfirmed-" : "unverified-") + root + "-" + intent.nodeId();
            if (state.incidents.containsKey(id)) {
                continue;
            }
            String reason = missingReturn ? LlmTexts.RECORDS_UNCONFIRMED_EFFECT : LlmTexts.RECORDS_EXECUTION_REVIEW;
            ExecutionEvent source = missingReturn ? intent : result;
            state.incidents.put(id, new RecoveryItem(id, missingReturn ? RecordState.UNCONFIRMED : RecordState.UNVERIFIED, reason,
                    Map.of("eventId", source.eventId(), "rootRunId", root, "facts", source.details())));
            state.disposition = Disposition.CONSULT_MODEL;
            state.reason = reason;
            state.recoveryRevision++;
            state.completed = false;
        }
    }

    private static List<ExecutionEvent> runEvents(RecordState state, String root) {
        List<ExecutionEvent> result = new ArrayList<>();
        for (ExecutionEvent event : state.events.values()) {
            if (root.equals(event.rootRunId())) {
                result.add(event);
            }
        }
        return List.copyOf(result);
    }

    private static boolean waitingForModel(RecordState state) {
        for (RecoveryItem item : state.incidents.values()) {
            if (RecordState.WAITING_MODEL.equals(item.state())) {
                return true;
            }
        }
        return false;
    }

    private static boolean runnable(RecordState state) {
        return !state.completed && state.latestKind != Kind.PAUSE && state.latestKind != Kind.CANCEL
                && (state.disposition != Disposition.WAIT || waitingForModel(state));
    }

    private void active(RecordState state, RunClaim claim) {
        if (!codec.same(state.active, claim)) {
            throw new IllegalStateException(LlmTexts.RECORDS_CLAIM_EXPIRED);
        }
    }

    private static void validateEvent(RunClaim claim, ExecutionEvent event) {
        Objects.requireNonNull(event);
        if (!claim.taskId().equals(event.taskId()) || !claim.rootRunId().equals(event.rootRunId())) {
            throw new IllegalArgumentException(LlmTexts.RECORDS_EVENT_CLAIM_MISMATCH);
        }
    }

    private <T> T update(String taskId, Change<T> change) {
        for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
            Loaded loaded = read(taskId);
            T result = change.apply(loaded.state());
            WorldEntity next = entity(loaded.state(), loaded.entity());
            // 幂等读与被拒操作不增加版本，也不覆盖并发更新。
            if (codec.same(next.data(), loaded.entity().data())) {
                return result;
            }
            try {
                if (store.compareAndSet(DualWriteStore.Database.NEO4J, loaded.entity(), next)) {
                    return result;
                }
            } catch (RuntimeException uncertain) {
                // 回包丢失可能已经提交。仅在读回后该幂等动作不再改变状态时确认成功。
                try {
                    Loaded observed = read(taskId);
                    T repeated = change.apply(observed.state());
                    if (codec.same(entity(observed.state(), observed.entity()).data(), observed.entity().data())) {
                        return repeated;
                    }
                } catch (RuntimeException verificationFailure) {
                    uncertain.addSuppressed(verificationFailure);
                }
                throw uncertain;
            }
        }
        throw new IllegalStateException(LlmTexts.RECORDS_CAS_EXHAUSTED);
    }

    private Loaded read(String taskId) {
        WorldEntity entity = store.read(DualWriteStore.Database.NEO4J, taskId)
                .orElseThrow(new MissingTask(taskId));
        return new Loaded(entity, codec.decode(entity));
    }

    /** 仓储决定查询投影，编解码器只组装同一个持久实体。 */
    private WorldEntity entity(RecordState state, WorldEntity old) {
        return codec.entity(state, old, state.active == null && runnable(state));
    }

    private List<RecordState> scan(String field, int limit) {
        return scan(field, limit, null);
    }

    private List<RecordState> scan(String field, int limit, String owner) {
        if (limit < 1 || limit > StorageQuery.MAX_RESULTS) {
            throw new IllegalArgumentException(LlmTexts.RECORDS_SCAN_LIMIT);
        }
        List<StorageQuery.Match> matches = new ArrayList<>();
        matches.add(new StorageQuery.Match("/data/kind", List.of(RecordState.ENTITY_KIND)));
        matches.add(new StorageQuery.Match("/data/" + field, List.of(true)));
        if (owner != null) {
            matches.add(new StorageQuery.Match("/data/activeOwner", List.of(owner)));
        }
        StorageQuery.Filter filter = new StorageQuery.Filter(matches);
        List<RecordState> result = new ArrayList<>();
        StorageQuery.Cursor cursor = null;
        do {
            StorageQuery.Page page = store.query(DualWriteStore.Database.NEO4J,
                    new StorageQuery.PageRequest(filter, Math.min(SCAN_PAGE_SIZE, limit - result.size()), cursor));
            for (WorldEntity entity : page.entities()) {
                result.add(codec.decode(entity));
            }
            cursor = page.nextCursor();
        } while (cursor != null && result.size() < limit);
        return result;
    }

    private interface Change<T> {
        T apply(RecordState state);
    }
    /** 一次读取的完整 CAS 依据及其独立可变业务副本。 */
    private record Loaded(
            /** 数据库返回的原实体，提交时必须原样作为 expected。 */
            WorldEntity entity,
            /** 仅供本次变更使用的反序列化状态，不跨调用缓存。 */
            RecordState state) {
    }
    private static final class MissingTask implements Supplier<IllegalArgumentException> {
        /** 仅用于诊断未知任务，不包含访问凭据。 */
        private final String id;
        private MissingTask(String id) {
            this.id = id;
        }
        @Override
        public IllegalArgumentException get() {
            return new IllegalArgumentException(LlmTexts.RECORDS_TASK_MISSING_PREFIX + id);
        }
    }

}

