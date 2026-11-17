package com.example.myhelper.tasklog;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 独立的有界实时投递。输出拥堵时丢弃新事件，业务线程不等待输出。 */
public final class AsyncTaskLog implements TaskLogPort, AutoCloseable {
    /** 超载与消费故障的诊断，不带业务数据。 */
    private static final System.Logger LOGGER = System.getLogger(AsyncTaskLog.class.getName());
    /** 只有日志消费者线程调用的输出端。 */
    private final Consumer<Event> sink;
    /** 单线程串行输出，以队列入列顺序处理已接收事件。 */
    private final ThreadPoolExecutor worker;
    /** 等待消费线程报告的队列拒绝次数。 */
    private final AtomicLong pendingDrops = new AtomicLong();
    /** 自进程启动以来的拒绝和关停丢弃次数。 */
    private final AtomicLong dropped = new AtomicLong();
    /** 自进程启动以来的消费异常次数。 */
    private final AtomicLong failed = new AtomicLong();

    public AsyncTaskLog(int capacity, Consumer<Event> sink) {
        this.sink = Objects.requireNonNull(sink);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), new java.util.concurrent.ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable task) {
                        return Thread.ofPlatform().daemon().name("myhelper-task-log").unstarted(task);
                    }
                });
    }

    @Override
    public void publish(Event event) {
        Objects.requireNonNull(event);
        try {
            worker.execute(new Delivery(event));
        } catch (RejectedExecutionException ignored) {
            dropped.incrementAndGet();
            pendingDrops.incrementAndGet();
        }
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long failedCount() {
        return failed.get();
    }

    /** 正常退出给已排队消息一次有界排空机会。 */
    @Override
    public void close() {
        worker.shutdown();
        try {
            if (worker.awaitTermination(1, TimeUnit.SECONDS)) {
                return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        dropped.addAndGet(worker.shutdownNow().size());
    }

    private final class Delivery implements Runnable {
        /** 本次交给输出端的安全摘要。 */
        private final Event event;

        private Delivery(Event event) {
            this.event = event;
        }

        @Override
        public void run() {
            try {
                sink.accept(event);
            } catch (RuntimeException failure) {
                long failures = failed.incrementAndGet();
                if (failures == 1) {
                    LOGGER.log(System.Logger.Level.WARNING, "任务日志输出失败，后续事件继续投递");
                }
            } finally {
                long count = pendingDrops.getAndSet(0);
                if (count > 0) {
                    LOGGER.log(System.Logger.Level.WARNING, "任务实时日志投递拒绝 {0} 条", count);
                }
            }
        }
    }
}
