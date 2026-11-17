package com.example.myhelper.interaction.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myhelper.interaction.desktop.behavior.DesktopBehavior.Display;
import com.example.myhelper.orchestration.AgentOrchestrator.Handle;
import com.example.myhelper.orchestration.AgentOrchestrator.Outcome;
import com.example.myhelper.orchestration.AgentOrchestrator.Status;
import com.example.myhelper.tasklog.TaskLogPort.Context;
import com.example.myhelper.tasklog.TaskLogPort.Event;
import com.example.myhelper.tasklog.TaskLogPort.Kind;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class DesktopTasksTest {
    /** 固定时间便于检查模型等待文案。 */
    private static final Instant AT = Instant.parse("2026-11-17T08:00:00Z");

    @Test
    void eventsBeforeHandleMergeAndConcurrentTasksKeepSeparateOutcomes() {
        DesktopTasks tasks = new DesktopTasks();
        tasks.accept(event("A", "run-A", "model-A", Kind.MODEL_STARTED, "规划请求"));
        tasks.accept(event("B", "run-B", "model-B", Kind.MODEL_STARTED, "规划请求"));
        var first = handle("A", "run-A");
        var second = handle("B", "run-B");
        tasks.attach(first, "任务甲");
        tasks.attach(second, "任务乙");
        assertEquals(2, tasks.views(AT, false).size());
        assertEquals("等待模型 · 5 秒", tasks.views(AT.plusSeconds(5), false).getFirst().phase());

        first.result().complete(new Outcome(Status.CANCELLED, null, "任务超时"));
        tasks.current("A").refreshOutcome();
        var stopping = tasks.views(AT, false).getFirst();
        assertEquals("正在收尾", stopping.phase());
        assertTrue(stopping.active());
        assertFalse(stopping.cancellable());
        assertEquals("任务超时", stopping.outcome());

        second.result().complete(new Outcome(Status.NEEDS_INPUT, "请选择日期", null));
        second.settled().complete(second.result().join());
        tasks.current("B").refreshOutcome();
        var waiting = tasks.views(AT, false).getLast();
        assertEquals("任务乙", waiting.input());
        assertEquals("需要补充", waiting.phase());
        assertTrue(waiting.outcome().contains("请选择日期"));
        assertFalse(waiting.active());
    }

    @Test
    void aLateOldRunCannotReplaceASettledNewRun() {
        var tasks = new DesktopTasks();
        tasks.accept(event("A", "old", "old-start", Kind.RUN_STARTED, "受理"));
        tasks.accept(event("A", "new", "new-start", Kind.RUN_STARTED, "恢复"));
        tasks.accept(event("A", "new", "new-end", Kind.RUN_ENDED, "运行收尾 status=SUCCESS"));
        tasks.attach(handle("A", "old"), null);
        tasks.accept(event("A", "old", "old-end", Kind.RUN_ENDED, "运行收尾 status=FAILED"));

        var view = tasks.views(AT, false).getFirst();
        assertEquals("new", tasks.current("A").root);
        assertEquals("已完成", view.phase());
        assertTrue(view.events().stream().anyMatch(DesktopTasksTest::isOldFailure));
    }

    @Test
    void droppedEventsClearUnconfirmedModelWait() {
        var tasks = new DesktopTasks();
        tasks.accept(event("A", "run", "model", Kind.MODEL_STARTED, "规划请求"));
        tasks.markReplayGap();
        assertEquals("进行中", tasks.views(AT, false).getFirst().phase());
    }

    @Test
    void successPoseWaitsForSettlementAndYieldsToAnotherTask() {
        var tasks = new DesktopTasks();
        var first = handle("A", "run-A");
        tasks.attach(first, "任务甲");
        first.result().complete(new Outcome(Status.SUCCESS, "完成", null));
        tasks.current("A").refreshOutcome();
        assertEquals(Display.SETTLING, tasks.behaviorDisplay(Instant.now()));

        first.settled().complete(first.result().join());
        tasks.current("A").refreshOutcome();
        assertEquals(Display.RESULT, tasks.behaviorDisplay(Instant.now()));

        tasks.attach(handle("B", "run-B"), "任务乙");
        assertEquals(Display.WORKING, tasks.behaviorDisplay(Instant.now()));
    }

    @Test
    void failedOrUnconfirmedSettlementNeverProducesSuccessPose() {
        var tasks = new DesktopTasks();
        tasks.accept(event("A", "run-A", "end-A", Kind.RUN_ENDED, "运行收尾 status=SUCCESS"));
        Instant later = AT.plusMillis(5);
        var failure = event("C", "run-C", "end-C", Kind.RUN_ENDED, "运行收尾 status=FAILED");
        tasks.accept(new Event(later, failure.context(), failure.correlationId(), failure.kind(), failure.summary()));
        assertEquals(Display.WORKING, tasks.behaviorDisplay(later));

        var second = handle("B", "run-B");
        tasks.attach(second, "任务乙");
        second.result().complete(new Outcome(Status.SUCCESS, "结果", null));
        second.settled().completeExceptionally(new IllegalStateException("归档失败"));
        tasks.current("B").refreshOutcome();
        assertEquals(Display.WORKING, tasks.behaviorDisplay(Instant.now()));
    }

    private static Event event(String task, String root, String id, Kind kind, String summary) {
        return new Event(AT, new Context(task, root, root, id, root, 0), id, kind, summary);
    }

    private static Handle handle(String task, String root) {
        return new Handle(root, new CompletableFuture<>(), new NoCancel(), task, new CompletableFuture<>());
    }

    private static boolean isOldFailure(DesktopWindow.Event event) {
        return event.source().startsWith("先前运行") && event.text().contains("FAILED");
    }

    private static final class NoCancel implements Runnable {
        @Override
        public void run() { }
    }
}
