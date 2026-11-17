package com.example.myhelper.orchestration;

import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.skill.ToolReliability;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 决定固化工具是否可以跳过模型；可靠度公式和验证范围仍由技能模块管理。 */
public final class ToolReliabilityGate {
    /** 用户配置的默认门限；严格大于此值才直接执行。 */
    public static final double DEFAULT_THRESHOLD = 0.85;
    /** 普通固化技能直执所需的累计成功次数。 */
    public static final long DEFAULT_MINIMUM_SUCCESSES = 5;
    /** 累计成功次数在 application.yml 中的配置键。 */
    public static final String MINIMUM_SUCCESSES_PROPERTY = "myhelper.orchestration.direct-tool-minimum-successes";
    /** 与 application.yml 对应的配置键。 */
    public static final String THRESHOLD_PROPERTY = "myhelper.orchestration.direct-tool-reliability-threshold";
    /** 技能模块查询；未装配时按未知评分交回模型。 */
    private final ToolReliability reliability;
    /** 本实例固定门限，不缓存工具评分或上次通过结论。 */
    private final double threshold;

    /** 普通固化技能直执所需的实际累计成功次数。 */
    private final long minimumSuccesses;

    public ToolReliabilityGate(ToolReliability reliability, double threshold) {
        this(reliability, threshold, DEFAULT_MINIMUM_SUCCESSES);
    }

    public ToolReliabilityGate(ToolReliability reliability, double threshold, long minimumSuccesses) {
        if (!Double.isFinite(threshold) || threshold < 0 || threshold > 1) {
            throw new IllegalArgumentException(THRESHOLD_PROPERTY + " 必须介于 0 和 1 之间");
        }
        if (minimumSuccesses < 0) {
            throw new IllegalArgumentException(MINIMUM_SUCCESSES_PROPERTY + " 必须是非负整数");
        }
        this.reliability = reliability;
        this.threshold = threshold;
        this.minimumSuccesses = minimumSuccesses;
    }

    /** 旧装配没有提供技能查询时，不把未知评分当成可靠。 */
    public static ToolReliabilityGate unavailable() {
        return new ToolReliabilityGate(null, DEFAULT_THRESHOLD);
    }

    /** 为规划提供同一工具实现的已批准范围统计。 */
    List<ToolReliability.PlanningReference> planningReferences(ToolRef ref) {
        return reliability == null ? List.of() : reliability.planningReferences(ref);
    }

    /** 使用最终工具参数查询，不能按工具名或语义相似度默认放行。 */
    public Decision evaluate(String toolName, Map<String, Object> arguments) {
        return evaluate(toolName, null, arguments);
    }

    /** 对本次实际执行的工具版本查分。 */
    public Decision evaluate(ToolRef ref, Map<String, Object> arguments) {
        return evaluate(ref.toolName(), ref, arguments);
    }

    /** 执行器没有提供版本绑定时回模型。 */
    public Decision evaluateUnavailable() {
        return new Decision(false, null, threshold, LlmTexts.TOOL_RELIABILITY_UNKNOWN);
    }

    private Decision evaluate(String toolName, ToolRef ref, Map<String, Object> arguments) {
        if (reliability == null) {
            return evaluateUnavailable();
        }
        Optional<ToolReliability.Assessment> assessment;
        try {
            assessment = ref == null ? reliability.assess(toolName, arguments) : reliability.assess(ref, arguments);
        } catch (RuntimeException failure) {
            // 此时还没有派发工具；查询失败只能请求模型判断，不能伪造可靠分数。
            return new Decision(false, null, threshold,
                    LlmTexts.TOOL_RELIABILITY_QUERY_FAILED + failure.getClass().getSimpleName());
        }
        if (assessment.isEmpty()) {
            return evaluateUnavailable();
        }
        ToolReliability.Assessment evidence = assessment.get();
        double score = evidence.score();
        if (score <= threshold) {
            return new Decision(false, score, threshold, LlmTexts.TOOL_RELIABILITY_BELOW_THRESHOLD);
        }
        if (!evidence.trustedAtomicTool() && evidence.successCount() < minimumSuccesses) {
            return new Decision(false, score, threshold,
                    LlmTexts.TOOL_RELIABILITY_TOO_FEW_SUCCESSES.formatted(evidence.successCount(), minimumSuccesses));
        }
        return new Decision(true, score, threshold, null);
    }

    public record Decision(
            /** 仅表示可跳过模型，不能代替权限、发布状态和执行后验收。 */
            boolean direct,
            /** 未查询到适用评分时为 null。 */
            Double score,
            /** 本次采用的配置门限。 */
            double threshold,
            /** 无法直接执行的原因。 */
            String reason) {
        /** 保存结构化判断证据，供执行图和下一轮模型读取。 */
        public Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("direct", direct);
            result.put("score", score);
            result.put("threshold", threshold);
            result.put("reason", reason);
            return TaskPlan.immutableMap(result);
        }
    }
}
