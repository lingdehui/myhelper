package com.example.myhelper.world.model;

import java.util.List;

/**
 * 当前统计范围内的一项失败归因汇总。
 * 原因定义可以复用，但 occurrenceCount 只属于持有它的那份 ExecutionStatistics，
 * 不自动在不同链路、位置或版本之间共享次数。
 */
public record FailureCause(
        /** 稳定的失败原因编号，用于识别或汇总同类原因；不使用向量相似度代替精确编号。 */
        String causeId,

        /** 归因类型；null 保存为 UNKNOWN，不自动推断为计划或环境问题。 */
        FailureCategory category,

        /** 失败原因或现象的明确描述，不能为空。 */
        String reason,

        /** 对原因的分析说明；未分析时为 null，不将说明文本自动视为已验证结论。 */
        String analysis,

        /** 本统计范围内记录到该原因的次数，不能为负数，不自动等同于总失败次数。 */
        long occurrenceCount,

        /** 支持归因的证据引用；需要复现时固定目标 revision，空列表表示未记录依据。 */
        List<EntityRef> evidence) {
    public FailureCause {
        if (causeId == null || causeId.isBlank()) {
            throw new IllegalArgumentException("causeId 不能为空");
        }
        category = category == null ? FailureCategory.UNKNOWN : category;
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        if (occurrenceCount < 0) {
            throw new IllegalArgumentException("归因次数不能为负数");
        }
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
