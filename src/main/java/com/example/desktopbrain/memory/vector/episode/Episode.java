package com.example.desktopbrain.memory.vector.episode;

import java.util.List;
import java.util.Map;

/**
 * 一次完整的任务执行轨迹（ExpeL/MUSE 经验学习的最小单元）。
 *
 * <p>借鉴 ExpeL（经验学习）和 MUSE（自演化 Agent）的思想：把每次成功执行的
 * 完整轨迹持久化到 Qdrant，下次相似请求来时向量检索复用其方案，
 * 失败时累计失败计数达阈值自动归档。</p>
 *
 * <h3>分形结构（步骤=小计划）</h3>
 * <p>用户核心设计思想："步骤=小计划→统一抽象→分形结构"。</p>
 * <ul>
 *   <li>{@link UnitType#COMPOSITE}：复合计划，包含有序的步骤（toolCalls 脚本），
 *       可变量化复用（如"微信发消息"，变量 contact/message）</li>
 *   <li>{@link UnitType#ATOMIC}：原子步骤，从 composite 中提取的通用小计划
 *       （如"输入文字"，被≥3个 composite 引用时自动标记 isGeneric=true）</li>
 * </ul>
 *
 * <h3>变量化复用</h3>
 * <p>存 episode 时 AI 把 toolCalls 的 args 模板化（"张三"→"$contact"），
 * 并提取 signature（变量名列表）。执行时 AI 提取变量值，
 * PlanExecutor 做变量替换后直接调工具，实现"传入变量就行了"。</p>
 *
 * <p><b>stability 字段说明</b>：Qdrant payload filter 不支持表达式计算，
 * 所以 stability 作为冗余字段存储，每次更新 successCount/failureCount 时
 * 同步重算写入。Java 端校验时用 {@link #computedStability()} 实时计算。</p>
 *
 * @param id                  UUID 字符串，Qdrant point id
 * @param userInput           用户原话（用于重新 embed 和展示）
 * @param selectedToolNames   选中的工具名列表
 * @param missingDescriptions 缺失的工具描述
 * @param toolCalls           实际工具调用轨迹（COMPOSITE 的执行脚本）
 * @param aiResponse          AI 最终回复（截断到 500 字符）
 * @param successLesson       成功经验（AI 反思总结，30字内，下次复用时作为 hint）
 * @param failureLesson       失败教训（AI 反思总结，30字内，下次复用时作为警示）
 * @param signature           变量签名：变量名 → 来源（"user_input" 表示从用户输入提取）
 * @param unitType            单元类型：COMPOSITE（完整计划）或 ATOMIC（通用步骤）
 * @param isGeneric           是否是通用步骤（被≥3个 composite 引用时自动标记）
 * @param parentIds           被哪些大 episode 引用（用于通用步骤提取和反向查找）
 * @param successCount        累计成功次数
 * @param failureCount        累计失败次数
 * @param archived            是否归档
 * @param timestamp           创建时间戳（毫秒）
 * @param stability           冗余字段：successCount/(success+failure)，用于 Qdrant filter
 * @param status              生命周期状态：DRAFT（执行中）/ACTIVE（成功可复用）/FAILED（失败但保留步骤）
 * @param canScript           是否可脚本化（successCount≥5 且 stability>0.9 时为 true，PlanExecutor 直接回放）
 * @param failedStepIndex     失败步位置（-1=未失败/成功；≥0=第 N 步失败，用于分段继续）
 * @param explorationType      探索类型（null=非探索，AUTONOMOUS=自主探索，MANUAL=手动触发）
 * @param explorationSummary   探索摘要（探索任务完成后填入）
 */
public record Episode(
        String id,
        String userInput,
        List<String> selectedToolNames,
        List<String> missingDescriptions,
        List<ToolCallLog> toolCalls,
        String aiResponse,
        String successLesson,
        String failureLesson,
        Map<String, String> signature,
        UnitType unitType,
        boolean isGeneric,
        List<String> parentIds,
        int successCount,
        int failureCount,
        boolean archived,
        long timestamp,
        double stability,
        EpisodeStatus status,
        boolean canScript,
        int failedStepIndex,
        ExplorationType explorationType,
        String explorationSummary
) {
    /** 单元类型 */
    public enum UnitType {
        /** 复合计划：包含有序步骤（toolCalls 脚本），可变量化复用 */
        COMPOSITE,
        /** 原子步骤：从 composite 提取的通用小计划，可被多个 composite 引用 */
        ATOMIC
    }

    /** 生命周期状态（决策4：立即创建draft，成功转active，失败也保存步骤） */
    public enum EpisodeStatus {
        /** 草稿：执行中创建，尚未确认结果 */
        DRAFT,
        /** 探索进行中 */
        IN_PROGRESS,
        /** 活跃：执行成功，可被检索复用 */
        ACTIVE,
        /** 失败：执行失败但保留已执行步骤，供后续分析 */
        FAILED
    }

    /** 探索类型（null/非探索，AUTONOMOUS/自主探索，MANUAL/手动触发） */
    public enum ExplorationType {
        AUTONOMOUS,
        MANUAL
    }

    /**
     * Java 端实时计算稳定度（不依赖存储的 stability 字段）。
     */
    public double computedStability() {
        return calcStability(successCount, failureCount);
    }

    /**
     * 静态稳定度计算公式（统一版本，含零除保护）。
     * 所有调用方都应使用此方法而非自己计算。
     */
    public static double calcStability(int successCount, int failureCount) {
        int total = successCount + failureCount;
        return total == 0 ? 0.0 : (double) successCount / total;
    }

    /** 是否是复合计划（含可执行脚本） */
    public boolean isComposite() {
        return unitType == UnitType.COMPOSITE;
    }

    /** 是否可脚本化执行（稳定度高时跳过 AI，直接回放 toolCalls） */
    public boolean isScriptable() {
        return canScript && status == EpisodeStatus.ACTIVE && toolCalls != null && !toolCalls.isEmpty();
    }

    /** 从 Qdrant point 反序列化 Episode（静态共享，避免分散复制） */
    @SuppressWarnings("unchecked")
    public static Episode fromQdrantPoint(Map<String, Object> point, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        try {
            Map<String, Object> payload = (Map<String, Object>) point.get("payload");
            if (payload == null) return null;
            String id = String.valueOf(point.get("id"));
            payload.put("id", id);
            payload.putIfAbsent("successLesson", null);
            payload.putIfAbsent("failureLesson", null);
            payload.putIfAbsent("signature", java.util.Map.of());
            payload.putIfAbsent("unitType", UnitType.COMPOSITE.name());
            payload.putIfAbsent("isGeneric", false);
            payload.putIfAbsent("parentIds", java.util.List.of());
            payload.putIfAbsent("status", EpisodeStatus.ACTIVE.name());
            payload.putIfAbsent("canScript", false);
            payload.putIfAbsent("failedStepIndex", -1);
            payload.putIfAbsent("explorationType", null);
            payload.putIfAbsent("explorationSummary", null);
            return objectMapper.convertValue(payload, Episode.class);
        } catch (Exception e) {
            System.err.println("❌ Episode 反序列化失败: " + e.getMessage());
            return null;
        }
    }
}
