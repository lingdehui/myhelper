package com.example.myhelper.exploration;

/**
 * 记忆清理候选：记录待删除 Unit 的价值评分详情。
 *
 * @param unitId        Unit UUID
 * @param valueScore    综合价值评分 [0.0, 1.0]，越低越优先清理
 * @param title         Unit 简述（goal 或成功经验）
 * @param sourceType    来源类型（user_task / autonomous-exploration / failure-pattern）
 * @param ageDays       已存在天数
 * @param successCount  成功次数
 * @param unitKind      单元类型
 */
public record CleanupCandidate(
        String unitId,
        double valueScore,
        String title,
        String sourceType,
        int ageDays,
        int successCount,
        String unitKind
) implements Comparable<CleanupCandidate> {

    /** 按 valueScore 升序排列（低分优先清理） */
    @Override
    public int compareTo(CleanupCandidate o) {
        return Double.compare(this.valueScore, o.valueScore);
    }
}
