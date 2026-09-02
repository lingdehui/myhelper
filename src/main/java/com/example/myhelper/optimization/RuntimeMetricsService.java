package com.example.myhelper.optimization;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.LongAdder;

/**
 * 元优化器使用的轻量运行指标采集器。
 *
 * <p>只记录计数、耗时和用户已确认的唤醒结果，不记录用户正文、工具参数或工具返回内容。
 * 指标因此既可用于调参，也不会把运行日志中的敏感上下文复制到新的数据空间。</p>
 */
@Service
public class RuntimeMetricsService {

    private final LongAdder planningRequests = new LongAdder();
    private final LongAdder planningCacheHits = new LongAdder();
    private final LongAdder planningElapsedMs = new LongAdder();
    private final LongAdder planningFailures = new LongAdder();
    private final LongAdder routingFastPaths = new LongAdder();
    private final LongAdder routingFallbacks = new LongAdder();
    private final LongAdder cacheEvictions = new LongAdder();
    private final LongAdder toolCalls = new LongAdder();
    private final LongAdder toolSuccesses = new LongAdder();
    private final LongAdder toolFailures = new LongAdder();
    private final LongAdder wakeTriggers = new LongAdder();
    private final LongAdder wakeConfirmed = new LongAdder();
    private final LongAdder wakeTimeouts = new LongAdder();
    private final LongAdder wakeMissReports = new LongAdder();

    public void recordPlanning(boolean cacheHit, long elapsedMs, boolean failed) {
        planningRequests.increment();
        if (cacheHit) planningCacheHits.increment();
        if (failed) planningFailures.increment();
        planningElapsedMs.add(Math.max(0, elapsedMs));
    }

    public void recordRoutingFastPath() { routingFastPaths.increment(); }
    public void recordRoutingFallback() { routingFallbacks.increment(); }
    public void recordCacheEviction() { cacheEvictions.increment(); }

    public void recordToolCall(boolean success) {
        toolCalls.increment();
        if (success) toolSuccesses.increment();
        else toolFailures.increment();
    }

    public void recordWakeTrigger() { wakeTriggers.increment(); }
    public void recordWakeConfirmed() { wakeConfirmed.increment(); }
    public void recordWakeTimeout() { wakeTimeouts.increment(); }
    public void recordWakeMissReported() { wakeMissReports.increment(); }

    /** 当前进程启动以来的单调快照；可用 {@link Snapshot#deltaSince(Snapshot)} 取试验期窗口。 */
    public Snapshot snapshot() {
        return new Snapshot(
                planningRequests.sum(), planningCacheHits.sum(), planningElapsedMs.sum(), planningFailures.sum(),
                routingFastPaths.sum(), routingFallbacks.sum(), cacheEvictions.sum(),
                toolCalls.sum(), toolSuccesses.sum(), toolFailures.sum(),
                wakeTriggers.sum(), wakeConfirmed.sum(), wakeTimeouts.sum(), wakeMissReports.sum());
    }

    public record Snapshot(
            long planningRequests,
            long planningCacheHits,
            long planningElapsedMs,
            long planningFailures,
            long routingFastPaths,
            long routingFallbacks,
            long cacheEvictions,
            long toolCalls,
            long toolSuccesses,
            long toolFailures,
            long wakeTriggers,
            long wakeConfirmed,
            long wakeTimeouts,
            long wakeMissReports) {

        public Snapshot deltaSince(Snapshot earlier) {
            if (earlier == null) return this;
            return new Snapshot(
                    delta(planningRequests, earlier.planningRequests),
                    delta(planningCacheHits, earlier.planningCacheHits),
                    delta(planningElapsedMs, earlier.planningElapsedMs),
                    delta(planningFailures, earlier.planningFailures),
                    delta(routingFastPaths, earlier.routingFastPaths),
                    delta(routingFallbacks, earlier.routingFallbacks),
                    delta(cacheEvictions, earlier.cacheEvictions),
                    delta(toolCalls, earlier.toolCalls), delta(toolSuccesses, earlier.toolSuccesses),
                    delta(toolFailures, earlier.toolFailures), delta(wakeTriggers, earlier.wakeTriggers),
                    delta(wakeConfirmed, earlier.wakeConfirmed), delta(wakeTimeouts, earlier.wakeTimeouts),
                    delta(wakeMissReports, earlier.wakeMissReports));
        }

        public double cacheHitRate() { return ratio(planningCacheHits, planningRequests); }
        public double toolSuccessRate() { return ratio(toolSuccesses, toolCalls); }
        public double routingFastPathRate() { return ratio(routingFastPaths, routingFastPaths + routingFallbacks); }
        public double wakeFalsePositiveRate() { return ratio(wakeTimeouts, wakeTriggers); }
        public double wakeMissRate() { return ratio(wakeMissReports, wakeTriggers + wakeMissReports); }
        public double averagePlanningMs() { return ratio(planningElapsedMs, planningRequests); }

        private static long delta(long now, long before) { return Math.max(0, now - before); }
        private static double ratio(long numerator, long denominator) {
            return denominator <= 0 ? 0.0 : (double) numerator / denominator;
        }
    }
}
