package com.example.myhelper.optimization;

import java.util.Optional;

/**
 * 元优化的纯策略层。
 *
 * <p>策略刻意保守：只在样本充分时提出一个小步候选，且所有路由参数均保留原有的
 * 候选数量边界与多轮目录回退。这里不调用模型、不读写文件，因此可稳定单测。</p>
 */
public final class MetaOptimizationPolicy {

    private MetaOptimizationPolicy() { }

    public static Optional<Proposal> diagnose(RuntimeMetricsService.Snapshot metrics,
                                              RuntimeTuningService.Status tuning,
                                              int minPlanningSamples, int minWakeSamples) {
        if (metrics == null || tuning == null) return Optional.empty();

        // 缓存出现明显淘汰且命中率低，才增加容量；没有淘汰时不把“请求本来就很分散”误判为容量问题。
        if (metrics.planningRequests() >= minPlanningSamples
                && metrics.cacheEvictions() >= Math.max(10, minPlanningSamples / 5)
                && metrics.cacheHitRate() < 0.30) {
            double current = value(tuning, RuntimeTuningService.Parameter.TOOL_CACHE_SIZE);
            return Optional.of(new Proposal(RuntimeTuningService.Parameter.TOOL_CACHE_SIZE,
                    Math.min(2_000, current + Math.max(50, Math.round(current * 0.25))),
                    "缓存淘汰=" + metrics.cacheEvictions() + "、命中率=" + percentage(metrics.cacheHitRate())
                            + "，小步扩大规划缓存。"));
        }

        // 快速路由使用很少且平均规划很慢时，轻微降低召回阈值。低于 0.40 不再下降，且候选过宽仍回退旧流程。
        long routingSamples = metrics.routingFastPaths() + metrics.routingFallbacks();
        if (routingSamples >= minPlanningSamples
                && metrics.routingFastPathRate() < 0.25
                && metrics.averagePlanningMs() >= 1_000) {
            RuntimeTuningService.Parameter parameter = RuntimeTuningService.Parameter.ROUTING_CATEGORY_MIN_SCORE;
            double current = value(tuning, parameter);
            if (current > parameter.min()) {
                return Optional.of(new Proposal(parameter, current - parameter.step(),
                        "快速路由占比=" + percentage(metrics.routingFastPathRate()) + "、平均规划="
                                + Math.round(metrics.averagePlanningMs()) + "ms，尝试小幅放宽分类召回。"));
            }
        }

        // 已大量走快速路由但工具总成功率变差时，提高直接工具检索阈值，宁可回退目录浏览也不扩大不相关候选。
        if (routingSamples >= minPlanningSamples && metrics.toolCalls() >= minPlanningSamples
                && metrics.routingFastPathRate() > 0.70 && metrics.toolSuccessRate() < 0.85) {
            RuntimeTuningService.Parameter parameter = RuntimeTuningService.Parameter.ROUTING_DIRECT_TOOL_MIN_SCORE;
            double current = value(tuning, parameter);
            if (current < parameter.max()) {
                return Optional.of(new Proposal(parameter, current + parameter.step(),
                        "快速路由占比较高但工具成功率=" + percentage(metrics.toolSuccessRate())
                                + "，收紧直接工具候选。"));
            }
        }

        // 唤醒误触只有“触发后未收到命令而超时”这一可观测定义；无用户标签时不猜测 VAD 或声纹错误。
        if (metrics.wakeTriggers() >= minWakeSamples && metrics.wakeFalsePositiveRate() > 0.25) {
            RuntimeTuningService.Parameter parameter = RuntimeTuningService.Parameter.WAKE_WORD_MAX_EDIT_DISTANCE;
            double current = value(tuning, parameter);
            if (current > parameter.min()) {
                return Optional.of(new Proposal(parameter, current - parameter.step(),
                        "唤醒后无命令超时率=" + percentage(metrics.wakeFalsePositiveRate())
                                + "，收紧唤醒词模糊匹配。"));
            }
        }

        // “漏唤醒”无法从音频本身可靠推断，只使用明确的用户反馈；并且误触不高才放宽。
        if (metrics.wakeMissReports() >= 3 && metrics.wakeMissRate() > 0.20
                && metrics.wakeFalsePositiveRate() < 0.10) {
            RuntimeTuningService.Parameter parameter = RuntimeTuningService.Parameter.WAKE_WORD_MAX_EDIT_DISTANCE;
            double current = value(tuning, parameter);
            if (current < parameter.max()) {
                return Optional.of(new Proposal(parameter, current + parameter.step(),
                        "用户报告漏唤醒=" + metrics.wakeMissReports() + "，且误触率较低，放宽一档模糊匹配。"));
            }
        }
        return Optional.empty();
    }

    /** 试验期验证：指标不足时保持试验；任一明确退化即回滚。 */
    public static TrialDecision evaluate(RuntimeTuningService.Trial trial, RuntimeMetricsService.Snapshot trialMetrics,
                                         int minPlanningSamples, int minWakeSamples) {
        if (trial == null || trialMetrics == null) return TrialDecision.WAIT;
        RuntimeTuningService.Parameter parameter = RuntimeTuningService.Parameter.fromKey(trial.parameterPath())
                .orElse(null);
        // 自动策略当前只会创建已知指标关联参数；用户或上层 Agent 创建的普通热参数
        // 仍以工具成功率作为统一护栏，样本充分后允许保留。
        if (parameter == null) {
            if (trialMetrics.planningRequests() < minPlanningSamples) return TrialDecision.WAIT;
            return trialMetrics.toolCalls() >= minPlanningSamples && trialMetrics.toolSuccessRate() < 0.85
                    ? TrialDecision.ROLLBACK : TrialDecision.ACCEPT;
        }
        if (parameter == RuntimeTuningService.Parameter.WAKE_WORD_MAX_EDIT_DISTANCE) {
            if (trialMetrics.wakeTriggers() < minWakeSamples) return TrialDecision.WAIT;
            if (trialMetrics.wakeFalsePositiveRate() > 0.25 || trialMetrics.wakeMissRate() > 0.25) {
                return TrialDecision.ROLLBACK;
            }
            return TrialDecision.ACCEPT;
        }

        if (trialMetrics.planningRequests() < minPlanningSamples) return TrialDecision.WAIT;
        // 任何调整都不能以明显降低工具调用成功率为代价。
        if (trialMetrics.toolCalls() >= minPlanningSamples && trialMetrics.toolSuccessRate() < 0.85) {
            return TrialDecision.ROLLBACK;
        }
        if (parameter == RuntimeTuningService.Parameter.TOOL_CACHE_SIZE) {
            return trialMetrics.cacheEvictions() == 0 || trialMetrics.cacheHitRate() >= 0.20
                    ? TrialDecision.ACCEPT : TrialDecision.ROLLBACK;
        }
        // 路由阈值调整：实际有快速路由产出、且无明显失败，才长期保留。
        return trialMetrics.routingFastPaths() > 0 ? TrialDecision.ACCEPT : TrialDecision.ROLLBACK;
    }

    private static double value(RuntimeTuningService.Status status, RuntimeTuningService.Parameter parameter) {
        return status.effectiveValues().getOrDefault(parameter.key(), parameter.min());
    }

    private static String percentage(double value) {
        return Math.round(value * 100) + "%";
    }

    public enum TrialDecision { WAIT, ACCEPT, ROLLBACK }
    public record Proposal(RuntimeTuningService.Parameter parameter, double targetValue, String reason) { }
}
