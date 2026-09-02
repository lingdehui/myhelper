package com.example.myhelper.memory.unit;

import java.util.List;
import java.util.Map;

/**
 * 统一对象模型（文档 15 v1.7 §3）。
 *
 * <p>所有计划/步骤/工具都用一个统一对象表示，靠 {@link UnitKind} 区分，
 * 格式完全一致。计划 = 大 Plan，步骤 = 子 Plan，工具 = 原子 Plan。</p>
 *
 * <p>{@code matchText} 是匹配语言字段，embedding 只对此字段生成向量，
 * 默认取 {@code goal}，区分度不足时由 AI 重写 {@code goal} 加区别词。</p>
 *
 * @param unitId              业务 UUID，对齐 Qdrant point id
 * @param unitKind            类型：TOOL / PLAN_STEP / MCP_TOOL
 * @param matchText           匹配语言字段（embedding 只对此字段生成向量，默认取 goal）
 * @param goal                说清要达到什么目的
 * @param description         补充描述
 * @param notes               注意点、失败教训提示、变体说明
 * @param params              入参签名（变量名→类型/默认值），非必填
 * @param outputSignature     输出签名（变量名→类型），声明本 Unit 产出哪些变量
 * @param toolName            unitKind 为 TOOL/MCP_TOOL 时指向的实际工具名
 * @param directExecutionStatus 语义命中后是否允许按 ContextUnit 条件直接执行
 * @param requiredContextIds  执行前必须满足的 REQUIREMENT ContextUnit
 * @param expectedContextIds  执行后必须验证的 EXPECTATION ContextUnit
 * @param successCount        成功次数
 * @param failureCount        失败次数（仅计 PLAN 原因）
 * @param stability           稳定度 = success/(success+failure)
 * @param qualityScore        经验质量分（0~1，综合可靠性、样本量、新鲜度和完整度）
 * @param failureCauses       失败原因引用列表（指向 FailureCause 的 causeId）
 * @param explorationRecords  探索模式操作记录
 * @param status              生命周期状态：ACTIVE / ARCHIVED
 */
public record Unit(
        String unitId,
        UnitKind unitKind,
        String matchText,
        String goal,
        String description,
        List<String> notes,
        Map<String, String> params,
        Map<String, String> outputSignature,
        String toolName,
        DirectExecutionStatus directExecutionStatus,
        List<String> requiredContextIds,
        List<String> expectedContextIds,
        int successCount,
        int failureCount,
        double stability,
        double qualityScore,
        List<String> failureCauses,
        List<ExplorationRecord> explorationRecords,
        UnitStatus status
) {
    public enum DirectExecutionStatus { DISABLED, LEARNING, ACTIVE, SUSPENDED }
    /** 生命周期状态 */
    public enum UnitStatus {
        ACTIVE,
        ARCHIVED
    }

    /**
     * 静态稳定度计算公式（统一版本，含零除保护）。
     * 所有调用方都应使用此方法而非自己计算。
     */
    public static double calcStability(int successCount, int failureCount) {
        int total = successCount + failureCount;
        return total == 0 ? 0.0 : (double) successCount / total;
    }

    /** Java 端实时计算稳定度（不依赖存储的 stability 字段） */
    public double computedStability() {
        return calcStability(successCount, failureCount);
    }

    /** 是否达到经验质量门槛，可作为跨轮次复用计划。 */
    public boolean isReusable(double minQualityScore) {
        return status == UnitStatus.ACTIVE && qualityScore >= minQualityScore;
    }

    public boolean allowsDirectExecution() {
        return status == UnitStatus.ACTIVE && directExecutionStatus == DirectExecutionStatus.ACTIVE;
    }

    /**
     * 生成 matchText：默认取 goal；goal 为空时退回 description。
     * 区分度不足时由 AI 重写 goal（而非拼接 description/notes）。
     */
    public static String buildMatchText(String goal, String description) {
        if (goal != null && !goal.isBlank()) return goal;
        if (description != null && !description.isBlank()) return description;
        return "";
    }
}
