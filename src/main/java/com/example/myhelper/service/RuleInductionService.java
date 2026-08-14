package com.example.myhelper.service;

import com.example.myhelper.common.PromptLoader;
import com.example.myhelper.memory.graph.FailurePatternNode;
import com.example.myhelper.memory.graph.FailurePatternRepository;
import com.example.myhelper.memory.graph.RuleNode;
import com.example.myhelper.memory.graph.RuleRepository;
import com.example.myhelper.memory.vector.episode.EpisodeCacheService;
import com.example.myhelper.config.ModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则归纳服务 —— 从 FailurePattern + 成功 Episode 中跨案例泛化出通用规则。
 *
 * <p>定时任务（每天凌晨 3:00）扫描近期失败模式和成功案例，
 * 调用 LLM 做跨案例归纳，生成抽象的通用规则存入 Neo4j {@code (:Rule)} 节点。
 * 这些规则会被注入到 {@link ToolPlanner} 的 System Prompt 中，
 * 让 AI 在规划阶段就能主动避坑。</p>
 *
 * <p>这是从"记性好的学徒"到"会总结规律的大师"的关键模块。</p>
 */
@Service
public class RuleInductionService {

    private static final Logger log = LoggerFactory.getLogger(RuleInductionService.class);

    private final ModelRouter modelRouter;
    private final RuleRepository ruleRepo;
    private final FailurePatternRepository failurePatternRepo;
    private final EpisodeCacheService episodeCacheService;
    private final PromptLoader promptLoader;

    public RuleInductionService(ModelRouter modelRouter,
                                 RuleRepository ruleRepo,
                                 FailurePatternRepository failurePatternRepo,
                                 EpisodeCacheService episodeCacheService,
                                 PromptLoader promptLoader) {
        this.modelRouter = modelRouter;
        this.ruleRepo = ruleRepo;
        this.failurePatternRepo = failurePatternRepo;
        this.episodeCacheService = episodeCacheService;
        this.promptLoader = promptLoader;
    }

    /**
     * 定期归纳规则（每天凌晨 3:00）。
     *
     * <p>首次运行时没有规则，后续只归纳新增的 FailurePattern（距上次归纳之后产生的）。</p>
     */
    @Scheduled(cron = "${myhelper.rule-induction.cron:0 0 3 * * ?}")
    public void scheduledInduction() {
        log.info("🧠 开始定时规则归纳...");
        try {
            int newRules = induceRules();
            if (newRules > 0) {
                log.info("🧠 规则归纳完成，新增 {} 条规则", newRules);
            } else {
                log.info("🧠 规则归纳完成，无新增规则");
            }
        } catch (Exception e) {
            log.error("⚠️ 规则归纳失败: {}", e.getMessage());
        }
    }

    /**
     * 执行一次规则归纳（可手动触发）。
     *
     * @return 新生成的规则数量
     */
    public int induceRules() {
        // 1. 获取上次归纳时间（最近一条规则的创建时间）
        long lastInductionTime = getLastInductionTime();

        // 2. 扫描近期 FailurePatterns
        List<FailurePatternNode> recentFailures = failurePatternRepo.findAll();
        List<FailurePatternNode> newFailures = recentFailures.stream()
                .filter(f -> f.getDetectedAt() > lastInductionTime)
                .toList();

        // 3. 扫描近期成功 Episodes
        List<String> recentSuccesses = episodeCacheService.getRecentSuccessfulEpisodeSummaries(30);

        // 4. 没有新材料则跳过
        if (newFailures.isEmpty() && recentSuccesses.isEmpty()) {
            log.info("🧠 无新材料，跳过规则归纳");
            return 0;
        }

        // 5. 构建 prompt
        String prompt = buildInductionPrompt(newFailures, recentSuccesses);

        // 6. 调用 LLM
        String response;
        try {
            response = modelRouter.chat().prompt().user(prompt).call().content();
        } catch (Exception e) {
            log.error("⚠️ LLM 规则归纳调用失败: {}", e.getMessage());
            return 0;
        }

        // 7. 解析 LLM 返回的规则
        List<RuleNode> rules = parseRules(response);
        if (rules.isEmpty()) return 0;

        // 8. 去重：与已有规则比对，只保存新规则
        List<RuleNode> existingRules = ruleRepo.findAllByOrderByCreatedDesc();
        Set<String> existingSummaries = new HashSet<>();
        for (RuleNode r : existingRules) {
            existingSummaries.add(r.getSummary().trim().toLowerCase());
        }

        int newCount = 0;
        for (RuleNode rule : rules) {
            if (!existingSummaries.contains(rule.getSummary().trim().toLowerCase())) {
                ruleRepo.save(rule);
                newCount++;
                log.info("📜 新规则: [{}%] {}", String.format("%.0f", rule.getConfidence() * 100), rule.getSummary());
            }
        }

        return newCount;
    }

    /**
     * 获取当前所有启用的规则（供 ToolPlanner 注入 System Prompt）。
     */
    public List<RuleNode> getActiveRules() {
        try {
            return ruleRepo.findByEnabledTrueOrderByConfidenceDesc();
        } catch (Exception e) {
            log.error("⚠️ 获取活跃规则失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ========================================================================
    // Private Helpers
    // ========================================================================

    private long getLastInductionTime() {
        List<RuleNode> all = ruleRepo.findAllByOrderByCreatedDesc();
        if (all.isEmpty()) return 0; // 首次运行，扫描所有
        return all.get(0).getCreated();
    }

    private String buildInductionPrompt(List<FailurePatternNode> failures,
                                         List<String> successes) {
        StringBuilder sb = new StringBuilder();
        sb.append("请从以下案例中归纳出通用规则：\n\n");

        if (!failures.isEmpty()) {
            sb.append("=== 近期失败模式 ===\n");
            for (FailurePatternNode f : failures) {
                sb.append("- 类型: ").append(f.getType()).append("\n");
                sb.append("  描述: ").append(f.getDescription()).append("\n");
                if (f.getMitigation() != null && !f.getMitigation().isEmpty()) {
                    sb.append("  建议: ").append(f.getMitigation()).append("\n");
                }
                sb.append("  失败次数: ").append(f.getCount()).append("\n\n");
            }
        }

        if (!successes.isEmpty()) {
            sb.append("=== 近期成功案例 ===\n");
            for (int i = 0; i < Math.min(successes.size(), 15); i++) {
                sb.append("- ").append(successes.get(i)).append("\n");
            }
            sb.append("\n");
        }

        sb.append("请归纳出 3-5 条通用规则，每条规则用一行，格式：\n");
        sb.append("RULE: 规则描述 | CONFIDENCE: 0.85 | SOURCE: from_failure_pattern | TOOLS: toolA,toolB\n\n");
        sb.append("要求：\n");
        sb.append("- 规则必须是跨案例的通用准则，而非单个案例的描述\n");
        sb.append("- CONFIDENCE 是 0.0-1.0 之间的小数，表示规则的置信度\n");
        sb.append("- SOURCE 选填 from_failure_pattern 或 from_success_episode\n");
        sb.append("- TOOLS 是可能受此规则影响的工具名（逗号分隔），不知道就填 unknown\n");
        sb.append("- 不要输出任何其他内容");

        return sb.toString();
    }

    private List<RuleNode> parseRules(String response) {
        if (response == null || response.isBlank()) return List.of();

        List<RuleNode> rules = new ArrayList<>();
        Pattern pattern = Pattern.compile(
                "RULE:\\s*(.+?)\\s*\\|\\s*CONFIDENCE:\\s*([0-9.]+)\\s*\\|\\s*SOURCE:\\s*(\\S+)\\s*\\|\\s*TOOLS:\\s*(\\S+)",
                Pattern.CASE_INSENSITIVE);

        for (String line : response.split("\n")) {
            Matcher m = pattern.matcher(line.trim());
            if (m.find()) {
                String summary = m.group(1).trim();
                double confidence;
                try {
                    confidence = Double.parseDouble(m.group(2));
                    confidence = Math.max(0.0, Math.min(1.0, confidence));
                } catch (NumberFormatException e) {
                    confidence = 0.7;
                }
                String source = m.group(3).trim();
                String toolsRaw = m.group(4).trim();
                List<String> tools = toolsRaw.equals("unknown") || toolsRaw.isEmpty()
                        ? List.of()
                        : Arrays.asList(toolsRaw.split(","));

                rules.add(new RuleNode(summary, confidence, source, tools, System.currentTimeMillis()));
            }
        }
        return rules;
    }
}
