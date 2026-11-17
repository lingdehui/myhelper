package com.example.myhelper.orchestration;

import com.example.myhelper.records.TaskRecordPort.EventKind;
import com.example.myhelper.support.RecordingTaskRecords;
import com.example.myhelper.tasklog.TaskLogPort;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用实际记录接线确认发布时机与业务写入异常。 */
class RunRecordAccessLogTest {
    @Test
    void showsConfirmedFactsAndReportsRejectedIntentWithoutPublishingIt() {
        var records = new RecordingTaskRecords();
        var log = new Capture(records);
        var request = new AgentOrchestrator.Request("private goal", Map.of(), "local", List.of("echo"));
        var access = new RunRecordAccess(records, log, "root", request, null);

        access.emit("run", "first", "branch", 1, EventKind.INTENT, Map.of("tool", "echo"));
        records.failAppend(EventKind.INTENT);
        assertThrows(IllegalStateException.class, new EmitRejectedIntent(access));
        access.end(new AgentOrchestrator.Outcome(AgentOrchestrator.Status.UNKNOWN, null, "private error"));

        assertEquals(List.of(TaskLogPort.Kind.TOOL_INTENT, TaskLogPort.Kind.RECORD_UNCONFIRMED,
                TaskLogPort.Kind.RUN_ENDED), log.kinds);
        assertEquals(List.of(true, false, true), log.committedAtPublication);
        assertTrue(records.finalization("root").evidenceGaps().getFirst().contains("nodeId=second"));
        assertFalse(log.summaries.toString().contains("private"));
    }

    private static final class EmitRejectedIntent implements org.junit.jupiter.api.function.Executable {
        /** 使用与已接受事件相同的正式写入路径。 */
        private final RunRecordAccess access;

        private EmitRejectedIntent(RunRecordAccess access) { this.access = access; }

        @Override
        public void execute() {
            access.emit("run", "second", "branch", 1, EventKind.INTENT, Map.of("tool", "echo"));
        }
    }

    private static final class Capture implements TaskLogPort {
        /** 查询发布当下的记录状态，确保诊断不抢在确认前。 */
        private final RecordingTaskRecords records;
        /** 已发布的事件阶段。 */
        private final List<Kind> kinds = new ArrayList<>();
        /** 对应的事件是否已在记录端可见。 */
        private final List<Boolean> committedAtPublication = new ArrayList<>();
        /** 用于验证端口收到的是安全摘要。 */
        private final List<String> summaries = new ArrayList<>();

        private Capture(RecordingTaskRecords records) { this.records = records; }

        @Override
        public void publish(Event event) {
            boolean committed = false;
            for (var fact : records.events(event.context().taskId())) {
                if (fact.eventId().equals(event.correlationId())) {
                    committed = true;
                    break;
                }
            }
            kinds.add(event.kind());
            committedAtPublication.add(committed);
            summaries.add(event.summary());
        }
    }
}
