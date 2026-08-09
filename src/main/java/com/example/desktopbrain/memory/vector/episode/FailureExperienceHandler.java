package com.example.desktopbrain.memory.vector.episode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * 失败经验处理器 —— 与"成功经验"平级的失败事件沉淀系统。
 *
 * <p>核心逻辑：</p>
 * <ol>
 *   <li><b>滑动窗口计数</b>：同一类型失败在时间窗口内达到阈值 → 生成 FailurePattern</li>
 *   <li><b>模式沉淀</b>：FailurePattern 可作为后续 AI 规划的负面提示，避免重复踩坑</li>
 *   <li><b>统一入口</b>：所有执行失败（缓存命中 / 新规划 / 重规划）都走此处理器</li>
 * </ol>
 *
 * <p>与 RefactService 的关系：前者负责单次归因（计划问题 vs 环境问题），
 * 本处理器负责跨时间的模式聚合（"这类操作最近频繁失败"）。</p>
 */
@Component
public class FailureExperienceHandler {

    private static final Logger log = LoggerFactory.getLogger(FailureExperienceHandler.class);

    /** 失败类型 → 时间戳队列（滑动窗口） */
    private final ConcurrentHashMap<String, LinkedBlockingDeque<Long>> slidingWindows = new ConcurrentHashMap<>();

    /** 已生成过 pattern 的类型集合（同类型不重复生成） */
    private final Set<String> patternCache = ConcurrentHashMap.newKeySet();

    /** 持久化出口（Qdrant + Neo4j） */
    private final EpisodeCacheService episodeCacheService;

    // ===== 可配置参数（后续可移入 application.yml） =====
    private static final int WINDOW_SIZE_MS = 60 * 60 * 1000;   // 1 小时滑动窗口
    private static final int PATTERN_THRESHOLD = 3;               // 3 次失败 → 生成模式

    public FailureExperienceHandler(EpisodeCacheService episodeCacheService) {
        this.episodeCacheService = episodeCacheService;
    }

    // ========================================================================
    // Record
    // ========================================================================

    /**
     * 失败模式 —— 跨多次失败聚合出的高频失败规律。
     *
     * @param type        失败类型（归一化后的失败教训摘要）
     * @param description 人类可读描述
     * @param mitigation  缓解建议
     * @param count       窗口内累计失败次数
     * @param detectedAt  检测时间戳
     */
    public record FailurePattern(
            String type,
            String description,
            String mitigation,
            int count,
            long detectedAt
    ) {}

    // ========================================================================
    // Public API
    // ========================================================================

    /**
     * 处理一次失败事件。
     *
     * <p>调用时机：每次执行失败并完成 AI 归因后（任何路径）。</p>
     *
     * @param userInput       用户原话
     * @param failureLesson   AI 归因的失败教训（可为 null）
     * @param isPlanIssue     是否计划逻辑错误
     * @param hasSalvaged     是否已提取了可复用步骤链
     * @param selectedToolNames 本次执行选用的工具名列表（用于关联分析）
     * @return 如果本次导致生成 FailurePattern，返回它；否则 Optional.empty()
     */
    public Optional<FailurePattern> handle(String userInput, String failureLesson,
                                            boolean isPlanIssue, boolean hasSalvaged,
                                            List<String> selectedToolNames) {
        if (failureLesson == null || failureLesson.isBlank()) {
            log.info("📊 失败计数: 跳过（无教训内容）");
            return Optional.empty();
        }

        String failureType = normalizeType(failureLesson);
        long now = System.currentTimeMillis();

        LinkedBlockingDeque<Long> window = slidingWindows.computeIfAbsent(
                failureType, k -> new LinkedBlockingDeque<>()
        );

        // 入队当前事件时间戳
        window.addLast(now);

        // 驱逐过期条目
        while (!window.isEmpty() && now - window.peekFirst() > WINDOW_SIZE_MS) {
            window.pollFirst();
        }

        int count = window.size();

        // 构建日志语境
        String context = isPlanIssue ? "计划问题" : "环境问题";
        String salvagedTag = hasSalvaged ? " | 已提取 ATOMIC" : "";
        log.info("📊 失败计数 [{}]: {}/{}（{}{}）", failureType, count, PATTERN_THRESHOLD, context, salvagedTag);

        // 达到阈值 → 生成 FailurePattern
        if (count >= PATTERN_THRESHOLD && !patternCache.contains(failureType)) {
            patternCache.add(failureType);
            String mitigation = buildMitigation(failureType, isPlanIssue);
            FailurePattern pattern = new FailurePattern(
                    failureType,
                    "最近 " + (WINDOW_SIZE_MS / 60000) + " 分钟内「" + failureType + "」失败 " + count + " 次。",
                    mitigation,
                    count,
                    now
            );

            window.clear(); // 重置窗口，避免短期重复触发
            log.info("⚠️ 生成失败模式: {}", pattern.description());

            // 持久化到 Qdrant + Neo4j（异步，不阻塞主流程）
            episodeCacheService.saveFailurePattern(pattern, selectedToolNames);

            return Optional.of(pattern);
        }

        // 如果窗口内计数清零（都过期了），从 patternCache 移除，允许后续再次触发
        if (count == 0) {
            patternCache.remove(failureType);
        }

        return Optional.empty();
    }

    /**
     * 获取当前所有活跃窗口的计数（供外部查看/调试）。
     */
    public Map<String, Integer> getActiveCounts() {
        long now = System.currentTimeMillis();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var entry : slidingWindows.entrySet()) {
            LinkedBlockingDeque<Long> window = entry.getValue();
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_SIZE_MS) {
                window.pollFirst();
            }
            if (!window.isEmpty()) {
                result.put(entry.getKey(), window.size());
            }
        }
        return result;
    }

    /** 清空所有状态（用于测试/重置）。 */
    public void reset() {
        slidingWindows.clear();
        patternCache.clear();
    }

    // ========================================================================
    // Private Helpers
    // ========================================================================

    /**
     * 将 AI 生成的失败教训归一化为类型标签。
     *
     * <p>保留核心语义，去除标点和冗余。如：
     * "目标网页 URL 不存在" → "目标网页URL不存在"</p>
     */
    private String normalizeType(String lesson) {
        if (lesson == null || lesson.isBlank()) return "未知失败";
        String simplified = lesson.replaceAll("[，。！？；：、]", "")
                .replaceAll("\\s+", "").trim();
        if (simplified.length() > 30) {
            simplified = simplified.substring(0, 30);
        }
        return simplified;
    }

    /** 根据失败类型生成缓解建议。 */
    private String buildMitigation(String failureType, boolean isPlanIssue) {
        if (isPlanIssue) {
            return "该类型的计划逻辑存在问题，建议：1) 优化计划步骤顺序；"
                    + "2) 检查工具参数；3) 失败 " + PATTERN_THRESHOLD + " 次后旧计划将被淘汰。";
        }
        return "该类型操作频繁因环境问题失败，建议：1) 检查网络/设备状态；"
                + "2) 确认目标资源是否可用；3) 考虑增加重试逻辑。";
    }
}
