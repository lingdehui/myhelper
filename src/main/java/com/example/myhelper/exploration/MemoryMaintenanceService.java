package com.example.myhelper.exploration;

import com.example.myhelper.config.MyHelperProperties;
import com.example.myhelper.memory.graph.UnitNode;
import com.example.myhelper.memory.graph.UnitRepository;
import com.example.myhelper.memory.unit.UnitStore;
import com.example.myhelper.memory.unit.ExperienceQualityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 记忆维护服务：定期检测占用率，基于多维价值评分清理低价值数据。
 *
 * <p>v1.7 起清理对象从旧 Episode 迁移到 Unit（Neo4j 主存储 + Qdrant 检索索引）。</p>
 *
 * <h3>清理策略（价值评分版）</h3>
 * <ol>
 *   <li>计算 Unit 占用率（Neo4j 节点数 / 上限），≥threshold 触发清理</li>
 *   <li>加载所有 Unit，跳过保护期内 + 白名单条目</li>
 *   <li>逐条计算价值分，并将经验质量分纳入保留优先级</li>
 *   <li>按分数升序取前 N 个删除候选</li>
 *   <li>通过 {@link UnitStore#delete(String)} 删除 Neo4j 节点 + Qdrant 索引</li>
 * </ol>
 *
 * <p>每天凌晨 4:00 执行，cron 可通过 myhelper.memory-maintenance.cron 配置。</p>
 */
@Service
public class MemoryMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceService.class);

    private final UnitStore unitStore;
    private final UnitRepository unitRepository;
    private final ExperienceQualityService experienceQualityService;
    private final MyHelperProperties props;

    /** Unit 数量上限（估算上限，超出视为 100% 占用） */
    private static final int MAX_UNITS = 10_000;

    /** 价值评分下限：低于此值加入删除候选 */
    private static final double MIN_VALUE_SCORE = 0.3;

    /** 白名单：key=Unit UUID, value=保护过期时间戳(ms, 0=永久) */
    private final ConcurrentHashMap<String, Long> protectedIds = new ConcurrentHashMap<>();

    public MemoryMaintenanceService(UnitStore unitStore,
                                     UnitRepository unitRepository,
                                     ExperienceQualityService experienceQualityService,
                                     MyHelperProperties props) {
        this.unitStore = unitStore;
        this.unitRepository = unitRepository;
        this.experienceQualityService = experienceQualityService;
        this.props = props;
    }

    // ========================================================================
    // 定时任务
    // ========================================================================

    @Scheduled(cron = "${myhelper.memory-maintenance.cron:0 0 4 * * ?}")
    public void scheduledCleanup() {
        if (!props.autonomous().enabled()) return;
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

        // 2. 加载所有 Unit
        List<UnitNode> allUnits = listAllUnits();
        if (allUnits.isEmpty()) {
            log.info("  ℹ️ 无 Unit 可清理");
            return;
        }

        log.info("  📋 共加载 {} 个 Unit，开始评分...", allUnits.size());

        // 3. 计算价值评分，筛选删除候选
        List<CleanupCandidate> candidates = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (UnitNode node : allUnits) {
            long createdAt = node.getCreatedAt() != null ? node.getCreatedAt() : now;
            int ageDays = (int) ((now - createdAt) / 86_400_000L);

            // 保护期内 → 跳过
            String sourceType = resolveSourceType(node);
            int retentionDays = getRetentionDays(sourceType);
            if (ageDays < retentionDays) continue;

            // 白名单 → 跳过
            if (isProtected(node.getUnitId())) continue;

            // 高稳定性 active 条目 → 跳过（仍在活跃使用中）
            boolean archived = "ARCHIVED".equalsIgnoreCase(node.getStatus());
            if (!archived && node.getStability() >= 0.3) continue;

            // 计算价值评分
            double valueScore = computeValueScore(node, ageDays, retentionDays);
            if (valueScore >= MIN_VALUE_SCORE) continue;

            String title = firstNonBlank(node.getGoal(), node.getMatchText());
            if (title == null) title = "无标题";
            candidates.add(new CleanupCandidate(
                    node.getUnitId(), valueScore, truncate(title, 60),
                    sourceType, ageDays, node.getSuccessCount(),
                    node.getUnitKind() != null ? node.getUnitKind() : "PLAN_STEP"
            ));
        }

        if (candidates.isEmpty()) {
            log.info("  ℹ️ 无符合条件的低价值 Unit");
            return;
        }

        // 4. 按价值评分升序排列，取需删除数量
        candidates.sort(null);
        int targetToRemove = (int) ((usageRate - mm.target()) * MAX_UNITS);
        int removeCount = Math.min(targetToRemove, candidates.size());
        List<CleanupCandidate> toDelete = candidates.subList(0, removeCount);

        // 5. 打印清理摘要
        log.info("  📊 清理摘要:");
        log.info("    候选数: {} 条，计划删除: {} 条", candidates.size(), removeCount);
        log.info("    分值范围: [{} ~ {}]", fmtRaw(toDelete.get(0).valueScore()),
                fmtRaw(toDelete.get(toDelete.size() - 1).valueScore()));

        int preview = Math.min(5, toDelete.size());
        for (int i = 0; i < preview; i++) {
            CleanupCandidate c = toDelete.get(i);
            log.info("    [{}] \"{}\" ({}, {}天)", fmtRaw(c.valueScore()), c.title(), c.sourceType(), c.ageDays());
        }

        // 6. 批量删除
        List<String> ids = toDelete.stream().map(CleanupCandidate::unitId).collect(Collectors.toList());
        int deleted = batchDelete(ids);

        log.info("  📦 已删除 {} 个低价值 Unit", deleted);
        log.info("✅ 记忆维护完成（价值评分模式）");
    }

    // ========================================================================
    // 价值评分算法
    // ========================================================================

    /**
     * 计算 Unit 的综合价值评分。
     * <pre>
     * valueScore = 0.7 × lifecycleValue + 0.3 × experienceQuality
     * </pre>
     */
    double computeValueScore(UnitNode node, int ageDays, int retentionDays) {
        MyHelperProperties.MemoryMaintenance.Weights w = props.memoryMaintenance().weights();

        // recencyScore = 1.0 - min(age/retention, 1.0)
        double recency = 1.0 - Math.min((double) ageDays / retentionDays, 1.0);

        // usageScore = min(successCount/10.0, 1.0)
        double usage = Math.min(node.getSuccessCount() / 10.0, 1.0);

        // userProducedScore: user_task=1.0, 其他=0.5
        String sourceType = resolveSourceType(node);
        double userProduced = "user_task".equals(sourceType) ? 1.0 : 0.5;

        // dependencyScore: TOOL 被引用数/5.0，PLAN_STEP=0
        double dependency = 0.0;
        if ("TOOL".equalsIgnoreCase(node.getUnitKind())) {
            List<String> parents = unitRepository.findParentsOf(node.getUnitId());
            dependency = Math.min((parents != null ? parents.size() : 0) / 5.0, 1.0);
        }

        double lifecycleValue = w.recency() * recency + w.usage() * usage
                + w.userProduced() * userProduced + w.dependency() * dependency;
        double quality = experienceQualityService.assess(node).score();
        return 0.7 * lifecycleValue + 0.3 * quality;
    }

    /** 根据 Unit 来源解析类型 */
    private String resolveSourceType(UnitNode node) {
        // 有探索记录 → 自主探索产物
        if (node.getExplorationRecordsJson() != null
                && !node.getExplorationRecordsJson().isBlank()
                && !"[]".equals(node.getExplorationRecordsJson())) {
            return "autonomous-exploration";
        }
        // 失败/归档 → 失败模式
        if ("ARCHIVED".equalsIgnoreCase(node.getStatus()) || node.getFailureCount() > 0) {
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
    public void protectMemory(String unitId) {
        protectedIds.put(unitId, 0L);
        log.info("🛡️ 已标记永久保护: {}", unitId);
    }

    /** 添加限期保护（N 天后自动失效） */
    public void protectMemory(String unitId, int days) {
        long expireAt = System.currentTimeMillis() + days * 86_400_000L;
        protectedIds.put(unitId, expireAt);
        log.info("🛡️ 已标记保护 {} 天: {}", days, unitId);
    }

    /** 移除保护 */
    public void unprotectMemory(String unitId) {
        protectedIds.remove(unitId);
        log.info("🔓 已移除保护: {}", unitId);
    }

    /** 检查是否受保护（自动清理过期保护） */
    boolean isProtected(String unitId) {
        Long expireAt = protectedIds.get(unitId);
        if (expireAt == null) return false;
        // 0 = 永久保护
        if (expireAt == 0L) return true;
        // 已过期 → 自动移除
        if (System.currentTimeMillis() > expireAt) {
            protectedIds.remove(unitId);
            return false;
        }
        return true;
    }

    // ========================================================================
    // Unit 操作
    // ========================================================================

    /** 计算 Unit 占用率（Neo4j 节点数 / 上限） */
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

    private double calculateUsageRate() {
        try {
            long count = unitRepository.count();
            return Math.min((double) count / MAX_UNITS, 1.0);
        } catch (Exception e) {
            log.warn("⚠️ 占用率计算失败", e);
            return 0.0;
        }
    }

    /** 加载所有 Unit（Neo4j 主存储） */
    private List<UnitNode> listAllUnits() {
        List<UnitNode> all = new ArrayList<>();
        unitRepository.findAll().forEach(all::add);
        return all;
    }

    /** 批量删除 Unit（Neo4j + Qdrant 索引） */
    private int batchDelete(List<String> ids) {
        int deleted = 0;
        for (String id : ids) {
            try {
                unitStore.delete(id);
                deleted++;
            } catch (Exception e) {
                log.warn("⚠️ Unit 删除失败: {} → {}", id, e.getMessage());
            }
        }
        return deleted;
    }

    // ========================================================================
    // 工具方法
    // ========================================================================

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

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
