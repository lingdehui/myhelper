package com.example.myhelper.optimization;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/** 将元优化器的状态和可观测反馈暴露为显式工具，便于用户审计而非黑箱调参。 */
@Component
public class MetaOptimizationTool {

    private final RuntimeMetricsService metrics;
    private final RuntimeTuningService tuning;
    private final MetaOptimizationService optimizer;
    private final ConfigCatalogService catalog;
    private final ConfigExperimentService experiments;
    private final ReflectionHotConfigApplier hotConfigApplier;

    public MetaOptimizationTool(RuntimeMetricsService metrics, RuntimeTuningService tuning,
                                MetaOptimizationService optimizer, ConfigCatalogService catalog,
                                ConfigExperimentService experiments, ReflectionHotConfigApplier hotConfigApplier) {
        this.metrics = metrics;
        this.tuning = tuning;
        this.optimizer = optimizer;
        this.catalog = catalog;
        this.experiments = experiments;
        this.hotConfigApplier = hotConfigApplier;
    }

    @Tool(description = "查看元自我优化器的运行指标、当前生效参数、待验证试验和最近审计记录。"
            + "只返回聚合数字，不返回用户内容或工具参数。")
    public String getMetaOptimizationStatus() {
        RuntimeMetricsService.Snapshot snapshot = metrics.snapshot();
        RuntimeTuningService.Status status = tuning.status();
        return "【元优化器状态】\n"
                + "规划请求=" + snapshot.planningRequests() + "，缓存命中=" + percent(snapshot.cacheHitRate())
                + "，平均规划=" + Math.round(snapshot.averagePlanningMs()) + "ms\n"
                + "工具成功=" + percent(snapshot.toolSuccessRate()) + "，快速路由=" + percent(snapshot.routingFastPathRate()) + "\n"
                + "唤醒触发=" + snapshot.wakeTriggers() + "，无命令超时=" + percent(snapshot.wakeFalsePositiveRate())
                + "，用户报告漏唤醒=" + snapshot.wakeMissReports() + "\n"
                + "配置目录=" + catalog.list().size() + " 个参数，其中可实验="
                + catalog.list().stream().filter(parameter -> parameter.risk() == ConfigCatalogService.Risk.TUNABLE).count() + " 个\n"
                + "生效参数=" + status.effectiveValues() + "\n"
                + "待验证试验=" + (status.pendingTrial() == null ? "无" : status.pendingTrial()) + "\n"
                + "最近审计=\n" + formatAudit(status.audit());
    }

    @Tool(description = "立即运行一次元自我优化评估。它只会验证已有试验，或在样本充分时应用一个白名单内的小步参数试验。")
    public String runMetaOptimization() {
        MetaOptimizationService.OptimizationResult result = optimizer.optimizeOnce();
        return "【元优化器】" + result.state() + "：" + result.message();
    }

    @Tool(description = "报告一次确实发生的漏唤醒：你已说出唤醒词但系统没有进入对话。"
            + "这是允许元优化器放宽唤醒词匹配的必要人工标签；没有该标签时它不会猜测漏唤醒。")
    public String reportWakeMiss(@ToolParam(description = "可选简短备注；不记录音频或完整对话") String note) {
        metrics.recordWakeMissReported();
        return "✅ 已记录一次漏唤醒反馈。元优化器会在误触率低且样本充分时，最多放宽一档匹配容忍度。";
    }

    @Tool(description = "列出元优化器自动发现的 YAML 配置参数及风险等级。所有配置叶子都会出现；"
            + "只有 TUNABLE 数值参数可以进入自主实验，HOT 表示当前进程可热应用，RESTART 表示只可生成实验副本。")
    public String listMetaConfigParameters(@ToolParam(description = "最多返回多少项，建议 20-100") int limit) {
        int max = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        StringBuilder output = new StringBuilder("【自动发现配置目录】\n");
        for (ConfigCatalogService.ConfigParameter parameter : catalog.list().stream().limit(max).toList()) {
            String mode = parameter.risk() == ConfigCatalogService.Risk.TUNABLE
                    ? (isBuiltInHotPath(parameter.path()) || hotConfigApplier.supports(parameter.path()) ? "HOT" : "RESTART")
                    : "PROTECTED";
            output.append("- ").append(parameter.path()).append(" = ").append(parameter.displayValue())
                    .append(" [").append(parameter.type()).append(" | ").append(parameter.risk())
                    .append(" | ").append(mode).append("] <").append(parameter.source()).append(">\n");
        }
        if (catalog.list().size() > max) output.append("…其余 ").append(catalog.list().size() - max).append(" 项可提高 limit 查看。\n");
        return output.toString();
    }

    @Tool(description = "为自动发现的一个数值配置创建独立 YAML 实验副本，并在参数支持热应用时启动可回滚试验。"
            + "只能用于目录中标记为 TUNABLE 的参数；密钥、连接、路径、权限和开关不会被自主修改。")
    public String experimentMetaConfig(
            @ToolParam(description = "完整配置路径，例如 myhelper.experience-quality.min-reuse-score") String propertyPath,
            @ToolParam(description = "候选数值") double candidateValue,
            @ToolParam(description = "此次实验的指标依据和预期效果") String reason) {
        try {
            ConfigExperimentService.Experiment experiment = experiments.stage(propertyPath, candidateValue, reason);
            boolean applied = tuning.applyDiscovered(experiment, metrics.snapshot());
            return applied
                    ? "✅ 已创建实验副本并热应用：" + experiment.file() + "；后续将自动验证或回滚。"
                    : "🧪 实验副本已创建：" + experiment.file() + "。该参数当前没有安全的热应用目标，保留副本供重启验证。";
        } catch (RuntimeException e) {
            return "❌ 无法创建配置实验：" + e.getMessage();
        }
    }

    private String formatAudit(List<RuntimeTuningService.AuditRecord> audit) {
        if (audit == null || audit.isEmpty()) return "暂无";
        StringBuilder lines = new StringBuilder();
        audit.stream().skip(Math.max(0, audit.size() - 5)).forEach(record -> lines
                .append("- ").append(record.outcome()).append(" ").append(record.parameterPath())
                .append(": ").append(record.oldValue()).append(" → ").append(record.newValue())
                .append("（").append(record.reason()).append("）\n"));
        return lines.toString();
    }

    private static String percent(double value) { return Math.round(value * 100) + "%"; }

    private static boolean isBuiltInHotPath(String path) {
        return RuntimeTuningService.Parameter.fromKey(path).isPresent();
    }
}
