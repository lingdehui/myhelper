package com.example.myhelper.application;

import com.example.myhelper.orchestration.RunActivityPort;

/** 本地任务占用与空闲调用共享同一仲裁点，实际工作由调用方在锁外完成。 */
public final class TaskActivity implements RunActivityPort {
    /** 并行根运行、尚未受理的交互提交和启动恢复扫描分别持有占用。 */
    private int occupations;
    /** 即使一次任务在两次空闲观察之间开始并结束，旧计划也不能继续。 */
    private long generation;
    /** 任务开始会使空闲结果失效，但已派发调用仍保留此位置直到实际返回。 */
    private IdleCall inFlight;

    @Override
    public synchronized Occupation occupy() {
        occupations++;
        generation++;
        return new Occupation();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(occupations != 0, generation);
    }

    public synchronized boolean currentIdle(long expectedGeneration) {
        return occupations == 0 && generation == expectedGeneration;
    }

    /** 在调用即将派发时取得许可。返回 null 表示当前不能发起请求。 */
    public synchronized IdleCall tryStartIdleCall(long expectedGeneration) {
        if (!currentIdle(expectedGeneration) || inFlight != null) {
            return null;
        }
        inFlight = new IdleCall();
        return inFlight;
    }

    public record Snapshot(
            /** 有任务受理、运行、收尾或启动扫描正在进行。 */
            boolean busy,
            /** 取得状态时的活动版本。 */
            long generation) {}

    public final class Occupation implements RunActivityPort.Occupation {
        /** 只在 TaskActivity 的监视器内访问，保证重复释放无影响。 */
        private boolean closed;

        private Occupation() {}

        @Override
        public void close() {
            synchronized (TaskActivity.this) {
                if (!closed) {
                    closed = true;
                    occupations--;
                    generation++;
                }
            }
        }
    }

    public final class IdleCall implements AutoCloseable {
        private IdleCall() {}

        @Override
        public void close() {
            synchronized (TaskActivity.this) {
                if (inFlight == this) {
                    inFlight = null;
                }
            }
        }
    }
}
