package com.example.myhelper.records;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.orchestration.ExecutionTraceStore;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.EntityJson;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.records.TaskRecordPort.*;
import com.example.myhelper.records.TaskInstructionPort.*;
import com.example.myhelper.records.RunArchivePort.*;
import com.example.myhelper.support.TestModelAdapter;
import com.example.myhelper.world.model.WorldEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

/** 真实记录仓储经统一存储和 JSON 往返；内存参与者仅模拟数据库原子边界。 */
class StoredTaskRecordsTest {
    /** 稳定时间避免同一事件的重试被检查代码改写。 */
    private static final String AT = "2026-11-17T01:00:00Z";

    @Test
    void newInstanceReadsOriginalTaskEventsAndNormalizedDuplicatePayload() {
        MemoryParticipant database = new MemoryParticipant();
        StoredTaskRecords first = repository(database);
        TaskSnapshot task = first.create(spec(false));
        RunClaim claim = start(first, task.taskId(), "run-one");
        ExecutionEvent original = event(claim, "observed", EventKind.OBSERVED, Map.of("count", 1L));
        first.append(claim, original);
        StoredTaskRecords second = repository(database);
        assertEquals("original-goal", second.load(task.taskId()).task().goal());
        assertEquals(1, second.readEvents(task.taskId()).size());
        second.append(claim, event(claim, "observed", EventKind.OBSERVED, Map.of("count", 1)));
        assertEquals(1, first.readEvents(task.taskId()).size());
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override public void execute() {
                second.append(claim, event(claim, "observed", EventKind.OBSERVED, Map.of("count", 2)));
            }
        });
        assertEquals(1, ((Number) second.readEvents(task.taskId()).getFirst().details().get("count")).intValue());
    }

    @Test
    void separateRepositoryInstancesCannotAcquireSameTaskConcurrently() throws Exception {
        MemoryParticipant database = new MemoryParticipant();
        StoredTaskRecords first = repository(database);
        StoredTaskRecords second = repository(database);
        String taskId = first.create(spec(false)).taskId();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<RunClaim> a = CompletableFuture.supplyAsync(
                    new ClaimAttempt(first, taskId, "a", ready, release), executor);
            CompletableFuture<RunClaim> b = CompletableFuture.supplyAsync(
                    new ClaimAttempt(second, taskId, "b", ready, release), executor);
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            release.countDown();
            RunClaim left = a.get(5, TimeUnit.SECONDS);
            RunClaim right = b.get(5, TimeUnit.SECONDS);
            assertEquals(1, (left == null ? 0 : 1) + (right == null ? 0 : 1));
            assertEquals(1, second.activeRuns(10).size());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void commandsDeduplicateAndPauseCancelContinuePreserveLateFacts() {
        MemoryParticipant database = new MemoryParticipant();
        StoredTaskRecords records = repository(database);
        String taskId = records.create(spec(false)).taskId();
        RunClaim old = start(records, taskId, "old");
        Command pause = new Command(taskId, "pause", 0, Kind.PAUSE, "pause");
        Receipt receipt = records.accept(pause);
        assertEquals(receipt, repository(database).accept(pause));
        assertEquals(Directive.PAUSE, records.check(old, 0));
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override public void execute() {
                records.accept(new Command(taskId, "pause", 0, Kind.CANCEL, "different"));
            }
        });
        assertFalse(records.complete(old, 0, records.query(old, "root").revision(), outcome("SUCCESS")));
        records.append(old, event(old, "late", EventKind.RETURNED, Map.of("outcome", outcome("SUCCESS"))));
        records.endRun(old, finalization(old, List.of()));
        assertTrue(records.recoverable(10).isEmpty(), "暂停不能自动继续原目标");
        records.accept(new Command(taskId, "cancel", 1, Kind.CANCEL, "cancel"));
        assertTrue(repository(database).recoverable(10).isEmpty());
        records.accept(new Command(taskId, "continue", 2, Kind.CONTINUE, "continue"));
        StoredTaskRecords reopened = repository(database);
        assertEquals(3, reopened.query(taskId).revision());
        assertEquals(Kind.CONTINUE, reopened.query(taskId).latestKind());
        assertEquals(1, reopened.recoverable(10).size());
        RunClaim next = start(reopened, taskId, "new");
        assertEquals(3, reopened.bind(next).revision());
        assertEquals(Directive.WAIT, reopened.check(old, 0));
    }

    @Test
    void modelMetadataDoesNotInvalidateRecoveryEvidenceVersion() {
        StoredTaskRecords records = repository(new MemoryParticipant());
        RunClaim claim = start(records, records.create(spec(false)).taskId(), "run");
        records.append(claim, event(claim, "failed-return", EventKind.RETURNED,
                Map.of("outcome", outcome("FAILED"))));
        RecoverySnapshot snapshot = records.query(claim, "root");
        for (EventKind kind : List.of(EventKind.MODEL_TASK_ASSIGNED, EventKind.MODEL_CALL_STARTED,
                EventKind.MODEL_CALL_RETURNED)) {
            records.append(claim, event(claim, kind.name(), kind, Map.of()));
        }
        assertEquals(snapshot.revision(), records.query(claim, "root").revision());
        List<String> ids = new ArrayList<>();
        for (RecoveryItem item : snapshot.pending()) { ids.add(item.id()); }
        assertFalse(ids.isEmpty());
        RecoverySnapshot reviewed = records.review(claim,
                new ResolutionProposal("root", snapshot.revision(), ids, Map.of("resolved", true)));
        assertFalse(reviewed.pending().isEmpty(), "默认策略不能相信模型自称已解决");
        assertEquals(Disposition.WAIT, reviewed.disposition());
    }

    @Test
    void unknownIntentRequiresExplicitReconciliationAndCannotBeBlindlyReplayed() {
        MemoryParticipant database = new MemoryParticipant();
        StoredTaskRecords records = repository(database);
        RunClaim old = start(records, records.create(spec(false)).taskId(), "lost-run");
        Map<String, Object> intention = Map.of("tool", "write", "arguments", Map.of("file", "result.txt"));
        records.append(old, event(old, "intent", EventKind.INTENT, intention));
        StoredTaskRecords reopened = repository(database);
        assertThrows(IllegalStateException.class, new Executable() {
            @Override public void execute() { reopened.beginRun(old.taskId(), "unsafe"); }
        });
        reopened.reconcileStoppedRun(old.taskId(), old.rootRunId(),
                Map.of("oldExecutionStopped", true, "evidence", "process-was-confirmed-stopped"));
        RunClaim next = start(reopened, old.taskId(), "recovery");
        RecoverySnapshot pending = reopened.query(next, "root");
        assertEquals(Disposition.CONSULT_MODEL, pending.disposition());
        assertFalse(pending.pending().isEmpty());
        assertThrows(IllegalStateException.class, new Executable() {
            @Override public void execute() {
                reopened.append(next, event(next, "replayed-intent", EventKind.INTENT, intention));
            }
        });
        assertEquals(Directive.WAIT, reopened.check(old, 0));
    }

    @Test
    void finalArchivePendingAndClaimReleaseAreOneCommitAndOldReplayCannotReleaseNewClaim() {
        MemoryParticipant database = new MemoryParticipant();
        AtomicInteger wakes = new AtomicInteger();
        StoredTaskRecords records = repository(database, wakes);
        RunClaim old = start(records, records.create(spec(false)).taskId(), "old");
        records.append(old, event(old, "observed", EventKind.OBSERVED, Map.of("evidence", "v2")));
        RunFinalization end = finalization(old, List.of());
        int commits = database.commits;
        records.endRun(old, end);
        assertEquals(commits + 1, database.commits, "最终归档、领取释放与待办须同次条件写");
        StoredTaskRecords reopened = repository(database, wakes);
        ArchiveRef ref = new ArchiveRef(old.taskId(), old.rootRunId());
        ArchivedRun saved = reopened.readArchive(ref).orElseThrow();
        assertTrue(saved.graphComplete());
        assertTrue(saved.evidenceComplete());
        assertEquals(2, saved.events().size());
        assertEquals(List.of(ref), reopened.pendingConsolidations(10));
        assertTrue(reopened.activeRuns(10).isEmpty());
        reopened.wakeConsolidation(old);
        assertEquals(1, wakes.get());
        RunClaim next = start(reopened, old.taskId(), "new");
        reopened.endRun(old, end);
        assertEquals(next, reopened.activeRuns(10).getFirst().claim());
        reopened.acknowledgeConsolidation(ref);
        reopened.acknowledgeConsolidation(ref);
        assertTrue(reopened.pendingConsolidations(10).isEmpty());
        assertEquals(saved, reopened.readArchive(ref).orElseThrow());
    }

    @Test
    void incompleteEvidenceAndInternalRunsAreArchivedWithoutConsolidationTodo() {
        for (boolean internal : List.of(false, true)) {
            MemoryParticipant database = new MemoryParticipant();
            AtomicInteger wakes = new AtomicInteger();
            StoredTaskRecords records = repository(database, wakes);
            RunClaim claim = start(records, records.create(spec(internal)).taskId(), "run");
            List<String> evidenceGaps = internal ? List.of() : List.of("missing-return-event");
            records.endRun(claim, finalization(claim, evidenceGaps));
            records.wakeConsolidation(claim);
            ArchivedRun archive = repository(database).readArchive(
                    new ArchiveRef(claim.taskId(), claim.rootRunId())).orElseThrow();
            assertTrue(archive.graphComplete());
            assertEquals(evidenceGaps.isEmpty(), archive.evidenceComplete());
            assertEquals(internal, archive.internalValidation());
            assertTrue(records.pendingConsolidations(10).isEmpty());
            assertEquals(0, wakes.get());
        }
    }

    @Test
    void failedWriteIsNotVisibleAndCommittedButLostReplyCanBeRetriedIdempotently() {
        MemoryParticipant database = new MemoryParticipant();
        StoredTaskRecords records = repository(database);
        RunClaim claim = start(records, records.create(spec(false)).taskId(), "run");
        RunFinalization end = finalization(claim, List.of());
        ArchiveRef ref = new ArchiveRef(claim.taskId(), claim.rootRunId());
        database.failBefore = true;
        assertThrows(IllegalStateException.class, new Executable() {
            @Override public void execute() { records.endRun(claim, end); }
        });
        assertTrue(repository(database).readArchive(ref).isEmpty());
        assertEquals(1, records.activeRuns(10).size());
        database.failBefore = false;
        database.commitThenThrow = true;
        try {
            records.endRun(claim, end);
        } catch (IllegalStateException lostReply) {
            // 允许仓储直接报告未知；用稳定信封重试确认，同内容必须成功。
        }
        StoredTaskRecords reopened = repository(database);
        reopened.endRun(claim, end);
        assertEquals(1, reopened.readArchive(ref).orElseThrow().events().size());
        assertEquals(List.of(ref), reopened.pendingConsolidations(10));
        assertTrue(reopened.activeRuns(10).isEmpty());
    }

    @Test
    void realOrchestratorResumesPersistedTaskAfterModelFailureOrApplicationShutdown() throws Exception {
        for (boolean shutdown : List.of(false, true)) {
            MemoryParticipant database = new MemoryParticipant();
            StoredTaskRecords first = repository(database);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AgentOrchestrator.Planner failing = new AgentOrchestrator.Planner() {
                @Override public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                    entered.countDown();
                    if (shutdown) {
                        await(release);
                        return completed();
                    }
                    throw new IllegalStateException("model-offline");
                }
            };
            AgentOrchestrator.Handle old;
            try (Runtime firstRuntime = new Runtime(first, failing)) {
                old = firstRuntime.orchestrator.submit(
                        new AgentOrchestrator.Request("original-goal", Map.of("version", 7), "A", List.of()));
                if (shutdown) {
                    assertTrue(entered.await(3, TimeUnit.SECONDS));
                    firstRuntime.orchestrator.stopForShutdown(old.taskId(), old.rootRunId());
                    release.countDown();
                }
                old.result().get(5, TimeUnit.SECONDS);
                old.settled().get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            StoredTaskRecords restarted = repository(database);
            assertEquals(Kind.CONTINUE, restarted.query(old.taskId()).latestKind(),
                    "应用退出不能把用户目标保存为 CANCEL");
            assertEquals(1, restarted.recoverable(10).size());
            AgentOrchestrator.Planner resumed = new AgentOrchestrator.Planner() {
                @Override public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
                    assertEquals("original-goal", context.goal());
                    assertEquals(7, context.arguments().get("version"));
                    return completed();
                }
            };
            try (Runtime nextRuntime = new Runtime(restarted, resumed)) {
                AgentOrchestrator.Handle next = nextRuntime.orchestrator.resume(old.taskId());
                assertEquals(AgentOrchestrator.Status.SUCCESS,
                        next.result().get(5, TimeUnit.SECONDS).status());
                next.settled().get(5, TimeUnit.SECONDS);
                assertEquals(old.taskId(), next.taskId());
                assertNotEquals(old.rootRunId(), next.rootRunId());
                assertTrue(restarted.recoverable(10).isEmpty());
                assertTrue(restarted.readArchive(new ArchiveRef(next.taskId(), next.rootRunId())).isPresent());
                assertFalse(restarted.readEvents(next.taskId()).isEmpty());
            }
        }
    }

    @Test
    void runtimeEventPagesKeepRunOrderAndRejectForeignCursorWhileOwnersStaySeparate() {
        MemoryParticipant database = new MemoryParticipant();
        Consumer<ArchiveRef> noWake = new Consumer<ArchiveRef>() {
            @Override public void accept(ArchiveRef ignored) { }
        };
        StoredTaskRecords records = new StoredTaskRecords(store(database),
                StoredTaskRecords.retainingReviews(), noWake, "local-owner");
        StoredTaskRecords other = new StoredTaskRecords(store(database),
                StoredTaskRecords.retainingReviews(), noWake, "other-owner");
        RunClaim first = start(records, records.create(spec(false)).taskId(), "first");
        // 故意用逆字母顺序的身份，读取必须保持提交顺序。
        records.append(first, event(first, "z-first", EventKind.OBSERVED, Map.of()));
        records.append(first, event(first, "m-second", EventKind.OBSERVED, Map.of()));
        records.append(first, event(first, "a-third", EventKind.OBSERVED, Map.of()));
        records.endRun(first, finalization(first, List.of()));
        RunClaim next = start(records, first.taskId(), "next");
        records.append(next, event(next, "foreign-cursor", EventKind.OBSERVED, Map.of()));
        RunClaim otherClaim = start(other, other.create(spec(false)).taskId(), "other");
        var page = repository(database).readEvents(first.taskId(), first.rootRunId(), null, 2);
        assertEquals(List.of("z-first", "m-second"), eventIds(page.events()));
        assertEquals("m-second", page.nextCursor());
        var last = records.readEvents(first.taskId(), first.rootRunId(), page.nextCursor(), 2);
        assertEquals(List.of("a-third", "end"), eventIds(last.events()));
        assertNull(last.nextCursor());
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override public void execute() {
                records.readEvents(first.taskId(), first.rootRunId(), "foreign-cursor", 2);
            }
        });
        assertEquals(next, records.activeRuns("local-owner", 1).getFirst().claim());
        assertEquals(otherClaim, records.activeRuns("other-owner", 1).getFirst().claim());
    }

    @Test
    void crashAfterCompletionPreservesCompletionOnlyWhenToolEvidenceIsVerified() {
        for (boolean verified : List.of(false, true)) {
            MemoryParticipant database = new MemoryParticipant();
            StoredTaskRecords records = repository(database);
            RunClaim claim = start(records, records.create(spec(false)).taskId(), "old");
            records.append(claim, event(claim, "intent", EventKind.INTENT,
                    Map.of("tool", "write", "arguments", Map.of("file", "result.txt"))));
            records.append(claim, event(claim, "returned", EventKind.RETURNED,
                    Map.of("outcome", outcome("SUCCESS"))));
            if (verified) {
                records.append(claim, event(claim, "verified", EventKind.VERIFIED,
                        Map.of("outcome", outcome("SUCCESS"))));
            }
            assertTrue(records.complete(claim, 0, records.query(claim, "root").revision(), outcome("SUCCESS")));
            StoredTaskRecords reopened = repository(database);
            reopened.reconcileStoppedRun(claim.taskId(), claim.rootRunId(), Map.of("oldExecutionStopped", true));
            assertTrue(reopened.activeRuns(10).isEmpty());
            assertEquals(verified, reopened.recoverable(10).isEmpty(),
                    "已完成且有验收证据不能因end前崩溃重做；缺少工具验收须恢复核对");
            if (!verified) {
                RunClaim next = start(reopened, claim.taskId(), "review");
                var snapshot = reopened.query(next, "root");
                assertEquals(Disposition.CONSULT_MODEL, snapshot.disposition());
                assertEquals("UNVERIFIED", snapshot.pending().getFirst().state());
            }
        }
    }

    @Test
    void knownFailureCanRetrySameArgumentsWithoutResolvingExistingIncident() {
        for (boolean verificationFailure : List.of(false, true)) {
            MemoryParticipant database = new MemoryParticipant();
            StoredTaskRecords records = repository(database);
            RunClaim claim = start(records, records.create(spec(false)).taskId(), "known-failure");
            Map<String, Object> intention = Map.of("tool", "write", "arguments", Map.of("file", "result.txt"));
            records.append(claim, operationEvent(claim, "first-intent", "first", EventKind.INTENT, intention));
            records.append(claim, operationEvent(claim, "first-return", "first", EventKind.RETURNED,
                    Map.of("outcome", outcome(verificationFailure ? "SUCCESS" : "FAILED"))));
            if (verificationFailure) {
                records.append(claim, operationEvent(claim, "failed-verification", "first", EventKind.VERIFIED,
                        Map.of("outcome", outcome("FAILED"))));
            }
            RecoverySnapshot before = records.query(claim, "root");
            assertEquals(1, before.pending().size());
            StoredTaskRecords reopened = repository(database);
            ExecutionEvent retry = operationEvent(claim, "retry-intent", "retry", EventKind.INTENT, intention);
            reopened.append(claim, retry);
            assertTrue(eventIds(reopened.readEvents(claim.taskId())).contains(retry.eventId()));
            assertEquals(before.pending(), reopened.query(claim, "root").pending(),
                    "允许新意图不代表旧影响已经审核解决");
            assertFalse(reopened.complete(claim, 0, reopened.query(claim, "root").revision(), outcome("SUCCESS")));
        }
    }

    @Test
    void unknownUnconfirmedAndUnverifiedEffectsStillRejectSameArgumentReplay() {
        for (String evidence : List.of("UNKNOWN", "UNCONFIRMED", "UNVERIFIED")) {
            MemoryParticipant database = new MemoryParticipant();
            StoredTaskRecords records = repository(database);
            RunClaim old = start(records, records.create(spec(false)).taskId(), "interrupted");
            Map<String, Object> intention = Map.of("tool", "write", "arguments", Map.of("file", "result.txt"));
            records.append(old, operationEvent(old, "first-intent", "first", EventKind.INTENT, intention));
            if (!"UNCONFIRMED".equals(evidence)) {
                String status = "UNKNOWN".equals(evidence) ? "UNKNOWN" : "SUCCESS";
                records.append(old, operationEvent(old, "first-return", "first", EventKind.RETURNED,
                        Map.of("outcome", outcome(status))));
            }
            records.reconcileStoppedRun(old.taskId(), old.rootRunId(), Map.of("localDispatcherStopped", true));
            StoredTaskRecords reopened = repository(database);
            RunClaim next = start(reopened, old.taskId(), "recovery");
            RecoverySnapshot before = reopened.query(next, "root");
            assertFalse(before.pending().isEmpty(), evidence);
            ExecutionEvent replay = operationEvent(next, "unsafe-replay", "new-operation", EventKind.INTENT, intention);
            assertThrows(IllegalStateException.class, new AppendEvent(reopened, next, replay), evidence);
            assertFalse(eventIds(reopened.readEvents(next.taskId())).contains(replay.eventId()), evidence);
            assertEquals(before.pending(), reopened.query(next, "root").pending(), evidence);
        }
    }

    @Test
    void injectedReplayPolicyControlsNewAdmissionWithoutRejectingAcceptedEventDuplicates() {
        MemoryParticipant database = new MemoryParticipant();
        StoredTaskRecords records = repository(database);
        RunClaim claim = start(records, records.create(spec(false)).taskId(), "policy");
        Map<String, Object> intention = Map.of("tool", "write", "arguments", Map.of("file", "result.txt"));
        ExecutionEvent accepted = operationEvent(claim, "accepted", "first", EventKind.INTENT, intention);
        records.append(claim, accepted);
        RejectNewIntents policy = new RejectNewIntents();
        StoredTaskRecords restricted = new StoredTaskRecords(store(database), StoredTaskRecords.retainingReviews(),
                new NoWake(), null, policy);
        int commits = database.commits;
        restricted.append(claim, accepted);
        assertEquals(0, policy.calls, "同一已接受事件的幂等读回不应重新审核派发许可");
        assertEquals(commits, database.commits);
        ExecutionEvent proposed = operationEvent(claim, "new-intent", "second", EventKind.INTENT, intention);
        assertThrows(IllegalStateException.class, new AppendEvent(restricted, claim, proposed));
        assertEquals(1, policy.calls);
        assertEquals(proposed, policy.lastRequested);
        assertEquals(commits, database.commits, "策略拒绝时不能提交新执行意图");
    }

    @Test
    void independentReviewDispositionIsPreservedWithRemainingPendingItems() {
        for (Disposition disposition : Disposition.values()) {
            MemoryParticipant database = new MemoryParticipant();
            StoredTaskRecords records = new StoredTaskRecords(store(database), new ApproveFirstIssue(disposition), new NoWake());
            RunClaim claim = start(records, records.create(spec(false)).taskId(), "review");
            records.append(claim, operationEvent(claim, "first-failure", "first", EventKind.RETURNED,
                    Map.of("outcome", outcome("FAILED"))));
            records.append(claim, operationEvent(claim, "second-failure", "second", EventKind.RETURNED,
                    Map.of("outcome", outcome("FAILED"))));
            RecoverySnapshot before = records.query(claim, "root");
            assertEquals(2, before.pending().size());
            String approved = before.pending().getFirst().id();
            RecoveryItem remaining = before.pending().getLast();
            RecoverySnapshot reviewed = records.review(claim, new ResolutionProposal("root", before.revision(),
                    List.of(approved), Map.of("evidence", "independently-checked")));
            assertEquals(disposition, reviewed.disposition());
            assertEquals(List.of(remaining), reviewed.pending());
            assertEquals("external-review", reviewed.reason());
            StoredTaskRecords reopened = repository(database);
            assertEquals(reviewed, reopened.query(claim, "root"), "JSON 往返不得改变外部接续结论");
            assertFalse(reopened.complete(claim, 0, reviewed.revision(), outcome("SUCCESS")),
                    "CONTINUE 也不能替代剩余事项的完成审核");
            assertEquals(List.of(remaining), reopened.query(claim, "root").pending());
        }
    }

    @Test
    void reviewConflictPreservesConcurrentFactsAndDoesNotRepeatExternalReview() {
        MemoryParticipant database = new MemoryParticipant();
        ConcurrentReview policy = new ConcurrentReview(repository(database));
        StoredTaskRecords records = new StoredTaskRecords(store(database), policy, new NoWake());
        RunClaim claim = start(records, records.create(spec(false)).taskId(), "conflicting-review");
        policy.claim = claim;
        records.append(claim, operationEvent(claim, "failure", "first", EventKind.RETURNED,
                Map.of("outcome", outcome("FAILED"))));
        RecoverySnapshot before = records.query(claim, "root");
        ResolutionProposal proposal = new ResolutionProposal("root", before.revision(),
                List.of(before.pending().getFirst().id()), Map.of("resolved", true));
        assertThrows(IllegalStateException.class, new ReviewRequest(records, claim, proposal));
        RecoverySnapshot after = repository(database).query(claim, "root");
        assertEquals(1, policy.calls, "外部核验不能进入 CAS 自动重试");
        assertTrue(after.revision() > before.revision());
        assertEquals(before.pending(), after.pending(), "过期审核不得清除事项");
        assertEquals(Disposition.CONSULT_MODEL, after.disposition());
        assertTrue(eventIds(records.readEvents(claim.taskId())).contains("concurrent-observation"));
        assertFalse(records.complete(claim, 0, after.revision(), outcome("SUCCESS")));
    }

    @Test
    void replayAdmissionIsRecheckedAfterConcurrentUnknownEffectWinsCas() {
        MemoryParticipant database = new MemoryParticipant();
        StoredTaskRecords records = repository(database);
        RunClaim claim = start(records, records.create(spec(false)).taskId(), "intent-race");
        Map<String, Object> intention = Map.of("tool", "write", "arguments", Map.of("file", "result.txt"));
        records.append(claim, operationEvent(claim, "first-intent", "first", EventKind.INTENT, intention));
        ExecutionEvent uncertain = operationEvent(claim, "unknown-return", "first", EventKind.RETURNED,
                Map.of("outcome", outcome("UNKNOWN")));
        database.beforeNextConditional = new AppendBeforeCommit(repository(database), claim, uncertain);
        ExecutionEvent retry = operationEvent(claim, "racing-intent", "retry", EventKind.INTENT, intention);
        assertThrows(IllegalStateException.class, new AppendEvent(records, claim, retry));
        List<String> ids = eventIds(repository(database).readEvents(claim.taskId()));
        assertTrue(ids.contains(uncertain.eventId()), "竞争提交的真实未知结果必须保留");
        assertFalse(ids.contains(retry.eventId()), "重读新快照后必须重新检查重放策略");
        assertFalse(records.query(claim, "root").pending().isEmpty());
    }

    @Test
    void recordsConfigurationUsesOwnerContractAndInjectedReplayPolicyWithoutApplicationLock() {
        MemoryParticipant database = new MemoryParticipant();
        RejectNewIntents replay = new RejectNewIntents();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("sharedStorage", store(database));
            context.getBeanFactory().registerSingleton("recordOwner", new TaskRecordOwner("independent-host"));
            context.getBeanFactory().registerSingleton("replayPolicy", replay);
            context.register(RecordsConfiguration.class);
            context.refresh();
            StoredTaskRecords records = context.getBean(StoredTaskRecords.class);
            RunClaim claim = start(records, records.create(spec(false)).taskId(), "configured");
            assertEquals("independent-host", records.activeRuns(1).getFirst().ownerId());
            assertSame(records, context.getBean(TaskRecordPort.class));
            assertSame(records, context.getBean(TaskInstructionPort.class));
            ExecutionEvent requested = operationEvent(claim, "configured-intent", "write-operation", EventKind.INTENT,
                    Map.of("tool", "write", "arguments", Map.of("file", "result.txt")));
            assertThrows(IllegalStateException.class, new AppendEvent(records, claim, requested));
            assertEquals(1, replay.calls);
            assertTrue(records.readEvents(claim.taskId()).isEmpty());
        }
    }

    @Test
    void nestedPayloadDeduplicatesAcrossNumericWidthAndObjectKeyOrder() {
        MemoryParticipant database = new MemoryParticipant();
        StoredTaskRecords records = repository(database);
        RunClaim claim = start(records, records.create(spec(false)).taskId(), "json-equivalence");
        Map<String, Object> original = new java.util.LinkedHashMap<>();
        original.put("z-first", Map.of("count", 1L));
        original.put("a-second", "stable");
        records.append(claim, event(claim, "equivalent", EventKind.OBSERVED, original));
        int commits = database.commits;
        Map<String, Object> reordered = new java.util.LinkedHashMap<>();
        reordered.put("a-second", "stable");
        reordered.put("z-first", Map.of("count", 1));
        StoredTaskRecords reopened = repository(database);
        reopened.append(claim, event(claim, "equivalent", EventKind.OBSERVED, reordered));
        assertEquals(commits, database.commits);
        assertEquals(List.of("equivalent"), eventIds(reopened.readEvents(claim.taskId())));
    }

    @Test
    void schemaOneWithoutEventOrderRemainsReadableAndGainsExplicitOrderOnNextWrite() {
        MemoryParticipant database = new MemoryParticipant();
        StoredTaskRecords records = repository(database);
        RunClaim claim = start(records, records.create(spec(false)).taskId(), "legacy-schema-one");
        records.append(claim, event(claim, "a-first", EventKind.OBSERVED, Map.of("source", "old-one")));
        records.append(claim, event(claim, "b-second", EventKind.OBSERVED, Map.of("source", "old-two")));
        JsonNode oldDocument = database.json.parse(database.data.get(claim.taskId()));
        assertEquals(1, oldDocument.at("/data/schema").intValue());
        assertEquals("CONTINUE", oldDocument.at("/data/state/latestKind").textValue());
        ((ObjectNode) oldDocument.at("/data/state")).remove("eventOrder");
        database.data.put(claim.taskId(), oldDocument.toString());
        StoredTaskRecords reopened = repository(database);
        assertEquals(List.of("a-first", "b-second"), eventIds(reopened.readEvents(claim.taskId())));
        reopened.append(claim, event(claim, "c-third", EventKind.OBSERVED, Map.of("source", "new")));
        assertEquals(List.of("a-first", "b-second", "c-third"), eventIds(repository(database).readEvents(claim.taskId())));
        JsonNode savedOrder = database.json.parse(database.data.get(claim.taskId())).at("/data/state/eventOrder");
        assertEquals(3, savedOrder.size());
        assertEquals("a-first", savedOrder.get(0).textValue());
        assertEquals("b-second", savedOrder.get(1).textValue());
        assertEquals("c-third", savedOrder.get(2).textValue());
    }

    @Test
    void pendingArchiveOrderSurvivesJsonRoundTripAndAcknowledgement() {
        MemoryParticipant database = new MemoryParticipant();
        StoredTaskRecords records = repository(database);
        String taskId = records.create(spec(false)).taskId();
        RunClaim first = start(records, taskId, "z-run-first");
        records.endRun(first, uniquelyNamedFinalization(first));
        RunClaim second = start(records, taskId, "a-run-second");
        records.endRun(second, uniquelyNamedFinalization(second));
        ArchiveRef firstRef = new ArchiveRef(taskId, first.rootRunId());
        ArchiveRef secondRef = new ArchiveRef(taskId, second.rootRunId());
        StoredTaskRecords reopened = repository(database);
        assertEquals(List.of(firstRef, secondRef), reopened.pendingConsolidations(10));
        reopened.acknowledgeConsolidation(firstRef);
        assertEquals(List.of(secondRef), repository(database).pendingConsolidations(10));
        assertTrue(reopened.readArchive(firstRef).isPresent(), "确认消费只去掉待办，不删除固定归档");
    }

    private static RunFinalization uniquelyNamedFinalization(RunClaim claim) {
        RunFinalization base = finalization(claim, List.of());
        ExecutionEvent ended = event(claim, "end-" + claim.rootRunId(), EventKind.RUN_ENDED, base.event().details());
        return new RunFinalization(ended, base.graph(), base.graphComplete(), base.gaps(), base.evidenceGaps());
    }

    private static List<String> eventIds(List<ExecutionEvent> events) {
        List<String> ids = new ArrayList<>();
        for (ExecutionEvent event : events) { ids.add(event.eventId()); }
        return ids;
    }

    private static StoredTaskRecords repository(MemoryParticipant database) {
        return new StoredTaskRecords(store(database));
    }

    private static StoredTaskRecords repository(MemoryParticipant database, AtomicInteger wakes) {
        return new StoredTaskRecords(store(database), StoredTaskRecords.retainingReviews(),
                new Consumer<ArchiveRef>() {
                    @Override public void accept(ArchiveRef ref) { wakes.incrementAndGet(); }
                });
    }

    private static DualWriteStore store(MemoryParticipant database) {
        return new DualWriteStore(database, new RejectVector());
    }

    private static TaskSpec spec(boolean internal) {
        return new TaskSpec("original-goal", Map.of("version", 7), "A", List.of("write"), internal);
    }

    private static RunClaim start(StoredTaskRecords records, String taskId, String rootRunId) {
        RunClaim claim = records.beginRun(taskId, rootRunId);
        records.bind(claim);
        return claim;
    }

    private static ExecutionEvent event(RunClaim claim, String id, EventKind kind, Map<String, Object> details) {
        return new ExecutionEvent(id, claim.taskId(), claim.rootRunId(), "work", "operation", "branch", 0, kind, details);
    }

    private static Map<String, Object> outcome(String status) {
        return Map.of("status", status, "value", Map.of("version", 2), "error", "");
    }

    private static RunFinalization finalization(RunClaim claim, List<String> evidenceGaps) {
        Instant at = Instant.parse(AT);
        ExecutionTraceStore.Node node = new ExecutionTraceStore.Node("operation", "work", null,
                ExecutionTraceStore.NodeKind.TASK, "original-goal", 0, Map.of(),
                ExecutionTraceStore.NodeStatus.SUCCESS, Map.of("version", 2), null, at, at);
        ExecutionTraceStore.Snapshot graph = new ExecutionTraceStore.Snapshot(Map.of(node.id(), node), List.of(),
                List.of(new ExecutionTraceStore.Event(1, node.id(), "CREATED", at),
                        new ExecutionTraceStore.Event(2, node.id(), "SUCCESS", at)), true);
        ExecutionEvent ended = event(claim, "end", EventKind.RUN_ENDED,
                Map.of("recordedAt", AT, "instructionRevision", 0L, "outcome", outcome("SUCCESS")));
        return new RunFinalization(ended, graph, true, List.of(), evidenceGaps);
    }

    private static AgentOrchestrator.Decision completed() {
        return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.COMPLETE, null, Map.of("done", true));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) { throw new AssertionError("等待信号超时"); }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static ExecutionEvent operationEvent(RunClaim claim, String id, String operation,
                                                EventKind kind, Map<String, Object> details) {
        return new ExecutionEvent(id, claim.taskId(), claim.rootRunId(), "work", operation, "branch", 0, kind, details);
    }

    private record AppendEvent(
            /** 被检查的仓储。 */
            StoredTaskRecords records,
            /** 原运行领取。 */
            RunClaim claim,
            /** 本次尝试写入的事实。 */
            ExecutionEvent event) implements Executable {
        @Override
        public void execute() {
            records.append(claim, event);
        }
    }

    private record ReviewRequest(
            /** 被检查的仓储。 */
            StoredTaskRecords records,
            /** 原运行领取。 */
            RunClaim claim,
            /** 固定版本的审核提案。 */
            ResolutionProposal proposal) implements Executable {
        @Override
        public void execute() {
            records.review(claim, proposal);
        }
    }

    private record AppendBeforeCommit(
            /** 模拟独立写入者的仓储。 */
            StoredTaskRecords records,
            /** 当前有效领取。 */
            RunClaim claim,
            /** 抢先提交的真实结果。 */
            ExecutionEvent event) implements Runnable {
        @Override
        public void run() {
            records.append(claim, event);
        }
    }

    private static final class NoWake implements Consumer<ArchiveRef> {
        @Override
        public void accept(ArchiveRef ignored) { }
    }

    private static final class RejectNewIntents implements ToolReplayPolicy {
        /** 观察策略是否仅处理新意图；此检查不执行外部调用。 */
        private int calls;
        /** 核对策略收到的原始意图。 */
        private ExecutionEvent lastRequested;
        @Override
        public Decision check(Context context) {
            calls++;
            lastRequested = context.requested();
            return new Decision(false, "external-policy-rejected");
        }
    }

    private static final class ApproveFirstIssue implements StoredTaskRecords.ReviewPolicy {
        /** 独立审核选择的后续调度结论。 */
        private final Disposition disposition;
        private ApproveFirstIssue(Disposition disposition) {
            this.disposition = disposition;
        }
        @Override
        public StoredTaskRecords.ReviewDecision review(RecoverySnapshot snapshot, ResolutionProposal proposal) {
            return new StoredTaskRecords.ReviewDecision(Set.of(proposal.incidentIds().getFirst()),
                    "external-review", disposition);
        }
    }

    private static final class ConcurrentReview implements StoredTaskRecords.ReviewPolicy {
        /** 另一个仓储实例用于在审核期间提交新证据。 */
        private final StoredTaskRecords competing;
        /** 当前有效运行领取，由场景准备后绑定。 */
        private RunClaim claim;
        /** 核对外部审核未在 CAS 冲突后重复调用。 */
        private int calls;
        private ConcurrentReview(StoredTaskRecords competing) {
            this.competing = competing;
        }
        @Override
        public StoredTaskRecords.ReviewDecision review(RecoverySnapshot snapshot, ResolutionProposal proposal) {
            calls++;
            competing.append(claim, event(claim, "concurrent-observation", EventKind.OBSERVED,
                    Map.of("evidence", "new-resource-version")));
            return new StoredTaskRecords.ReviewDecision(Set.copyOf(proposal.incidentIds()),
                    "obsolete-review", Disposition.CONTINUE);
        }
    }

    private static final class ClaimAttempt implements Supplier<RunClaim> {
        /** 独立仓储实例。 */
        private final StoredTaskRecords records;
        /** 竞争的稳定任务身份。 */
        private final String taskId;
        /** 本竞争者的根运行身份。 */
        private final String root;
        /** 确认竞争者均已就绪。 */
        private final CountDownLatch ready;
        /** 同时启动领取竞争。 */
        private final CountDownLatch release;
        private ClaimAttempt(StoredTaskRecords records, String taskId, String root,
                             CountDownLatch ready, CountDownLatch release) {
            this.records = records; this.taskId = taskId; this.root = root;
            this.ready = ready; this.release = release;
        }
        @Override public RunClaim get() {
            ready.countDown();
            await(release);
            try { return records.beginRun(taskId, root); }
            catch (IllegalStateException conflict) { return null; }
        }
    }

    /** 实际 JSON 编解码模拟进程重启及数值类型变化，不共享生产 State 对象。 */
    private static final class MemoryParticipant implements DualWriteStore.Participant {
        /** 只保存序列化后的完整实体。 */
        private final Map<String, String> data = new TreeMap<>();
        /** 与生产统一存储相同的实体编解码器。 */
        private final EntityJson json = new EntityJson();
        /** 确认最终提交只使用一次原子条件写。 */
        private int commits;
        /** 写入之前持续失败。 */
        private boolean failBefore;
        /** 写入已完成但返回异常，只触发一次。 */
        private boolean commitThenThrow;
        /** 仅下一次条件提交前插入竞争事实，模拟旧快照与提交之间的冲突。 */
        private Runnable beforeNextConditional;

        @Override public DualWriteStore.Change open(String operationId, List<WorldEntity> entities) {
            throw new AssertionError("记录权威状态必须走 CAS，不能普通覆盖写");
        }

        @Override public synchronized boolean writeConditionally(WorldEntity expected, WorldEntity replacement) {
            if (failBefore) { throw new IllegalStateException("before-commit"); }
            Runnable competingWrite = beforeNextConditional;
            beforeNextConditional = null;
            if (competingWrite != null) {
                competingWrite.run();
            }
            String current = data.get(replacement.id());
            if (expected == null ? current != null
                    : current == null || !json.parse(current).equals(json.parse(json.encode(expected)))) {
                return false;
            }
            data.put(replacement.id(), json.encode(replacement));
            commits++;
            if (commitThenThrow) {
                commitThenThrow = false;
                throw new IllegalStateException("reply-lost-after-commit");
            }
            return true;
        }

        @Override public synchronized Optional<WorldEntity> read(String id) {
            String encoded = data.get(id);
            return encoded == null ? Optional.empty() : Optional.of(json.entity(encoded));
        }

        @Override public synchronized StorageQuery.Page query(StorageQuery.PageRequest request) {
            List<WorldEntity> found = new ArrayList<>();
            String after = request.cursor() == null ? "" : request.cursor().position();
            boolean more = false;
            for (Map.Entry<String, String> entry : data.entrySet()) {
                if (entry.getKey().compareTo(after) <= 0 || !matches(json.parse(entry.getValue()), request.filter())) {
                    continue;
                }
                if (found.size() == request.limit()) { more = true; break; }
                found.add(json.entity(entry.getValue()));
            }
            StorageQuery.Cursor next = more ? new StorageQuery.Cursor(DualWriteStore.Database.NEO4J,
                    found.getLast().id()) : null;
            return new StorageQuery.Page(found, next);
        }

        private boolean matches(JsonNode entity, StorageQuery.Filter filter) {
            for (StorageQuery.Match match : filter.matches()) {
                JsonNode actual = entity.at(match.pointer());
                boolean accepted = false;
                for (Object value : match.values()) {
                    JsonNode expected = json.parse(json.encode(value));
                    if (actual.isArray()) {
                        for (JsonNode member : actual) {
                            if (sameScalar(member, expected)) { accepted = true; }
                        }
                    } else if (sameScalar(actual, expected)) {
                        accepted = true;
                    }
                }
                if (!accepted) { return false; }
            }
            return true;
        }

        private boolean sameScalar(JsonNode left, JsonNode right) {
            if (left.isNumber() && right.isNumber()) {
                return left.decimalValue().compareTo(right.decimalValue()) == 0;
            }
            return left.equals(right);
        }
    }

    private static final class RejectVector implements DualWriteStore.Participant {
        @Override public DualWriteStore.Change open(String operationId, List<WorldEntity> entities) {
            throw new AssertionError("记录仓储不应隐式双写向量库");
        }
        @Override public Optional<WorldEntity> read(String id) {
            throw new AssertionError("记录仓储不应从向量库回退");
        }
    }

    private static final class Runtime implements AutoCloseable {
        /** 独立运行组件的工作池。 */
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        /** 独立截止计时器。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 每次模拟启动创建新的编排器与模型会话。 */
        private final AgentOrchestrator orchestrator;
        private Runtime(StoredTaskRecords records, AgentOrchestrator.Planner planner) {
            AgentOrchestrator.Checks checks = new AgentOrchestrator.Checks() {
                @Override public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) { return true; }
                @Override public boolean verify(String goal, Map<String, Object> args, Object output) { return true; }
            };
            AgentOrchestrator.ActionExecutor actions = new AgentOrchestrator.ActionExecutor() {
                @Override public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args,
                                                                    AgentOrchestrator.CallContext context) {
                    throw new AssertionError("恢复检查不应盲目执行旧工具");
                }
            };
            orchestrator = new AgentOrchestrator(planner, CapabilitySearchPort.disabled(), actions, checks,
                    new ExecutionTraceStore(3, 300, 1500), workers, timer,
                    new AgentOrchestrator.Limits(5, 5, 20, 20, 30, 30, Duration.ofSeconds(15)),
                    new TestModelAdapter(List.of("A")), records);
        }
        @Override public void close() { workers.shutdownNow(); timer.shutdownNow(); }
    }
}
