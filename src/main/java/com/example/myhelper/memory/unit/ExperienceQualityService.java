package com.example.myhelper.memory.unit;

import com.example.myhelper.config.MyHelperProperties;
import com.example.myhelper.memory.graph.UnitNode;
import com.example.myhelper.memory.graph.UnitRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Unit 经验质量评分。
 *
 * <p>稳定度只回答“历史成功比例”，无法区分一次偶然成功与多次验证的可靠经验。本服务以
 * 可靠性、验证样本量、新鲜度和经验完整度合成 0~1 质量分，供复用、条件直达和记忆清理共用。</p>
 */
@Service
public class ExperienceQualityService {

    /** 一次有效执行也能复用，但需达到该分数门槛。 */
    @Value("${myhelper.experience-quality.min-reuse-score:0.55}")
    private double minReuseScore;

    /** 自动升级为条件满足时直接执行前必须达到的质量门槛。 */
    @Value("${myhelper.experience-quality.direct-execution-score:0.80}")
    private double directExecutionScore;

    /** 达到该样本数后，样本量维度不再额外加分。 */
    @Value("${myhelper.experience-quality.evidence-target:8}")
    private int evidenceTarget;

    /** 最近一次验证超过该半衰期会逐步降低新鲜度。 */
    @Value("${myhelper.experience-quality.freshness-half-life-days:90}")
    private int freshnessHalfLifeDays;

    private final UnitRepository unitRepository;
    private final MyHelperProperties props;

    public ExperienceQualityService(UnitRepository unitRepository, MyHelperProperties props) {
        this.unitRepository = unitRepository;
        this.props = props;
    }

    public record Assessment(double score, double reliability, double evidence,
                             double freshness, double completeness) {}

    /** 计算质量分，不修改节点，调用方决定何时持久化。 */
    public Assessment assess(UnitNode node) {
        if (node == null || "ARCHIVED".equalsIgnoreCase(node.getStatus())) {
            return new Assessment(0.0, 0.0, 0.0, 0.0, 0.0);
        }

        int success = Math.max(0, node.getSuccessCount());
        int failure = Math.max(0, node.getFailureCount());
        int observations = success + failure;

        // Beta(1,1) 平滑，避免一次成功被当作 100% 可靠。
        double reliability = (success + 1.0) / (observations + 2.0);
        double evidence = Math.min(1.0, observations / (double) Math.max(1, evidenceTarget));
        double freshness = freshness(node);
        double completeness = completeness(node);

        // 可靠性占主导；新鲜度和完整度保证旧经验/空经验不会被盲目复用。
        double score = 0.55 * reliability + 0.20 * evidence
                + 0.15 * freshness + 0.10 * completeness;
        return new Assessment(clamp(score), reliability, evidence, freshness, completeness);
    }

    public boolean isReusable(Unit unit) {
        return unit != null && unit.isReusable(minReuseScore);
    }

    public double minReuseScore() {
        return minReuseScore;
    }

    public double directExecutionScore() {
        return directExecutionScore;
    }

    /** 每天刷新一次长期未验证经验的分数；仅写分数，不改变 updatedAt。 */
    @Scheduled(cron = "${myhelper.experience-quality.refresh-cron:0 30 3 * * ?}")
    public void refreshPersistedScores() {
        if (!props.autonomous().enabled()) return;
        int updated = 0;
        for (UnitNode node : unitRepository.findAll()) {
            double score = assess(node).score();
            if (Math.abs(score - node.getQualityScore()) < 0.001) continue;
            node.setQualityScore(score);
            unitRepository.save(node);
            updated++;
        }
        if (updated > 0) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                    .info("📏 已刷新 {} 条 Unit 的经验质量分", updated);
        }
    }

    /** 汇总用于诊断和 AI 自检。 */
    public QualitySummary summarize() {
        int total = 0;
        int active = 0;
        int reusable = 0;
        double totalScore = 0.0;
        for (UnitNode node : unitRepository.findAll()) {
            total++;
            Assessment assessment = assess(node);
            totalScore += assessment.score();
            if (!"ARCHIVED".equalsIgnoreCase(node.getStatus())) {
                active++;
                if (assessment.score() >= minReuseScore) reusable++;
            }
        }
        return new QualitySummary(total, active, reusable,
                total == 0 ? 0.0 : totalScore / total, minReuseScore);
    }

    public record QualitySummary(int total, int active, int reusable,
                                 double averageScore, double minReuseScore) {}

    private double freshness(UnitNode node) {
        long reference = node.getUpdatedAt() != null ? node.getUpdatedAt()
                : node.getCreatedAt() != null ? node.getCreatedAt() : System.currentTimeMillis();
        long ageMs = Math.max(0L, System.currentTimeMillis() - reference);
        double ageDays = ageMs / 86_400_000.0;
        return Math.pow(0.5, ageDays / Math.max(1, freshnessHalfLifeDays));
    }

    private double completeness(UnitNode node) {
        boolean planStep = "PLAN_STEP".equalsIgnoreCase(node.getUnitKind());
        double score = 0.0;
        if (hasText(node.getGoal()) || hasText(node.getMatchText())) score += 0.35;
        if (planStep && !unitRepository.findChildUnitIdsOrdered(node.getUnitId()).isEmpty()) score += 0.35;
        if (!planStep && hasText(node.getToolName())) score += 0.35;
        if (hasMeaningfulJson(node.getNotesJson())) score += 0.15;
        if (hasMeaningfulJson(node.getOutputSignatureJson())) score += 0.15;
        return clamp(score);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasMeaningfulJson(String value) {
        return hasText(value) && !"[]".equals(value.trim()) && !"{}".equals(value.trim());
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
