package com.example.myhelper.optimization;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 元优化策略测试：验证指标驱动的候选和回滚判定，不依赖 Spring、模型或数据库。 */
class MetaOptimizationPolicyTest {

    @Test
    void expandsCacheOnlyWhenThereAreEvictionsAndPoorHitRate() {
        RuntimeMetricsService.Snapshot metrics = snapshot(50, 5, 60_000, 0,
                0, 50, 12, 0, 0, 0, 0, 0, 0, 0);

        MetaOptimizationPolicy.Proposal proposal = MetaOptimizationPolicy
                .diagnose(metrics, tuningStatus(500, 0.45, 0.45, 2), 50, 20)
                .orElseThrow();

        assertEquals(RuntimeTuningService.Parameter.TOOL_CACHE_SIZE, proposal.parameter());
        assertEquals(625, proposal.targetValue());
    }

    @Test
    void tightensWakeMatchingWhenConfirmedFalseWakeRateIsHigh() {
        RuntimeMetricsService.Snapshot metrics = snapshot(0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 20, 10, 6, 0);

        MetaOptimizationPolicy.Proposal proposal = MetaOptimizationPolicy
                .diagnose(metrics, tuningStatus(500, 0.45, 0.45, 2), 50, 20)
                .orElseThrow();

        assertEquals(RuntimeTuningService.Parameter.WAKE_WORD_MAX_EDIT_DISTANCE, proposal.parameter());
        assertEquals(1, proposal.targetValue());
    }

    @Test
    void rollsBackAnyPlanningTrialWhenToolSuccessFallsBelowSafetyFloor() {
        RuntimeTuningService.Trial trial = new RuntimeTuningService.Trial(
                RuntimeTuningService.Parameter.ROUTING_CATEGORY_MIN_SCORE.key(), 0.45, 0.42,
                "test", System.currentTimeMillis(), snapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                "sandbox.yml", false);
        RuntimeMetricsService.Snapshot trialWindow = snapshot(50, 20, 30_000, 0,
                10, 40, 0, 50, 40, 10, 0, 0, 0, 0);

        assertEquals(MetaOptimizationPolicy.TrialDecision.ROLLBACK,
                MetaOptimizationPolicy.evaluate(trial, trialWindow, 50, 20));
    }

    @Test
    void snapshotDeltaUsesOnlyTheTrialWindow() {
        RuntimeMetricsService.Snapshot before = snapshot(20, 5, 10_000, 0,
                2, 18, 3, 20, 18, 2, 2, 1, 1, 0);
        RuntimeMetricsService.Snapshot after = snapshot(35, 12, 19_000, 1,
                8, 27, 5, 30, 27, 3, 5, 3, 2, 1);

        RuntimeMetricsService.Snapshot delta = after.deltaSince(before);

        assertEquals(15, delta.planningRequests());
        assertEquals(7, delta.planningCacheHits());
        assertEquals(10, delta.toolCalls());
        assertTrue(delta.cacheHitRate() > 0.4);
    }

    private static RuntimeTuningService.Status tuningStatus(double cache, double category, double direct, double wake) {
        return new RuntimeTuningService.Status(Map.of(
                RuntimeTuningService.Parameter.TOOL_CACHE_SIZE.key(), cache,
                RuntimeTuningService.Parameter.ROUTING_CATEGORY_MIN_SCORE.key(), category,
                RuntimeTuningService.Parameter.ROUTING_DIRECT_TOOL_MIN_SCORE.key(), direct,
                RuntimeTuningService.Parameter.WAKE_WORD_MAX_EDIT_DISTANCE.key(), wake), null, List.of());
    }

    private static RuntimeMetricsService.Snapshot snapshot(long planning, long cacheHits, long planningMs, long planningFailures,
                                                           long routeFast, long routeFallback, long evictions,
                                                           long toolCalls, long toolSuccess, long toolFailures,
                                                           long wakeTriggers, long wakeConfirmed, long wakeTimeouts, long wakeMisses) {
        return new RuntimeMetricsService.Snapshot(planning, cacheHits, planningMs, planningFailures,
                routeFast, routeFallback, evictions, toolCalls, toolSuccess, toolFailures,
                wakeTriggers, wakeConfirmed, wakeTimeouts, wakeMisses);
    }
}
