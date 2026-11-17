package com.example.myhelper.skill;

import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import com.example.myhelper.world.model.ExecutionStatistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/** 供循环在直接调用工具前查询已发布技能的可靠度。 */
public final class ToolReliability {
    /** 送入规划消息的批准范围数量上限。 */
    private static final int MAX_PLANNING_RANGES = 8;
    /** 读取当前发布修订、参数范围及统计的权威查询。 */
    private final Lookup lookup;

    public ToolReliability(Lookup lookup) {
        this.lookup = Objects.requireNonNull(lookup);
    }

    /** 查无适用范围或没有有效样本及先验时返回空。调用方决定阈值。 */
    public OptionalDouble score(String toolName, Map<String, Object> arguments) {
        Optional<Assessment> assessment = assess(toolName, arguments);
        if (assessment.isEmpty()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(assessment.get().score());
    }

    /** 一次查询返回分数、累计成功次数和当前版本的可信资格。 */
    public Optional<Assessment> assess(String toolName, Map<String, Object> arguments) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        Optional<Sample> match = lookup.findInVerifiedRange(toolName,
                TaskPlan.immutableMap(Objects.requireNonNull(arguments, "arguments")));
        return assess(match);
    }

    /** 直执使用已解析的执行身份，避免按名字重新挑版本。 */
    public Optional<Assessment> assess(ToolRef ref, Map<String, Object> arguments) {
        return assess(lookup.findInVerifiedRange(Objects.requireNonNull(ref, "ref"),
                TaskPlan.immutableMap(Objects.requireNonNull(arguments, "arguments"))));
    }

    /** 读取当前工具版本在已批准范围内的历史统计，供模型规划参考。 */
    public List<PlanningReference> planningReferences(ToolRef ref) {
        List<PlanningReference> references = new ArrayList<>();
        for (var entry : lookup.planningSamples(Objects.requireNonNull(ref, "ref")).entrySet()) {
            Sample sample = entry.getValue();
            ExecutionStatistics statistics = sample.statistics();
            if (statistics.successCount() == 0 && statistics.failureCount() == sample.externalFailures()) {
                continue;
            }
            Assessment assessed = assess(sample).orElseThrow();
            references.add(new PlanningReference(entry.getKey(), assessed.score(), assessed.successCount(),
                    assessed.trustedAtomicTool()));
            if (references.size() == MAX_PLANNING_RANGES) {
                break;
            }
        }
        return List.copyOf(references);
    }

    private Optional<Assessment> assess(Optional<Sample> match) {
        if (match.isEmpty()) {
            return Optional.empty();
        }
        return assess(match.get());
    }

    private Optional<Assessment> assess(Sample sample) {
        ExecutionStatistics statistics = sample.statistics();
        long methodFailures = statistics.failureCount() - sample.externalFailures();
        Prior prior = sample.prior();
        double weight = prior.weight();
        double total = weight + statistics.successCount() + methodFailures;
        if (total == 0) {
            return Optional.empty();
        }
        double score = (prior.successRate() * weight + statistics.successCount()) / total;
        return Optional.of(new Assessment(score, statistics.successCount(), sample.trustedAtomicTool()));
    }

    public record Assessment(
            /** 估计成功率。 */
            double score,
            /** 已确认的实际累计成功次数。 */
            long successCount,
            /** 当前实现版本已登记为可信原子工具。 */
            boolean trustedAtomicTool) {
    }

    public record PlanningReference(
            /** 历史统计适用的批准范围编号。 */
            String rangeId,
            /** 该范围内按现有公式计算的估计成功率。 */
            double score,
            /** 该范围内已确认的实际成功次数。 */
            long successCount,
            /** 当前实现版本已登记为可信原子工具。 */
            boolean trustedAtomicTool) {
    }

    public interface Lookup {
        /**
         * 按精确工具名及完整入参查询当前有效修订。使用最近一次已验证范围，
         * 范围交大模型检查所有参数和组合约束；模型不可用时沿用已保存的实际参数样本，
         * 尚未执行的新请求不能作为实际样本写入。执行后的实际参数保存不能依赖模型可用。
         * 先验必须来自同一工具实现版本和适用范围。
         * 可信标记由目录按来源提供：非大模型生成、非固化组合且非本项目开发的工具可信。
         * 无适用修订或无法唯一确定时返回空。
         */
        Optional<Sample> findInVerifiedRange(String toolName, Map<String, Object> arguments);

        /** 只有支持按实际版本读取的查询才能用于编排直执。 */
        default Optional<Sample> findInVerifiedRange(ToolRef ref, Map<String, Object> arguments) {
            return Optional.empty();
        }

        /** 按批准顺序返回当前实现各范围的统计，未提供摘要的实现返回空。 */
        default Map<String, Sample> planningSamples(ToolRef ref) {
            return Map.of();
        }
    }

    public record Sample(
            /** 命中修订的成功、失败及归因统计。 */
            ExecutionStatistics statistics,
            /** 确认非方法本身导致的失败执行次数，按执行身份去重。 */
            long externalFailures,
            /** 当前工具版本和适用范围的初始估计，零权重表示没有先验。 */
            Prior prior,
            /** 目录按三个来源排除项确定的可信资格，仅免除累计成功次数条件。 */
            boolean trustedAtomicTool) {
        public Sample(ExecutionStatistics statistics, long externalFailures) {
            this(statistics, externalFailures, new Prior(0, 0));
        }

        public Sample(ExecutionStatistics statistics, long externalFailures, Prior prior) {
            this(statistics, externalFailures, prior, false);
        }

        public Sample {
            Objects.requireNonNull(statistics);
            Objects.requireNonNull(prior);
            if (externalFailures < 0 || externalFailures > statistics.failureCount()) {
                throw new IllegalArgumentException("外部失败次数必须介于 0 和总失败次数之间");
            }
        }
    }


    public record Prior(
            /** 工具目录给出的初始可靠度，取 0 到 1。 */
            double successRate,
            /** 等效执行次数，零表示无先验。 */
            long weight) {
        public Prior {
            if (!Double.isFinite(successRate) || successRate < 0 || successRate > 1) {
                throw new IllegalArgumentException("初始可靠度必须介于 0 和 1 之间");
            }
            if (weight < 0) {
                throw new IllegalArgumentException("初始权重不能为负数");
            }
        }
    }
}
