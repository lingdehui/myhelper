package com.example.myhelper.exploration;

import com.example.myhelper.config.MyHelperProperties;
import com.example.myhelper.memory.vector.episode.Episode;
import com.example.myhelper.memory.vector.QdrantDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 记忆维护服务：定期检测占用率，基于多维价值评分清理低价值数据。
 *
 * <h3>清理策略（v2 价值评分版）</h3>
 * <ol>
 *   <li>计算 Qdrant 占用率，≥threshold 触发清理</li>
 *   <li>滚动获取所有 Episode（含 payload），跳过保护期内 + 白名单条目</li>
 *   <li>逐条计算 valueScore = w1×recency + w2×usage + w3×userProduced + w4×dependency</li>
 *   <li>按分数升序取前 N 个删除候选（N = (占用率-target) × MAX_POINTS）</li>
 *   <li>批量删除 Qdrant points</li>
 * </ol>
 *
 * <p>每天凌晨 4:00 执行，cron 可通过 myhelper.memory-maintenance.cron 配置。</p>
 *
 * <h3>白名单机制</h3>
 * <p>通过 {@link #protectMemory(String)} / {@link #protectMemory(String, int)} 可将指定 Episode
 * 标记为受保护，清理时自动跳过。白名单存于内存 {@link ConcurrentHashMap}，重启后丢失。</p>
 */
@Service
public class MemoryMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceService.class);

    private final WebClient qdrant;
    private final MyHelperProperties props;
    private final ObjectMapper objectMapper;

    /** Qdrant 集合最大点数（估算上限，超出视为 100% 占用） */
    private static final int MAX_POINTS = 100_000;

    /** 价值评分下限：低于此值加入删除候选 */
    private static final double MIN_VALUE_SCORE = 0.3;

    /** 白名单：key=Episode UUID, value=保护过期时间戳(ms, 0=永久) */
    private final ConcurrentHashMap<String, Long> protectedIds = new ConcurrentHashMap<>();

    public MemoryMaintenanceService(WebClient qdrantWebClient,
                                     MyHelperProperties props) {
        this.qdrant = qdrantWebClient;
        this.props = props;
        this.objectMapper = new ObjectMapper();
    }

    // ========================================================================
    // 定时任务
    // ========================================================================

    @Scheduled(cron = "${myhelper.memory-maintenance.cron:0 0 4 * * ?}")
    public void scheduledCleanup() {
        if (!props.memoryMaintenance().enabled()) return;
        try {
            cleanup();
        } catch (Exception e) {
            log.error("❌ 记忆维护失败", e);
        }
    }

    // ========================================================================
    // 主清理流程
    // ========================================================================

    /** 执行清理（价值评分版） */
    public void cleanup() {
        MyHelperProperties.MemoryMaintenance mm = props.memoryMaintenance();

        // 1. 计算占用率
        double usageRate = calculateUsageRate();
        if (usageRate < mm.threshold()) {
            log.info("📊 记忆占用率 {} < 阈值 {}，无需清理", fmt(usageRate), fmtPct(mm.threshold()));
            return;
        }

        log.info("🧹 记忆占用率 {} ≥ 阈值 {}，触发价值评分清理...", fmt(usageRate), fmtPct(mm.threshold()));

        // 2. 滚动获取所有 Episode
        List<Episode> allEpisodes = scrollAllEpisodes();
        if (allEpisodes.isEmpty()) {
            log.info("  ℹ️ 无 Episode 可清理");
            return;
        }

        log.info("  📋 共加载 {} 条 Episode，开始评分...", allEpisodes.size());

        // 3. 计算价值评分，筛选删除候选
        List<CleanupCandidate> candidates = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Episode ep : allEpisodes) {
            int ageDays = (int) ((now - ep.timestamp()) / 86_400_000L);

            // 保护期内 → 跳过
            String sourceType = resolveSourceType(ep);
            int retentionDays = getRetentionDays(sourceType);
            if (ageDays < retentionDays) continue;

            // 白名单 → 跳过
            if (isProtected(ep.id())) continue;

            // 高稳定性 active 条目 → 跳过（仍在活跃使用中）
            if (!ep.archived() && ep.stability() >= 0.3) continue;

            // 计算价值评分
            double valueScore = computeValueScore(ep, ageDays, retentionDays);
            if (valueScore >= MIN_VALUE_SCORE) continue;

            String title = ep.userInput() != null ? ep.userInput()
                    : (ep.successLesson() != null ? ep.successLesson() : "无标题");
            candidates.add(new CleanupCandidate(
                    ep.id(), valueScore, truncate(title, 60),
                    sourceType, ageDays, ep.successCount(),
                    ep.unitType() != null ? ep.unitType().name() : "COMPOSITE"
            ));
        }

        if (candidates.isEmpty()) {
            log.info("  ℹ️ 无符合条件的低价值 Episode");
            return;
        }

        // 4. 按价值评分升序排列，取需删除数量
        candidates.sort(null);
        int targetToRemove = (int) ((usageRate - mm.target()) * MAX_POINTS);
        int removeCount = Math.min(targetToRemove, candidates.size());
        List<CleanupCandidate> toDelete = candidates.subList(0, removeCount);

        // 5. 打印清理摘要
        log.info("  📊 清理摘要:");
        log.info("    候选数: {} 条，计划删除: {} 条", candidates.size(), removeCount);
        log.info("    分值范围: [{} ~ {}]", fmtRaw(toDelete.get(0).valueScore()),
                fmtRaw(toDelete.get(toDelete.size() - 1).valueScore()));

        // 打印前 5 条
        int preview = Math.min(5, toDelete.size());
        for (int i = 0; i < preview; i++) {
            CleanupCandidate c = toDelete.get(i);
            log.info("    [{}] \"{}\" ({}, {}天)", fmtRaw(c.valueScore()), c.title(), c.sourceType(), c.ageDays());
        }

        // 6. 批量删除
        List<String> ids = toDelete.stream().map(CleanupCandidate::episodeId).collect(Collectors.toList());
        int deleted = batchDelete(ids);

        log.info("  📦 已删除 {} 条低价值 Episode", deleted);
        log.info("✅ 记忆维护完成（价值评分模式）");
    }

    // ========================================================================
    // 价值评分算法
    // ========================================================================

    /**
     * 计算 Episode 的综合价值评分。
     * <pre>
     * valueScore = w1 × recencyScore + w2 × usageScore + w3 × userProducedScore + w4 × dependencyScore
     * </pre>
     */
    double computeValueScore(Episode ep, int ageDays, int retentionDays) {
        MyHelperProperties.MemoryMaintenance.Weights w = props.memoryMaintenance().weights();

        // recencyScore = 1.0 - min(age/retention, 1.0)
        double recency = 1.0 - Math.min((double) ageDays / retentionDays, 1.0);

        // usageScore = min(successCount/10.0, 1.0)
        double usage = Math.min(ep.successCount() / 10.0, 1.0);

        // userProducedScore: user_task=1.0, 其他=0.5
        String sourceType = resolveSourceType(ep);
        double userProduced = "user_task".equals(sourceType) ? 1.0 : 0.5;

        // dependencyScore: ATOMIC 被引用数/5.0，COMPOSITE=0
        double dependency = 0.0;
        if (ep.unitType() == Episode.UnitType.ATOMIC && ep.parentIds() != null) {
            dependency = Math.min(ep.parentIds().size() / 5.0, 1.0);
        }

        return w.recency() * recency + w.usage() * usage
                + w.userProduced() * userProduced + w.dependency() * dependency;
    }

    /** 根据 explorationType 解析来源类型 */
    private String resolveSourceType(Episode ep) {
        if (ep.explorationType() == Episode.ExplorationType.AUTONOMOUS) {
            return "autonomous-exploration";
        }
        if (ep.explorationType() == Episode.ExplorationType.MANUAL) {
            return "autonomous-exploration"; // 手动触发的也算探索
        }
        // 失败模式
        if (ep.status() == Episode.EpisodeStatus.FAILED || ep.failureCount() > 0) {
            return "failure-pattern";
        }
        return "user_task";
    }

    /** 查 retention-rules 获取保留天数 */
    private int getRetentionDays(String sourceType) {
        MyHelperProperties.MemoryMaintenance.RetentionRules rules =
                props.memoryMaintenance().retentionRules();
        if (rules == null) return 90;
        return switch (sourceType) {
            case "user_task" -> rules.userTask();
            case "autonomous-exploration" -> rules.autonomousExploration();
            case "failure-pattern" -> rules.failurePattern();
            default -> rules.defaultDays();
        };
    }

    // ========================================================================
    // 白名单管理
    // ========================================================================

    /** 添加永久保护 */
    public void protectMemory(String episodeId) {
        protectedIds.put(episodeId, 0L);
        log.info("🛡️ 已标记永久保护: {}", episodeId);
    }

    /** 添加限期保护（N 天后自动失效） */
    public void protectMemory(String episodeId, int days) {
        long expireAt = System.currentTimeMillis() + days * 86_400_000L;
        protectedIds.put(episodeId, expireAt);
        log.info("🛡️ 已标记保护 {} 天: {}", days, episodeId);
    }

    /** 移除保护 */
    public void unprotectMemory(String episodeId) {
        protectedIds.remove(episodeId);
        log.info("🔓 已移除保护: {}", episodeId);
    }

    /** 检查是否受保护（自动清理过期保护） */
    boolean isProtected(String episodeId) {
        Long expireAt = protectedIds.get(episodeId);
        if (expireAt == null) return false;
        // 0 = 永久保护
        if (expireAt == 0L) return true;
        // 已过期 → 自动移除
        if (System.currentTimeMillis() > expireAt) {
            protectedIds.remove(episodeId);
            return false;
        }
        return true;
    }

    // ========================================================================
    // Qdrant 操作
    // ========================================================================

    /** 计算 Qdrant 占用率 */
    public double getUsageRate() {
        return calculateUsageRate();
    }

    /** 获取清理阈值 */
    public double getThreshold() {
        return props.memoryMaintenance().threshold();
    }

    /** 获取清理目标 */
    public double getTarget() {
        return props.memoryMaintenance().target();
    }

    @SuppressWarnings("unchecked")
    private double calculateUsageRate() {
        try {
            int totalPoints = 0;

            Map<String, Object> episodesInfo = qdrant.get()
                    .uri("/collections/episodes")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (episodesInfo != null) {
                Map<String, Object> result = (Map<String, Object>) episodesInfo.get("result");
                if (result != null) {
                    Object count = result.get("points_count");
                    if (count instanceof Number) totalPoints += ((Number) count).intValue();
                }
            }

            Map<String, Object> fpInfo = qdrant.get()
                    .uri("/collections/failure-patterns")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .onErrorReturn(Map.of())
                    .block();
            if (fpInfo != null) {
                Map<String, Object> result = (Map<String, Object>) fpInfo.get("result");
                if (result != null) {
                    Object count = result.get("points_count");
                    if (count instanceof Number) totalPoints += ((Number) count).intValue();
                }
            }

            return Math.min((double) totalPoints / MAX_POINTS, 1.0);
        } catch (Exception e) {
            log.warn("⚠️ 占用率计算失败", e);
            return 0.0;
        }
    }

    /** 滚动获取所有 Episode（含 payload，无 vector） */
    private List<Episode> scrollAllEpisodes() {
        List<Episode> all = new ArrayList<>();
        String nextOffset = null;

        try {
            do {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("limit", 500);
                body.put("with_payload", true);
                body.put("with_vector", false);
                if (nextOffset != null) body.put("offset", nextOffset);

                QdrantDtos.ScrollResponse response = qdrant.post()
                        .uri("/collections/episodes/points/scroll")
                        .header("Content-Type", "application/json")
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(QdrantDtos.ScrollResponse.class)
                        .block();

                if (response == null || response.result() == null) break;

                List<QdrantDtos.ScoredPoint> points = response.result().points();
                if (points != null) {
                    for (QdrantDtos.ScoredPoint point : points) {
                        Episode ep = Episode.fromQdrantPoint(point, objectMapper);
                        if (ep != null) all.add(ep);
                    }
                }

                String nxt = response.result().next_page_offset();
                nextOffset = (nxt != null && !nxt.isEmpty()) ? nxt : null;

            } while (nextOffset != null);

        } catch (Exception e) {
            log.warn("⚠️ 滚动获取 Episode 失败", e);
        }

        return all;
    }

    /** 批量删除 Qdrant points */
    private int batchDelete(List<String> ids) {
        if (ids.isEmpty()) return 0;
        try {
            Map<String, Object> deleteBody = Map.of("points", ids);
            qdrant.post()
                    .uri("/collections/episodes/points/delete?wait=true")
                    .header("Content-Type", "application/json")
                    .bodyValue(deleteBody)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            return ids.size();
        } catch (Exception e) {
            log.warn("⚠️ 批量删除失败", e);
            return 0;
        }
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    private static String fmt(double rate) {
        return String.format("%.1f%%", rate * 100);
    }

    private static String fmtPct(double pct) {
        return String.format("%.0f%%", pct * 100);
    }

    private static String fmtRaw(double v) {
        return String.format("%.2f", v);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }
}
