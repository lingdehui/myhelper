package com.example.myhelper.optimization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 配置级“元自我优化”Agent 的调度与闭环控制器。
 *
 * <p>一次运行只做一件事：验证已有试验，或在没有试验时提出并应用一个候选参数。
 * 这样指标归因清楚，且任意调参都有可审计、可回滚的前后状态。</p>
 */
@Service
public class MetaOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(MetaOptimizationService.class);

    private final RuntimeMetricsService metrics;
    private final RuntimeTuningService tuning;
    private final ConfigExperimentService experiments;

    @Value("${myhelper.meta-optimizer.enabled:true}")
    private boolean enabled;
    @Value("${myhelper.meta-optimizer.min-planning-samples:50}")
    private int minPlanningSamples;
    @Value("${myhelper.meta-optimizer.min-wake-samples:20}")
    private int minWakeSamples;

    public MetaOptimizationService(RuntimeMetricsService metrics, RuntimeTuningService tuning,
                                   ConfigExperimentService experiments) {
        this.metrics = metrics;
        this.tuning = tuning;
        this.experiments = experiments;
    }

    @Scheduled(fixedDelayString = "${myhelper.meta-optimizer.interval-ms:3600000}",
            initialDelayString = "${myhelper.meta-optimizer.initial-delay-ms:300000}")
    public void scheduledOptimize() {
        if (enabled) optimizeOnce();
    }

    /** 供工具和测试触发一次同步评估。 */
    public synchronized OptimizationResult optimizeOnce() {
        RuntimeMetricsService.Snapshot current = metrics.snapshot();
        var pending = tuning.pendingTrial();
        if (pending.isPresent()) {
            RuntimeTuningService.Trial trial = pending.get();
            RuntimeMetricsService.Snapshot trialMetrics = current.deltaSince(trial.baseline());
            MetaOptimizationPolicy.TrialDecision decision = MetaOptimizationPolicy.evaluate(
                    trial, trialMetrics, minPlanningSamples, minWakeSamples);
            if (decision == MetaOptimizationPolicy.TrialDecision.WAIT) {
                return OptimizationResult.waiting("试验仍在收集验证样本。", trial);
            }
            if (decision == MetaOptimizationPolicy.TrialDecision.ACCEPT) {
                tuning.acceptPending("试验期指标达标：" + summarize(trialMetrics));
                return OptimizationResult.accepted("试验效果达标，保留新参数。", trial);
            }
            tuning.rollbackPending("试验期指标未达标：" + summarize(trialMetrics));
            return OptimizationResult.rolledBack("试验指标退化，已恢复旧参数。", trial);
        }

        return MetaOptimizationPolicy.diagnose(current, tuning.status(), minPlanningSamples, minWakeSamples)
                .map(proposal -> startSandboxedTrial(proposal, current))
                .orElseGet(() -> OptimizationResult.noAction("当前指标没有满足安全调参条件。"));
    }

    private OptimizationResult startSandboxedTrial(MetaOptimizationPolicy.Proposal proposal,
                                                    RuntimeMetricsService.Snapshot baseline) {
        try {
            ConfigExperimentService.Experiment experiment = experiments.stage(proposal.parameter().key(),
                    proposal.targetValue(), proposal.reason());
            return tuning.applyDiscovered(experiment, baseline)
                    ? OptimizationResult.applied("已生成实验配置并开始小步试验：" + proposal.reason(), experiment)
                    : OptimizationResult.waiting("已有试验或候选参数无法热应用。", experiment);
        } catch (RuntimeException e) {
            log.warn("元优化器无法建立配置实验: {}", e.getMessage());
            return OptimizationResult.noAction("候选配置未通过实验校验：" + e.getMessage());
        }
    }

    private String summarize(RuntimeMetricsService.Snapshot metrics) {
        return "规划=" + metrics.planningRequests() + "，缓存命中=" + Math.round(metrics.cacheHitRate() * 100)
                + "% ，工具成功=" + Math.round(metrics.toolSuccessRate() * 100) + "%";
    }

    public record OptimizationResult(String state, String message, Object detail) {
        static OptimizationResult applied(String message, Object detail) { return new OptimizationResult("APPLIED", message, detail); }
        static OptimizationResult waiting(String message, Object detail) { return new OptimizationResult("WAITING", message, detail); }
        static OptimizationResult accepted(String message, Object detail) { return new OptimizationResult("ACCEPTED", message, detail); }
        static OptimizationResult rolledBack(String message, Object detail) { return new OptimizationResult("ROLLED_BACK", message, detail); }
        static OptimizationResult noAction(String message) { return new OptimizationResult("NO_ACTION", message, null); }
    }
}
