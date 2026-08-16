package com.example.myhelper.memory.unit;

import java.util.List;

/**
 * 失败原因独立对象（文档 15 v1.7 §5）。
 *
 * <p>网络、环境、原因、入参、分析、归类全部结构化。
 * 归类为 {@link FailureCategory#PLAN} 的失败累计达阈值即触发禁用/删除，
 * {@link FailureCategory#ENVIRONMENT} 不惩罚计划，只记录。</p>
 */
public record FailureCause(
        String causeId,
        String network,
        String environment,
        String reason,
        String inputArgs,
        String analysis,
        FailureCategory category,
        long timestamp,
        List<String> suggestedUnitIds
) {
    /** 失败归类 */
    public enum FailureCategory {
        /** 计划本身有缺陷（步骤错、顺序错、工具错、参数错）→ 惩罚计划 */
        PLAN,
        /** 环境问题（网络、窗口、第三方异常）→ 不惩罚计划，仅记录 */
        ENVIRONMENT
    }
}
