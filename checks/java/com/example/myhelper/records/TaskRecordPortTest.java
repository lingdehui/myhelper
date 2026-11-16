package com.example.myhelper.records;

import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.orchestration.ExecutionTraceStore;
import com.example.myhelper.records.TaskRecordPort.Disposition;
import com.example.myhelper.records.TaskRecordPort.EventKind;
import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.records.TaskRecordPort.RecoveryItem;
import com.example.myhelper.records.TaskRecordPort.RecoverySnapshot;
import com.example.myhelper.records.TaskRecordPort.ResolutionProposal;
import com.example.myhelper.records.TaskRecordPort.RunClaim;
import com.example.myhelper.records.TaskRecordPort.TaskSnapshot;
import com.example.myhelper.records.TaskRecordPort.TaskSpec;
import com.example.myhelper.support.RecordingTaskRecords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端口数据与检查替身合同；不把进程内替身当作生产持久恢复验收。 */
class TaskRecordPortTest {
    @Test
    void disabledPortCannotInventTasksClaimsOrRecoveryApproval() {
        TaskRecordPort records = TaskRecordPort.disabled();
        assertFalse(records.enabled());
        assertEquals(RecoverySnapshot.empty(), records.query(null, "child"));
        records.append(null, null);
        records.endRun(null, (TaskRecordPort.ExecutionEvent) null);
        for (String operation : List.of("create", "load", "claim", "review")) {
            UnsupportedOperationException failure = assertThrows(UnsupportedOperationException.class, new Executable() {
                @Override
                public void execute() {
                    switch (operation) {
                        case "create" -> records.create(spec());
                        case "load" -> records.load("task");
                        case "claim" -> records.beginRun("task", "root");
                        case "review" -> records.review(null,
                                new ResolutionProposal("child", 0, List.of(), Map.of("resolved", true)));
                        default -> throw new AssertionError(operation);
                    }
                }
            });
            assertEquals(LlmTexts.TASK_RECORDS_REQUIRED, failure.getMessage());
        }
    }

    @Test
    void everyPublishedPayloadIsADeepImmutableSnapshot() {
        List<Object> mutableValues = new ArrayList<>(List.of("version-1"));
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("versions", mutableValues);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("artifact", nested);
        List<String> tools = new ArrayList<>(List.of("read"));
        TaskSpec spec = new TaskSpec("original goal", payload, "A", tools);
        RecoveryItem item = new RecoveryItem("incident", "OPEN", "needs evidence", payload);
        List<RecoveryItem> pending = new ArrayList<>(List.of(item));
        RecoverySnapshot snapshot = new RecoverySnapshot(1, Disposition.CONSULT_MODEL, pending, payload, null);
        List<String> ids = new ArrayList<>(List.of("incident"));
        ResolutionProposal proposal = new ResolutionProposal("child", 1, ids, payload);
        ExecutionEvent event = new ExecutionEvent("event", "task", "root", "child", "operation", "branch",
                1, EventKind.RETURNED, payload);
        mutableValues.add("version-2");
        nested.put("unexpected", true);
        payload.clear();
        tools.clear();
        pending.clear();
        ids.clear();
        assertEquals(List.of("read"), spec.toolNames());
        assertEquals(List.of("incident"), proposal.incidentIds());
        assertEquals(List.of(item), snapshot.pending());
        for (Map<String, Object> frozen : List.of(spec.arguments(), item.evidence(), snapshot.progress(), event.details())) {
            assertArtifactSnapshot(frozen);
        }
        assertArtifactSnapshot(asMap(proposal.conclusion()));
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new RecoverySnapshot(-1, Disposition.CONTINUE, List.of(), Map.of(), null);
            }
        });
    }

    @Test
    void existingTraceStoreCanBeUsedThroughTheIndependentPort() {
        ExecutionTracePort traces = new ExecutionTraceStore(2, 10, 30);
        traces.open("root");
        traces.add("root", new ExecutionTraceStore.Node("operation", "child", null,
                ExecutionTraceStore.NodeKind.TOOL, "write artifact", 1, Map.of("path", "artifact"),
                ExecutionTraceStore.NodeStatus.RUNNING, null, null, Instant.EPOCH, null));
        ExecutionTraceStore.Snapshot before = traces.snapshot("root");
        traces.finish("root", "operation", ExecutionTraceStore.NodeStatus.FAILED,
                Map.of("writtenVersion", "v2"), "verification failed");
        traces.close("root");
        ExecutionTraceStore.Snapshot after = traces.snapshot("root");
        assertFalse(before.closed());
        assertEquals(ExecutionTraceStore.NodeStatus.RUNNING, before.nodes().get("operation").status());
        assertTrue(after.closed());
        assertEquals(Map.of("writtenVersion", "v2"), after.nodes().get("operation").output());
        assertEquals(ExecutionTraceStore.NodeStatus.FAILED, after.nodes().get("operation").status());
    }

    @Test
    void eventDeduplicationRejectsConflictingPayloadsAndForeignClaims() {
        RecordingTaskRecords records = new RecordingTaskRecords();
        TaskSnapshot task = records.create(spec());
        RunClaim claim = records.beginRun(task.taskId(), "root-1");
        ExecutionEvent intent = event(claim, "intent", EventKind.INTENT, Map.of("tool", "write"));
        records.append(claim, intent);
        records.append(claim, intent);
        assertEquals(List.of(intent), records.events(task.taskId()));
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                records.append(claim, event(claim, "intent", EventKind.INTENT, Map.of("tool", "delete")));
            }
        });
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                ExecutionEvent foreign = new ExecutionEvent("foreign", "other-task", claim.rootRunId(),
                        "child", "operation", "branch", 1, EventKind.INTENT, Map.of());
                records.append(claim, foreign);
            }
        });
        assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                records.beginRun(task.taskId(), "competing-run");
            }
        });
        assertEquals(List.of(intent), records.events(task.taskId()));
    }

    @Test
    void failedEndCommitKeepsClaimAndRepeatedOldEndCannotReleaseANewRun() {
        RecordingTaskRecords records = new RecordingTaskRecords();
        TaskSnapshot task = records.create(spec());
        RunClaim first = records.beginRun(task.taskId(), "root-1");
        ExecutionEvent end = event(first, "end-1", EventKind.RUN_ENDED, Map.of());
        records.failAppend(EventKind.RUN_ENDED);
        assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                records.endRun(first, end);
            }
        });
        assertEquals(first, records.activeClaim(task.taskId()));
        assertTrue(records.events(task.taskId()).isEmpty());
        // 保存故障时，真实在途动作的迟到回执仍属于原领取。
        records.append(first, event(first, "late", EventKind.RETURNED, outcome("UNKNOWN", "already-written")));
        records.failAppend(null);
        records.endRun(first, end);
        assertNull(records.activeClaim(task.taskId()));
        RunClaim second = records.beginRun(task.taskId(), "root-2");
        records.endRun(first, end);
        assertEquals(second, records.activeClaim(task.taskId()));
        assertEquals(2, records.events(task.taskId()).size());
        assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                records.append(first, event(first, "too-late", EventKind.RETURNED, Map.of()));
            }
        });
    }

    @Test
    void resolutionRequiresCurrentRevisionAndExternalApprovalOfSpecificIncidents() {
        RecordingTaskRecords records = new RecordingTaskRecords();
        TaskSnapshot task = records.create(spec());
        RunClaim claim = records.beginRun(task.taskId(), "root");
        RecoveryItem first = new RecoveryItem("R1", "OPEN", "first effect", Map.of("version", 1));
        RecoveryItem second = new RecoveryItem("R2", "OPEN", "second effect", Map.of("version", 2));
        records.setRecovery(task.taskId(), new RecoverySnapshot(3, Disposition.CONSULT_MODEL,
                List.of(first, second), Map.of(), null));
        assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                records.review(claim, new ResolutionProposal("child", 2, List.of("R1"), "resolved"));
            }
        });
        assertEquals(List.of(first, second), records.recovery(task.taskId()).pending());
        RecoverySnapshot rejected = records.review(claim,
                new ResolutionProposal("child", 3, List.of("R1"), Map.of("resolved", true)));
        assertEquals(Disposition.WAIT, rejected.disposition());
        assertEquals(List.of(first, second), rejected.pending());
        records.approveReviews(true);
        RecoverySnapshot partlyApproved = records.review(claim,
                new ResolutionProposal("child", rejected.revision(), List.of("R1"), "no remaining effect"));
        assertEquals(List.of(second), partlyApproved.pending());
        assertEquals(Disposition.CONSULT_MODEL, partlyApproved.disposition());
        RecoverySnapshot approved = records.review(claim,
                new ResolutionProposal("child", partlyApproved.revision(), List.of("R2"), "verified repair"));
        assertTrue(approved.pending().isEmpty());
        assertEquals(Disposition.CONTINUE, approved.disposition());
        assertEquals(3, records.reviews(task.taskId()).size());
    }

    @Test
    void modelFailureKeepsOriginalFactsForANewRunAndLaterSuccessDoesNotEraseThem() {
        RecordingTaskRecords records = new RecordingTaskRecords();
        TaskSnapshot original = records.create(spec());
        RunClaim first = records.beginRun(original.taskId(), "root-1");
        ExecutionEvent returned = event(first, "returned-1", EventKind.RETURNED, outcome("UNKNOWN", "written-v2"));
        records.append(first, returned);
        records.append(first, event(first, "model-failed", EventKind.MODEL_FAILED,
                Map.of("error", "provider offline", "phase", "RECOVERY", "history", List.of())));
        RecoverySnapshot waiting = records.recovery(original.taskId());
        assertEquals(Disposition.WAIT, waiting.disposition());
        assertEquals("WAITING_MODEL", waiting.pending().getFirst().state());
        records.endRun(first, event(first, "end-1", EventKind.RUN_ENDED, Map.of()));
        // 新编排器持有同一个外部模块时只需 load/beginRun，不依赖旧编排器对象。
        TaskRecordPort externallyShared = records;
        assertEquals(original, externallyShared.load(original.taskId()));
        RunClaim second = externallyShared.beginRun(original.taskId(), "root-2");
        assertNotEquals(first.rootRunId(), second.rootRunId());
        assertNotEquals(first.token(), second.token());
        RecoverySnapshot resumed = externallyShared.query(second, "new-child");
        assertEquals(Disposition.CONSULT_MODEL, resumed.disposition());
        assertEquals(waiting.pending(), resumed.pending());
        externallyShared.append(second, event(second, "returned-2", EventKind.RETURNED, outcome("SUCCESS", "repair-output")));
        assertEquals(resumed.pending(), records.recovery(original.taskId()).pending());
        assertEquals(returned, records.events(original.taskId()).getFirst());
        assertEquals("original goal", externallyShared.load(original.taskId()).task().goal());
    }

    private static TaskSpec spec() {
        return new TaskSpec("original goal", Map.of("artifact", "document"), "A", List.of("read", "write"));
    }

    private static ExecutionEvent event(RunClaim claim, String eventId, EventKind kind, Map<String, Object> details) {
        return new ExecutionEvent(eventId, claim.taskId(), claim.rootRunId(), "child", "operation", "branch",
                1, kind, details);
    }

    private static Map<String, Object> outcome(String status, Object value) {
        return Map.of("outcome", Map.of("status", status, "value", value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static void assertArtifactSnapshot(Map<String, Object> frozen) {
        Map<String, Object> artifact = asMap(frozen.get("artifact"));
        assertEquals(List.of("version-1"), artifact.get("versions"));
        assertEquals(1, artifact.size());
        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            public void execute() {
                artifact.put("changed", true);
            }
        });
        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            @SuppressWarnings("unchecked")
            public void execute() {
                ((List<Object>) artifact.get("versions")).add("changed");
            }
        });
    }
}
