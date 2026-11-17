package com.example.myhelper.tasklog;

import com.example.myhelper.tasklog.TaskLogPort.Context;
import com.example.myhelper.tasklog.TaskLogPort.Event;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 为本机桌面视图保留近期任务日志摘要。 */
public final class TaskLogBuffer implements TaskLogPort.Listener {
    /** 可回放的任务事件数上限。 */
    private final int capacity;
    /** 写入缓冲前异步投递队列的累计丢弃数。 */
    private final LongSupplier upstreamDrops;
    /** 按投递顺序保存，访问时持有本对象的锁。 */
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    /** 最新事件序号，访问时持有本对象的锁。 */
    private long lastSequence;

    public TaskLogBuffer(int capacity) {
        this(capacity, TaskLogBuffer::noUpstreamDrops);
    }

    TaskLogBuffer(int capacity, LongSupplier upstreamDrops) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("任务日志缓冲容量必须为正数");
        }
        this.capacity = capacity;
        this.upstreamDrops = Objects.requireNonNull(upstreamDrops);
    }

    @Override
    public synchronized void onEvent(Event event) {
        Context context = event.context();
        if (context == null || missingId(context.taskId()) || missingId(context.rootRunId())) {
            return;
        }
        if (entries.size() == capacity) {
            entries.removeFirst();
        }
        entries.addLast(new Entry(++lastSequence, event));
    }

    /** 读取游标之后的可用事件，首次读取传零。 */
    public Batch readAfter(long sequence) {
        if (sequence < 0) {
            throw new IllegalArgumentException("任务日志游标不能为负数");
        }
        long droppedCount = upstreamDrops.getAsLong();
        synchronized (this) {
            if (sequence >= lastSequence) {
                return new Batch(lastSequence, false, droppedCount, List.of());
            }
            boolean historyGap = sequence < lastSequence - entries.size();
            List<Entry> available = new ArrayList<>();
            for (Entry entry : entries) {
                if (entry.sequence() > sequence) {
                    available.add(entry);
                }
            }
            return new Batch(lastSequence, historyGap, droppedCount, available);
        }
    }

    private static boolean missingId(String id) {
        return id == null || id.isBlank();
    }

    private static long noUpstreamDrops() {
        return 0;
    }

    public record Entry(
            /** 消费时分配的序号。 */
            long sequence,
            /** 生产端提供的白名单事件摘要。 */
            Event event) { }

    public record Batch(
            /** 下一次读取的游标。 */
            long lastSequence,
            /** 游标之后有事件已经移出缓冲。 */
            boolean historyGap,
            /** 应用日志投递队列的累计丢弃数。 */
            long droppedCount,
            /** 游标之后按投递顺序可读的事件。 */
            List<Entry> entries) {
        public Batch {
            entries = List.copyOf(entries);
        }
    }
}
