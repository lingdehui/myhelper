package com.example.desktopbrain.exploration;

/**
 * 记忆清理候选：记录待删除 Episode 的价值评分详情。
 *
 * @param episodeId   Episode UUID
 * @param valueScore  综合价值评分 [0.0, 1.0]，越低越优先清理
 * @param title       Episode 简述（userInput 或 successLesson）
 * @param sourceType  来源类型（user_task / autonomous_exploration / failure-pattern）
 * @param ageDays     已存在天数
 * @param successCount 成功次数
 * @param unitType    单元类型
 */
public record CleanupCandidate(
        String episodeId,
        double valueScore,
        String title,
        String sourceType,
        int ageDays,
        int successCount,
        String unitType
) implements Comparable<CleanupCandidate> {

    /** 按 valueScore 升序排列（低分优先清理） */
    @Override
    public int compareTo(CleanupCandidate o) {
        return Double.compare(this.valueScore, o.valueScore);
    }
}
