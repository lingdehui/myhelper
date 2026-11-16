package com.example.myhelper.records;

import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.records.TaskInstructionPort.Command;
import com.example.myhelper.records.TaskInstructionPort.Directive;
import com.example.myhelper.records.TaskInstructionPort.Kind;
import com.example.myhelper.records.TaskInstructionPort.Receipt;
import com.example.myhelper.records.TaskInstructionPort.Snapshot;
import com.example.myhelper.records.TaskRecordPort.Disposition;
import com.example.myhelper.records.TaskRecordPort.EventKind;
import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.records.TaskRecordPort.RecoverySnapshot;
import com.example.myhelper.records.TaskRecordPort.RunClaim;
import com.example.myhelper.records.TaskRecordPort.TaskSpec;
import com.example.myhelper.support.RecordingTaskInstructions;
import com.example.myhelper.support.RecordingTaskRecords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 指令合同及检查事务的行为验证，不冒充生产持久化或崩溃恢复验证。 */
class TaskInstructionPortTest {
    @Test
    void commandRetryUsesOriginalReceiptBeforeComparingExpectedVersion() {
        Fixture fixture = new Fixture();
        Command first = command(fixture, "first", 0, Kind.AMEND, "write B instead");
        Receipt receipt = fixture.instructions.accept(first);
        fixture.instructions.accept(command(fixture, "second", 1, Kind.AMEND, "keep A too"));
        assertEquals(receipt, fixture.instructions.accept(first));
        assertEquals(2, fixture.instructions.query(fixture.taskId).revision());
        assertEquals(2, fixture.instructions.query(fixture.taskId).instructions().size());
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                fixture.instructions.accept(command(fixture, "first", 0, Kind.AMEND, "delete A"));
            }
        });
        assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                fixture.instructions.accept(command(fixture, "third", 0, Kind.AMEND, "stale update"));
            }
        });
        assertEquals(2, fixture.instructions.query(fixture.taskId).revision());
    }

    @Test
    void activeRunKeepsItsSnapshotAndCannotApplyAnOldPlan() {
        Fixture fixture = new Fixture();
        RunClaim claim = fixture.records.beginRun(fixture.taskId, "root-1");
        Snapshot original = fixture.instructions.bind(claim);
        fixture.instructions.accept(command(fixture, "amend", 0, Kind.AMEND, "write B instead"));
        assertEquals(original, fixture.instructions.bind(claim));
        assertEquals(Directive.REPLAN, fixture.instructions.check(claim, 0));
        assertFalse(fixture.instructions.applied(claim, 0, "root-task", 1, Map.of("goal", "write A")));
        assertFalse(fixture.instructions.complete(claim, 0, 0, success()));
        assertEquals(0, fixture.instructions.query(fixture.taskId).appliedRevision());
        assertFalse(fixture.instructions.completed(fixture.taskId));
        // 中断后真实在途回执仍可保存，旧计划失效不会抹掉已经发生的效果。
        fixture.records.append(claim, event(claim, "late-return", EventKind.RETURNED,
                Map.of("outcome", success(), "receipt", "written-A")));
        assertTrue(fixture.records.query(claim, "root-task").progress().toString().contains("written-A"));
    }

    @Test
    void completeRejectsAChangedProgressSnapshotThenCommitsLatestOnce() {
        Fixture fixture = new Fixture();
        fixture.instructions.accept(command(fixture, "first", 0, Kind.AMEND, "write B"));
        RunClaim claim = fixture.records.beginRun(fixture.taskId, "root-1");
        assertEquals(1, fixture.instructions.bind(claim).revision());
        assertTrue(fixture.instructions.applied(claim, 1, "root-task", 1, Map.of("goal", "write B")));
        fixture.records.setRecovery(fixture.taskId,
                new RecoverySnapshot(7, Disposition.CONTINUE, List.of(), Map.of("artifact", "B"), null));
        assertFalse(fixture.instructions.complete(claim, 1, 6, success()));
        assertFalse(fixture.instructions.completed(fixture.taskId));
        assertTrue(fixture.instructions.complete(claim, 1, 7, success()));
        assertTrue(fixture.instructions.complete(claim, 1, 7, success()));
        assertEquals(1, fixture.instructions.query(fixture.taskId).appliedRevision());
        assertTrue(fixture.instructions.completed(fixture.taskId));
        assertEquals(Directive.WAIT, fixture.instructions.check(claim, 1));
        fixture.instructions.accept(command(fixture, "second", 1, Kind.AMEND, "also write C"));
        assertFalse(fixture.instructions.completed(fixture.taskId));
        assertFalse(fixture.instructions.complete(claim, 1, 7, success()));
        assertEquals(Directive.REPLAN, fixture.instructions.check(claim, 1));
    }

    @Test
    void pauseAndCancelCannotBindUntilExplicitContinue() {
        for (Kind kind : List.of(Kind.PAUSE, Kind.CANCEL)) {
            Fixture fixture = new Fixture();
            fixture.instructions.accept(command(fixture, "stop", 0, kind, "stop this task"));
            RunClaim claim = fixture.records.beginRun(fixture.taskId, "root-1");
            assertThrows(IllegalStateException.class, new Executable() {
                @Override
                public void execute() {
                    fixture.instructions.bind(claim);
                }
            });
            fixture.records.endRun(claim, event(claim, "ended", EventKind.RUN_ENDED, Map.of()));
            assertTrue(fixture.instructions.recoverable(10).isEmpty());
            fixture.instructions.accept(command(fixture, "continue", 1, Kind.CONTINUE, "resume now"));
            RunClaim resumed = fixture.records.beginRun(fixture.taskId, "root-2");
            assertEquals(2, fixture.instructions.bind(resumed).revision());
            assertEquals(Directive.PROCEED, fixture.instructions.check(resumed, 2));
            assertEquals(Directive.WAIT, fixture.instructions.check(claim, 0));
        }
    }

    @Test
    void interruptionRecordFailureDoesNotAcceptCommandOrAdvanceVersion() {
        Fixture fixture = new Fixture();
        RunClaim claim = fixture.records.beginRun(fixture.taskId, "root-1");
        fixture.instructions.bind(claim);
        Command amend = command(fixture, "amend", 0, Kind.AMEND, "write B");
        fixture.records.failAppend(EventKind.RUN_INTERRUPTION_REQUESTED);
        assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                fixture.instructions.accept(amend);
            }
        });
        assertEquals(Snapshot.empty(), fixture.instructions.query(fixture.taskId));
        assertEquals(Directive.PROCEED, fixture.instructions.check(claim, 0));
        assertTrue(fixture.records.events(fixture.taskId).isEmpty());
        fixture.records.failAppend(null);
        Receipt receipt = fixture.instructions.accept(amend);
        assertEquals(receipt, fixture.instructions.accept(amend));
        assertEquals(1, fixture.records.events(fixture.taskId).size());
        assertEquals(EventKind.RUN_INTERRUPTION_REQUESTED, fixture.records.events(fixture.taskId).getFirst().kind());
    }

    @Test
    void snapshotsCopyCommandsAndRejectInvalidVersions() {
        List<Command> commands = new ArrayList<>();
        commands.add(new Command("task", "first", 0, Kind.AMEND, "write B"));
        Snapshot snapshot = new Snapshot(1, 0, commands, Kind.AMEND);
        commands.clear();
        assertEquals(1, snapshot.instructions().size());
        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            public void execute() {
                snapshot.instructions().clear();
            }
        });
        for (long[] versions : List.of(new long[] {-1, 0}, new long[] {1, -1}, new long[] {1, 2})) {
            assertThrows(IllegalArgumentException.class, new Executable() {
                @Override
                public void execute() {
                    new Snapshot(versions[0], versions[1], List.of(), Kind.CONTINUE);
                }
            });
        }
    }

    @Test
    void disabledInstructionsNeverInventPermissionOrAcceptInput() {
        TaskInstructionPort port = TaskRecordPort.disabled().instructions();
        assertFalse(port.enabled());
        assertEquals(Directive.WAIT, port.check(null, 0));
        assertTrue(port.recoverable(10).isEmpty());
        for (String method : List.of("accept", "bind", "applied", "complete", "query")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, new Executable() {
                @Override
                public void execute() {
                    switch (method) {
                        case "accept" -> port.accept(new Command("task", "first", 0, Kind.AMEND, "write B"));
                        case "bind" -> port.bind(null);
                        case "applied" -> port.applied(null, 0, "run", 1, Map.of());
                        case "complete" -> port.complete(null, 0, 0, success());
                        case "query" -> port.query("task");
                        default -> throw new AssertionError(method);
                    }
                }
            });
            assertEquals(LlmTexts.TASK_INSTRUCTIONS_REQUIRED, failure.getMessage());
        }
    }

    private static Command command(Fixture fixture, String id, long expected, Kind kind, String text) {
        return new Command(fixture.taskId, id, expected, kind, text);
    }

    private static Map<String, Object> success() {
        return Map.of("status", "SUCCESS", "value", "verified output");
    }

    private static ExecutionEvent event(RunClaim claim, String eventId, EventKind kind, Map<String, Object> details) {
        return new ExecutionEvent(eventId, claim.taskId(), claim.rootRunId(), "root-task", "operation", "branch",
                1, kind, details);
    }

    private static final class Fixture {
        /** 共享领取与事实的检查替身。 */
        private final RecordingTaskRecords records = new RecordingTaskRecords();
        /** 与记录替身共用事务监视器的指令替身。 */
        private final RecordingTaskInstructions instructions = records.enableInstructions();
        /** 本检查使用的稳定任务身份。 */
        private final String taskId = records.create(new TaskSpec("write A", Map.of(), "A", List.of("write"))).taskId();
    }
}
