package com.example.myhelper.tasklog;

import com.example.myhelper.records.RunArchivePort;
import com.example.myhelper.records.RunArchivePort.ArchiveRef;
import com.example.myhelper.records.RunArchivePort.ArchivedRun;
import com.example.myhelper.records.TaskRecordPort.EventKind;
import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.tasklog.TaskLogPort.Context;
import com.example.myhelper.tasklog.TaskLogPort.Event;
import com.example.myhelper.tasklog.TaskLogPort.Kind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 实时队列隔离业务线程，归档沿用同一事实投影。 */
class TaskLogTest {
    /** 用于断言原始业务内容不会随摘要暴露。 */
    private static final String PRIVATE_VALUE = "privateMarker123";
    /** 固定测试运行身份。 */
    private static final ArchiveRef REF = new ArchiveRef("task-1", "root-1");
    /** 保留事件产生时间与归档提交顺序的差别。 */
    private static final Instant TIME = Instant.parse("2026-11-17T04:50:00Z");

    @Test
    void slowSinkDoesNotBlockProducersAndDropsOnlyNewOverflow() throws Exception {
        BlockingSink sink = new BlockingSink();
        try (AsyncTaskLog log = new AsyncTaskLog(1, sink);
             var producer = Executors.newSingleThreadExecutor()) {
            try {
                producer.submit(new Publish(log, "first")).get(3, TimeUnit.SECONDS);
                assertTrue(sink.entered.await(3, TimeUnit.SECONDS));
                producer.submit(new Publish(log, "queued", "overflow")).get(3, TimeUnit.SECONDS);
                assertEquals(1, log.droppedCount());
            } finally {
                sink.release.countDown();
            }
            assertTrue(sink.delivered.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("first", "queued"), sink.ids);
            assertEquals(0, log.failedCount());
        }
    }

    @Test
    void sinkFailureIsCountedAndTheNextEventStillArrives() throws Exception {
        FailingSink sink = new FailingSink();
        try (AsyncTaskLog log = new AsyncTaskLog(4, sink)) {
            TaskLogPort.report(log, event("fail"));
            TaskLogPort.report(log, event("next"));
            assertTrue(sink.delivered.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("fail", "next"), sink.ids);
            assertEquals(1, log.failedCount());
            assertEquals(0, log.droppedCount());
        }
    }

    @Test
    void historyKeepsTheArchiveOrderCompletenessAndProjection() {
        ExecutionEvent intent = fact("intent", EventKind.INTENT, Map.of(
                "tool", "readFile", "goal", PRIVATE_VALUE, "arguments", Map.of("token", PRIVATE_VALUE),
                "recordedAt", TIME.toString()));
        ExecutionEvent observed = fact("observed", EventKind.OBSERVED, Map.of(
                "phase", PRIVATE_VALUE, "evidence", Map.of("data", PRIVATE_VALUE),
                "recordedAt", TIME.minusSeconds(1).toString()));
        ExecutionEvent ended = fact("ended", EventKind.RUN_ENDED, Map.of(
                "outcome", Map.of("status", "UNKNOWN", "error", PRIVATE_VALUE, "value", PRIVATE_VALUE)));
        List<ExecutionEvent> facts = List.of(intent, observed, ended);
        ArchiveStub archives = new ArchiveStub(new ArchivedRun(REF, 1, ended, null, false,
                List.of("图不可读"), facts, false, List.of("验收事件未确认")));
        TaskLogHistory history = new TaskLogHistory(archives);

        TaskLogHistory.History result = history.read(REF).orElseThrow();

        assertEquals(REF, result.ref());
        assertFalse(result.graphComplete());
        assertFalse(result.evidenceComplete());
        assertEquals(List.of(TaskLogProjection.from(intent), TaskLogProjection.from(observed),
                TaskLogProjection.from(ended)), result.entries());
        assertEquals(new Context("task-1", "root-1", "run-2", "node-3", "branch-4", 2),
                result.entries().getFirst().context());
        assertEquals("intent", result.entries().getFirst().correlationId());
        assertEquals(Kind.TOOL_INTENT, result.entries().getFirst().kind());
        assertTrue(result.entries().getFirst().summary().contains("readFile"));
        assertEquals(TIME, result.entries().getFirst().at());
        assertEquals(TIME.minusSeconds(1), result.entries().get(1).at());
        assertNull(result.entries().getLast().at());
        assertTrue(result.entries().getLast().summary().contains("UNKNOWN"));
        assertFalse(result.toString().contains(PRIVATE_VALUE));
        assertTrue(history.read(new ArchiveRef("task-1", "missing")).isEmpty());
    }

    @Test
    void projectionSummarizesPlanAndRejectsUnknownStatusAndUnsafeLabels() {
        Event plan = TaskLogProjection.from(fact("plan", EventKind.MODEL_CALL_RETURNED,
                Map.of("decisionKind", "PLAN", "stepCount", 3, "plan", PRIVATE_VALUE)));
        Event returned = TaskLogProjection.from(fact("returned", EventKind.RETURNED,
                Map.of("tool", "tool\n" + PRIVATE_VALUE, "dispatchStarted", false,
                        "outcome", Map.of("status", PRIVATE_VALUE))));

        assertEquals(Kind.PLAN_RETURNED, plan.kind());
        assertTrue(plan.summary().contains("steps=3"));
        assertEquals(Kind.TOOL_RETURNED, returned.kind());
        assertTrue(returned.summary().contains("工具未派发"));
        assertFalse(plan.summary().contains(PRIVATE_VALUE));
        assertFalse(returned.summary().contains(PRIVATE_VALUE));
        assertFalse(returned.summary().contains("\n"));
    }

    private static Event event(String id) {
        return new Event(TIME, null, id, Kind.OBSERVED, id);
    }

    private static ExecutionEvent fact(String id, EventKind kind, Map<String, Object> details) {
        return new ExecutionEvent(id, REF.taskId(), REF.rootRunId(), "run-2", "node-3", "branch-4", 2, kind, details);
    }

    private static final class Publish implements Runnable {
        /** 使用实际投递组件发送事件。 */
        private final TaskLogPort log;
        /** 生产线程依次发送的事件 ID。 */
        private final String[] ids;

        private Publish(TaskLogPort log, String... ids) {
            this.log = log;
            this.ids = ids;
        }

        @Override
        public void run() {
            for (String id : ids) {
                TaskLogPort.report(log, event(id));
            }
        }
    }

    private static final class BlockingSink implements Consumer<Event> {
        /** 第一条已开始消费，队列中还没有待处理事件。 */
        private final CountDownLatch entered = new CountDownLatch(1);
        /** 检查线程决定何时释放慢输出。 */
        private final CountDownLatch release = new CountDownLatch(1);
        /** 两个已接收事件均已交给消费者。 */
        private final CountDownLatch delivered = new CountDownLatch(2);
        /** 消费线程按接收顺序登记，检查线程在 delivered 后读取。 */
        private final List<String> ids = new ArrayList<>();

        @Override
        public void accept(Event event) {
            if (event.correlationId().equals("first")) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            ids.add(event.correlationId());
            delivered.countDown();
        }
    }

    private static final class FailingSink implements Consumer<Event> {
        /** 第一次故障后的下一条事件已经送达。 */
        private final CountDownLatch delivered = new CountDownLatch(1);
        /** 记录失败事件和后续正常事件的接收顺序。 */
        private final List<String> ids = new ArrayList<>();

        @Override
        public void accept(Event event) {
            ids.add(event.correlationId());
            if (event.correlationId().equals("fail")) {
                throw new IllegalStateException(PRIVATE_VALUE);
            }
            delivered.countDown();
        }
    }

    private static final class ArchiveStub implements RunArchivePort {
        /** 单个已结束运行的权威样本。 */
        private final ArchivedRun archive;

        private ArchiveStub(ArchivedRun archive) {
            this.archive = archive;
        }

        @Override
        public Optional<ArchivedRun> readArchive(ArchiveRef ref) {
            return archive.ref().equals(ref) ? Optional.of(archive) : Optional.empty();
        }

        @Override
        public List<ArchiveRef> pendingConsolidations(int limit) {
            throw new AssertionError("日志读取不得扫描固化待办");
        }

        @Override
        public void acknowledgeConsolidation(ArchiveRef ref) {
            throw new AssertionError("日志读取不得修改固化确认");
        }
    }
}
