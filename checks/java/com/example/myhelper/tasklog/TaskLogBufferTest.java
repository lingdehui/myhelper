package com.example.myhelper.tasklog;

import com.example.myhelper.tasklog.TaskLogBuffer.Batch;
import com.example.myhelper.tasklog.TaskLogBuffer.Entry;
import com.example.myhelper.tasklog.TaskLogPort.Context;
import com.example.myhelper.tasklog.TaskLogPort.Event;
import com.example.myhelper.tasklog.TaskLogPort.Kind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class TaskLogBufferTest {
    /** 用同一任务的两个运行验证缓存保留各自身份。 */
    private static final Context ROOT = new Context("task", "root", "root", "root", "root", 0);
    private static final Context RESUMED = new Context("task", "later", "later", "later", "later", 0);

    @Test
    void replaysRecentTaskEventsAfterCursorAndReportsEviction() {
        TaskLogBuffer buffer = new TaskLogBuffer(2);
        Event first = event(ROOT, "start");
        Event second = event(ROOT, "planning");
        Event third = event(RESUMED, "resume");
        buffer.onEvent(first);
        Batch opened = buffer.readAfter(0);
        assertEquals(List.of(new Entry(1, first)), opened.entries());

        buffer.onEvent(event(null, "detached"));
        buffer.onEvent(event(new Context("task", "", "x", "x", "x", 0), "missing root"));
        buffer.onEvent(second);
        buffer.onEvent(third);

        Batch stale = buffer.readAfter(0);
        assertTrue(stale.historyGap());
        assertEquals(List.of(new Entry(2, second), new Entry(3, third)), stale.entries());
        assertEquals(3, stale.lastSequence());
        Batch resumed = buffer.readAfter(opened.lastSequence());
        assertFalse(resumed.historyGap());
        assertEquals(stale.entries(), resumed.entries());
        assertThrows(UnsupportedOperationException.class, resumed.entries()::clear);
        assertEquals(List.of(new Entry(1, first)), opened.entries());
        assertEquals(new Batch(3, false, 0, List.of()), buffer.readAfter(resumed.lastSequence()));
    }

    @Test
    void readsDeliveryLossEvenWhenThereAreNoNewEntries() {
        AtomicLong missed = new AtomicLong();
        TaskLogBuffer buffer = new TaskLogBuffer(1, missed::get);
        assertEquals(new Batch(0, false, 0, List.of()), buffer.readAfter(0));
        missed.incrementAndGet();
        assertEquals(new Batch(0, false, 1, List.of()), buffer.readAfter(0));
    }

    @Test
    void springDesktopListenerReceivesTheAsynchronouslyPublishedEvent() {
        try (var context = configuredContext(Map.of("myhelper.ui", "desktop"))) {
            AsyncTaskLog publisher = context.getBean(AsyncTaskLog.class);
            TaskLogBuffer buffer = context.getBean(TaskLogBuffer.class);
            Event start = event(ROOT, "start");
            publisher.publish(start);
            publisher.close();
            assertEquals(new Batch(1, false, 0, List.of(new Entry(1, start))), buffer.readAfter(0));
        }
    }

    @Test
    void terminalKeepsPublisherAndDesktopCanDisablePublishing() {
        try (var console = configuredContext(Map.of());
             var disabled = configuredContext(Map.of("myhelper.ui", "desktop", "myhelper.task-log.enabled", false))) {
            assertNotNull(console.getBean(AsyncTaskLog.class));
            assertNull(console.getBeanProvider(TaskLogBuffer.class).getIfAvailable());
            assertNull(disabled.getBeanProvider(TaskLogPort.class).getIfAvailable());
            assertEquals(new Batch(0, false, 0, List.of()), disabled.getBean(TaskLogBuffer.class).readAfter(0));
        }
    }

    private static Event event(Context context, String id) {
        return new Event(Instant.EPOCH, context, id, Kind.OBSERVED, id);
    }

    private static AnnotationConfigApplicationContext configuredContext(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("buffer-test", properties));
        context.register(TaskLogConfiguration.class);
        context.refresh();
        return context;
    }
}
