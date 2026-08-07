package com.example.desktopbrain.service;

import com.example.desktopbrain.common.AiResponseUtils;
import com.example.desktopbrain.common.PromptLoader;
import com.example.desktopbrain.config.DesktopBrainProperties;
import com.example.desktopbrain.memory.graph.RuleNode;
import com.example.desktopbrain.memory.vector.category.ToolCategoryService;
import com.example.desktopbrain.memory.vector.episode.Episode;
import com.example.desktopbrain.memory.vector.episode.EpisodeCacheService;
import com.example.desktopbrain.memory.vector.episode.ToolCallLog;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 三阶段工具规划器（升级版：三层缓存 + Episode 经验学习）。
 *
 * <p>借鉴 ExpeL/MUSE 经验学习思想，在原有内存缓存基础上增加 Episode 向量检索层，
 * 实现跨重启的语义级方案复用。</p>
 *
 * <h3>三层缓存查询逻辑</h3>
 * <pre>
 * plan(userInput, allTools):
 *   [Layer 1] 内存缓存（ConcurrentHashMap，key=AiResponseUtils.normalizeKey(userInput)）
 *             命中 → 返回 PlanResult(fromCache=true, episodeId=entry.episodeId)
 *             不查 Qdrant，避免重复 embed（200-500ms）
 *   [Layer 2] Episode 向量检索（Qdrant episodes collection）
 *             embed(userInput) → search(top_k=3, score_threshold=0.65,
 *                                        filter: archived=false AND stability>=0.6)
 *             命中 → 回填内存缓存 → 返回 PlanResult(fromCache=true, episodeId=ep.id)
 *   [Layer 3] AI 规划（现有 doPlan 逻辑）
 *             返回 PlanResult(fromCache=false, episodeId=null)
 * </pre>
 *
 * <h3>淘汰策略（两层独立）</h3>
 * <ul>
 *   <li>内存缓存：连续失败 3 次 → 清除（重启后也清空）</li>
 *   <li>Episode：failureCount+1，stability&lt;0.3 或连续失败 3 次 → archived=true
 *       （不被检索但数据保留，跨重启持久化）</li>
 * </ul>
 *
 * <h3>反馈学习</h3>
 * <ul>
 *   <li>命中缓存+成功 → 内存重置失败计数 + 异步 incrementSuccess（successCount+1，不重置 failureCount，让 stability 自然衰退）</li>
 *   <li>命中缓存+失败 → 内存失败计数+1 + 异步 recordFailure（达阈值 archive）</li>
 *   <li>首次规划+成功 → 内存写入 + 异步 recordSuccess（新建 Episode，返回 episodeId 回填内存缓存）</li>
 *   <li>首次请求就失败 → episodeId=null，所有回调 no-op（Episode 库只存成功方案）</li>
 * </ul>
 */
@Component
public class ToolPlanner {

    private final ChatClient chatClient;
    private final EpisodeCacheService episodeCacheService;
    private final ToolCategoryService categoryService;
    private final RuleInductionService ruleInductionService;
    private final DesktopBrainProperties props;
    private final PromptLoader promptLoader;

    /** 脚本化阈值：与 EpisodeCacheService 保持一致，successCount≥此值 且 stability>0.9 时 canScript=true */
    @Value("${qdrant.episode.script-success-threshold:5}")
    private int scriptSuccessThreshold;

    /** 缓存条目：方案 + episodeId + episode引用 + 连续失败次数 */
    private static class CacheEntry {
        final List<String> selectedToolNames;
        final List<String> missingDescriptions;
        final String episodeId;  // 关联的 Qdrant episode id（可能为 null）
        volatile Episode episode;   // episode 引用（含 successLesson/failureLesson，可能为 null）；volatile 因 onCacheHitSuccess 会更新 canScript
        volatile int failureCount;     // volatile：onCacheHitSuccess 和 onCacheHitFailure 可能并发
        CacheEntry(List<String> selectedToolNames, List<String> missingDescriptions,
                   String episodeId, Episode episode) {
            this.selectedToolNames = selectedToolNames;
            this.missingDescriptions = missingDescriptions;
            this.episodeId = episodeId;
            this.episode = episode;
            this.failureCount = 0;
        }
    }

    private final Map<String, CacheEntry> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(200, 0.75f, true) {  // access-order LRU
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > props.toolPlanner().maxCacheSize();
                }
            });

    public ToolPlanner(ChatClient.Builder chatClientBuilder,
                        EpisodeCacheService episodeCacheService,
                        ToolCategoryService categoryService,
                        RuleInductionService ruleInductionService,
                        DesktopBrainProperties props,
                        PromptLoader promptLoader) {
        this.chatClient = chatClientBuilder
                .defaultSystem("你是工具规划器，根据用户请求选择所需的能力分类，只返回编号。")
                .build();
        this.episodeCacheService = episodeCacheService;
        this.categoryService = categoryService;
        this.ruleInductionService = ruleInductionService;
        this.props = props;
        this.promptLoader = promptLoader;
    }

    /**
     * 规划结果。
     *
     * @param selectedToolNames   选中的工具名列表
     * @param missingDescriptions 缺失的工具描述
     * @param fromCache           是否来自缓存（内存或 Episode）
     * @param episodeId           关联的 Episode id（内存命中或 Episode 命中时非 null；AI 规划时为 null）
     * @param episode             命中的完整 Episode（含 successLesson/failureLesson/toolCalls）；
     *                            AI 规划时为 null。调用方可据此增强 prompt（参考计划+经验提示）
     * @param failureWarnings     历史失败警告列表（向量检索匹配的 FailurePattern），用于注入 AI prompt 避坑
     */
    public record PlanResult(
            List<String> selectedToolNames,
            List<String> missingDescriptions,
            boolean fromCache,
            String episodeId,
            Episode episode,
            List<EpisodeCacheService.FailureSearchResult> failureWarnings
    ) {
        /** AI 规划的便捷工厂（episode=null, failureWarnings=empty） */
        public static PlanResult ofAIPlan(List<String> tools, List<String> missing) {
            return new PlanResult(tools, missing, false, null, null, List.of());
        }
        /** 带失败警告的 AI 规划工厂 */
        public static PlanResult ofAIPlan(List<String> tools, List<String> missing,
                                           List<EpisodeCacheService.FailureSearchResult> warnings) {
            return new PlanResult(tools, missing, false, null, null, warnings);
        }
    }

    /**
     * 正常规划：三层缓存查询（内存 → Episode → AI 规划）。
     *
     * <p>命中缓存时 PlanResult.episode 非 null（含 successLesson/failureLesson/toolCalls），
     * 调用方（DesktopBrainApplication）可据此让 AI 判断可用性 + 带参考计划执行。</p>
     */
    public PlanResult plan(String userInput, ToolCallback[] allTools) {
        String key = AiResponseUtils.normalizeKey(userInput);

        // ===== Layer 1: 内存缓存（精确匹配，最快）=====
        CacheEntry entry = cache.get(key);
        if (entry != null) {
            System.out.println("💾 命中内存缓存（失败计数: " + entry.failureCount + "/" + props.toolPlanner().failureThreshold()
                    + (entry.episodeId != null ? ", episode=" + entry.episodeId.substring(0, 8) + "..." : "") + "）");
            // L1 命中但仍检索失败警告（轻量，不额外 embed）
            List<EpisodeCacheService.FailureSearchResult> warnL1 = episodeCacheService.searchFailurePatterns(userInput, 3);
            return new PlanResult(entry.selectedToolNames, entry.missingDescriptions,
                    true, entry.episodeId, entry.episode, warnL1);
        }

        // ===== Layer 2: Episode 向量检索 + 失败模式并行检索 =====
        Optional<Episode> ep = episodeCacheService.findSimilarEpisode(userInput);
        List<EpisodeCacheService.FailureSearchResult> warnings = episodeCacheService.searchFailurePatterns(userInput, 3);

        if (!warnings.isEmpty()) {
            System.out.println("⚠️ 发现 " + warnings.size() + " 条相关失败警告: "
                    + warnings.stream().map(w -> w.type() + "(" + String.format("%.0f%%", w.score() * 100) + ")")
                    .reduce((a, b) -> a + ", " + b).orElse(""));
        }

        if (ep.isPresent()) {
            Episode episode = ep.get();
            // 回填内存缓存（带 episode 引用，含 lesson），下次相同输入直接内存命中
            cache.put(key, new CacheEntry(episode.selectedToolNames(), episode.missingDescriptions(),
                    episode.id(), episode));
            System.out.println("💾 命中 Episode 缓存（稳定度: " + String.format("%.2f", episode.computedStability())
                    + ", episode=" + episode.id().substring(0, 8) + "...）");
            return new PlanResult(episode.selectedToolNames(), episode.missingDescriptions(),
                    true, episode.id(), episode, warnings);
        }

        // ===== Layer 3: AI 规划 =====
        return doPlan(userInput, allTools, null, warnings);
    }

    /**
     * 缓存命中但失败时调用（带 AI 归因结果）。
     *
     * <p>用户核心逻辑："按问题判断是否是计划问题→是计划问题→失败数+1→不是计划问题→分段继续"。</p>
     *
     * <p>归因结果决定惩罚策略：
     * <ul>
     *   <li>isPlanIssue=true（计划问题）：内存失败计数+1（达阈值清除）+ Episode failureCount+1（达阈值 archive）</li>
     *   <li>isPlanIssue=false（环境问题）：不惩罚计划，只存 failureLesson，保留缓存（下次还能用）</li>
     * </ul>
     *
     * @param userInput    用户原话（用于查内存缓存 key）
     * @param plan         当前命中的 PlanResult
     * @param failureLesson 失败教训（AI 反思总结，可为 null）
     * @param isPlanIssue  是否计划逻辑问题（true=惩罚 / false=不惩罚）
     * @return true = 应写入新方案（计划问题且达阈值，或内存缓存已清除）；
     *         false = 保留旧缓存，本次用新方案但不持久化
     */
    public boolean onCacheHitFailure(String userInput, PlanResult plan,
                                      String failureLesson, boolean isPlanIssue) {
        String key = AiResponseUtils.normalizeKey(userInput);
        CacheEntry entry = cache.get(key);

        // 异步更新 Episode（带归因：计划问题才 failureCount+1，环境问题只存 lesson）
        episodeCacheService.recordFailure(plan.episodeId(), failureLesson, isPlanIssue);

        // 环境问题：不惩罚内存缓存，计划本身没问题，保留供下次使用
        if (!isPlanIssue) {
            System.out.println("ℹ️ 环境问题导致失败，不惩罚计划，保留缓存（分段继续）");
            return false;
        }

        // 计划问题：内存失败计数+1
        if (entry == null) {
            return true;
        }
        entry.failureCount++;
        if (entry.failureCount >= props.toolPlanner().failureThreshold()) {
            cache.remove(key);
            System.out.println("🗑️ 缓存连续失败 " + props.toolPlanner().failureThreshold() + " 次（计划问题），已清除旧方案");
            return true;
        }
        System.out.println("⚠️ 缓存方案失败（计划问题，第 " + entry.failureCount + " 次/" + props.toolPlanner().failureThreshold() + "），保留缓存，本次重新规划");
        return false;
    }

    /**
     * 缓存命中且成功：重置内存失败计数 + 同步更新内存 episode 统计 + 异步 Qdrant 成功计数+1。
     *
     * <p>Episode 端用 incrementSuccess（successCount+1，不重置 failureCount），
     * 让 stability 随环境变化自然衰退，避免老 episode 永不淘汰。</p>
     *
     * <p>修复问题3：同步更新内存 CacheEntry 的 episode.successCount 和 canScript，
     * 使内存命中时也能走脚本执行（否则内存 episode 的 canScript 永远是初始值 false）。</p>
     *
     * @param userInput 用户原话（用于查内存缓存 key）
     * @param plan      当前命中的 PlanResult（用 plan.episodeId() 调 Episode 成功计数）
     */
    public void onCacheHitSuccess(String userInput, PlanResult plan) {
        String key = AiResponseUtils.normalizeKey(userInput);
        CacheEntry entry = cache.get(key);
        if (entry != null) {
            if (entry.failureCount > 0) entry.failureCount = 0;
            // 修复问题3：同步更新内存 episode 的 successCount + canScript
            if (entry.episode != null) {
                Episode old = entry.episode;
                int newSuccess = old.successCount() + 1;
                int failure = old.failureCount();
                double newStability = Episode.calcStability(newSuccess, failure);
                boolean wasScriptable = old.canScript();
                boolean canScript = newSuccess >= scriptSuccessThreshold && newStability > 0.9;
                entry.episode = new Episode(
                        old.id(), old.userInput(), old.selectedToolNames(), old.missingDescriptions(),
                        old.toolCalls(), old.aiResponse(), old.successLesson(), old.failureLesson(),
                        old.signature(), old.unitType(), old.isGeneric(), old.parentIds(),
                        newSuccess, failure, old.archived(), old.timestamp(), newStability,
                        old.status(), canScript, old.failedStepIndex(), old.explorationType(), old.explorationSummary());
                if (canScript && !wasScriptable) {
                    System.out.println("🚀 内存缓存 episode 升级为可脚本化（id=" + old.id().substring(0, 8) + "...）");
                }
            }
        }
        episodeCacheService.incrementSuccess(plan.episodeId());
    }

    /** 重新规划（带失败原因，不走缓存） */
    public PlanResult replan(String userInput, ToolCallback[] allTools, String failureReason) {
        List<EpisodeCacheService.FailureSearchResult> warnings = episodeCacheService.searchFailurePatterns(userInput, 3);
        return doPlan(userInput, allTools, failureReason, warnings);
    }

    // ========== DRAFT 生命周期委托 ==========

    /**
     * 执行前创建 DRAFT episode（委托 EpisodeCacheService.createDraft）。
     *
     * @return episodeId（执行失败时返回 null）
     */
    public String createDraftEpisode(String userInput, PlanResult plan) {
        return episodeCacheService.createDraft(userInput,
                plan.selectedToolNames(), plan.missingDescriptions());
    }

    /**
     * DRAFT → ACTIVE（执行成功时调用，委托 EpisodeCacheService.activateDraft）。
     */
    public void activateDraftEpisode(String episodeId, List<ToolCallLog> toolCallLogs,
                                      String aiResponse, String successLesson) {
        episodeCacheService.activateDraft(episodeId, toolCallLogs, aiResponse, successLesson);
    }

    /**
     * DRAFT → FAILED（执行失败时调用，决策4：失败也保存步骤）。
     */
    public void failDraftEpisode(String episodeId, List<ToolCallLog> toolCallLogs,
                                  String failureLesson, int failedStepIndex) {
        episodeCacheService.failDraft(episodeId, toolCallLogs, failureLesson, failedStepIndex);
    }

    /** 从失败执行中提取可复用步骤链 */
    public void saveSalvageableChains(String userInput, List<ToolCallLog> toolCalls,
                                       List<List<Integer>> chains, String draftId) {
        episodeCacheService.saveSalvageableAtomicChains(userInput, toolCalls, chains, draftId);
    }

    /**
     * 写入新方案到内存缓存（首次规划成功 + draft 转 active 后调用）。
     *
     * <p>构造一个 Episode 对象存入内存，使后续内存命中时也能拿到 lesson 和参考计划。
     * Qdrant 持久化由 {@link #activateDraftEpisode} 完成，本方法只负责内存缓存。</p>
     *
     * @param userInput      用户原话
     * @param plan           首次规划成功的方案
     * @param episodeId      activateDraft 返回的 episode id
     * @param toolCallLogs   本次执行的工具调用轨迹
     * @param aiResponse     AI 最终回复
     * @param successLesson  成功经验（可为 null）
     */
    public void cacheToMemory(String userInput, PlanResult plan, String episodeId,
                               List<ToolCallLog> toolCallLogs, String aiResponse, String successLesson) {
        Episode memEpisode = new Episode(episodeId, userInput, plan.selectedToolNames(),
                plan.missingDescriptions(), toolCallLogs, AiResponseUtils.truncate(aiResponse, 500),
                successLesson, null, Map.of(), Episode.UnitType.COMPOSITE, false, List.of(),
                1, 0, false, System.currentTimeMillis(), 1.0,
                Episode.EpisodeStatus.ACTIVE, false, -1, null, null);
        cache.put(AiResponseUtils.normalizeKey(userInput), new CacheEntry(
                plan.selectedToolNames(), plan.missingDescriptions(), episodeId, memEpisode));
        System.out.println("💾 已写入内存缓存（" + plan.selectedToolNames().size()
                + " 个工具，episode=" + episodeId.substring(0, 8) + "..."
                + (successLesson != null ? ", 经验: " + AiResponseUtils.truncate(successLesson, 40) : "") + "）");
    }

    /**
     * 同步工具分类（启动时 / 工具变化时调用）。
     *
     * <p>委托 ToolCategoryService.syncCategories()，所有工具（MCP+本地+生成的）传入 AI 分组归类。
     * 应在所有 ToolCallback 就绪后调用一次。</p>
     *
     * @param allTools 所有可用工具
     * @return 分类数量
     */
    public int syncCategories(ToolCallback[] allTools) {
        return categoryService.syncCategories(allTools);
    }

    /** 内部：调 AI 规划（让 AI 从完整工具列表中直接选工具名） */
    private PlanResult doPlan(String userInput, ToolCallback[] allTools, String failureReason,
                               List<EpisodeCacheService.FailureSearchResult> warnings) {
        // 构建完整工具列表（工具名 + 简短描述）
        StringBuilder toolList = new StringBuilder();
        for (ToolCallback tc : allTools) {
            String name = tc.getToolDefinition().name();
            String desc = tc.getToolDefinition().description();
            toolList.append("- ").append(name);
            if (desc != null && !desc.isBlank()) {
                toolList.append(": ").append(AiResponseUtils.truncateNotNull(desc, 60));
            }
            toolList.append("\n");
        }

        String prompt;
        if (failureReason != null) {
            prompt = promptLoader.getReplanning().formatted(toolList, userInput, failureReason);
        } else {
            prompt = promptLoader.getPlanning().formatted(toolList, userInput);
        }

        // 注入历史失败警告到 AI 规划 prompt
        if (warnings != null && !warnings.isEmpty()) {
            StringBuilder warnBlock = new StringBuilder("\n\n--- ⚠️ 历史失败警告（请避免以下做法） ---\n");
            for (int i = 0; i < warnings.size(); i++) {
                var w = warnings.get(i);
                warnBlock.append((i + 1)).append(". ").append(w.type())
                        .append("（失败 ").append(w.count()).append(" 次, 相似度 ").append(String.format("%.0f%%", w.score() * 100)).append("）\n")
                        .append("   描述: ").append(w.description()).append("\n");
                if (w.mitigation() != null && !w.mitigation().isBlank()) {
                    warnBlock.append("   建议: ").append(w.mitigation()).append("\n");
                }
            }
            prompt += warnBlock.toString();
        }

        // 注入归纳的通用规则到 AI 规划 prompt
        List<RuleNode> activeRules = ruleInductionService.getActiveRules();
        if (!activeRules.isEmpty()) {
            StringBuilder ruleBlock = new StringBuilder("\n\n--- 📜 已知规则（请遵循） ---\n");
            for (int i = 0; i < Math.min(activeRules.size(), 10); i++) {
                var r = activeRules.get(i);
                ruleBlock.append((i + 1)).append(". ").append(r.getSummary())
                        .append("（置信度: ").append(String.format("%.0f%%", r.getConfidence() * 100)).append("）\n");
            }
            prompt += ruleBlock.toString();
        }

        String planResponse;
        try {
            planResponse = chatClient.prompt().user(prompt).call().content();
        } catch (Exception e) {
            System.out.println("⚠️ 规划失败，使用兜底工具: " + e.getMessage());
            return PlanResult.ofAIPlan(new ArrayList<>(props.toolPlanner().fallbackTools()), List.of());
        }

        return parseToolNames(planResponse, allTools);
    }

    /** 解析 AI 返回的工具名 → 精确匹配 → 模糊/关键词兜底 → MISSING */
    private PlanResult parseToolNames(String response, ToolCallback[] allTools) {
        if (response == null) {
            return PlanResult.ofAIPlan(new ArrayList<>(props.toolPlanner().fallbackTools()), List.of());
        }

        Set<String> knownNames = collectExisting(allTools);
        Set<String> neededTools = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();

        for (String part : response.split("[,，\n]")) {
            String trimmed = part.trim()
                    .replaceAll("^[\\d]+[.\\)、]\\s*", "")  // 去掉编号
                    .trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.toUpperCase().startsWith("MISSING:")) {
                missing.add(trimmed.substring("MISSING:".length()).trim());
                continue;
            }

            // 精确匹配
            if (knownNames.contains(trimmed)) {
                neededTools.add(trimmed);
                continue;
            }
            // 模糊匹配（AI 可能多打/少打字符）
            String trimmedLower = trimmed.toLowerCase();
            for (String known : knownNames) {
                if (known.equalsIgnoreCase(trimmed)
                        || known.toLowerCase().contains(trimmedLower)) {
                    neededTools.add(known);
                    break;
                }
            }
        }

        // === 关键词兜底：MISSING 或没选到工具时 ===
        if (!missing.isEmpty()) {
            List<String> keywordTools = findToolsByKeywords(missing, allTools);
            if (!keywordTools.isEmpty()) {
                neededTools.addAll(keywordTools);
                System.out.println("🔎 关键词兜底匹配: " + keywordTools.size() + " 个工具");
                missing.clear();
            }
        }

        if (neededTools.isEmpty()) neededTools.addAll(props.toolPlanner().fallbackTools());
        for (String tool : props.toolPlanner().alwaysAppendTools()) {
            neededTools.add(tool);
        }
        System.out.println("📦 AI 选中 " + neededTools.size() + " 个工具" + (missing.isEmpty() ? "" : ", 缺失: " + missing));
        return PlanResult.ofAIPlan(new ArrayList<>(neededTools), missing);
    }

    /**
     * 关键词匹配：在所有工具中搜索名称/描述包含关键词的工具。
     * 用于 Qdrant 分类漏掉工具时的兜底补充。
     *
     * <p>对中文关键词做多级切分：先按分隔符切，再对每个片段用2-gram滑窗匹配，
     * 确保 "设备扫描工具" 能匹配到描述中含 "扫描...设备" 的工具。</p>
     */
    /** 按关键词匹配工具（公开静态，供外部复用） */
    public static List<String> findToolsByKeywords(List<String> missingDescriptions,
                                                    ToolCallback[] allTools) {
        List<String> result = new ArrayList<>();
        for (String missing : missingDescriptions) {
            // 先按分隔符切分
            String[] segments = missing.split("[的与和或及、\\s]+");
            for (ToolCallback tc : allTools) {
                String name = tc.getToolDefinition().name();
                String desc = tc.getToolDefinition().description();
                if (desc == null) desc = "";
                String combined = (name + " " + desc).toLowerCase();
                for (String seg : segments) {
                    String segLower = seg.toLowerCase();
                    // 英文/数字：直接包含匹配
                    if (segLower.matches(".*[a-z0-9].*")) {
                        if (segLower.length() >= 2 && combined.contains(segLower)) {
                            result.add(name);
                            break;
                        }
                    } else {
                        // 中文：用2-gram滑窗匹配，每个字符也要单独匹配
                        if (containsChineseKeywords(combined, segLower)) {
                            result.add(name);
                            break;
                        }
                    }
                }
            }
        }
        return result;
    }

    /** 中文关键词匹配：每个字符必须在 combined 中出现，且至少一个2-gram匹配 */
    private static boolean containsChineseKeywords(String combined, String keyword) {
        if (keyword.isEmpty()) return false;
        // 每个单字符必须出现
        for (int i = 0; i < keyword.length(); i++) {
            if (combined.indexOf(keyword.charAt(i)) < 0) return false;
        }
        // 至少一个2-gram子串在 combined 中出现
        for (int i = 0; i + 2 <= keyword.length(); i++) {
            if (combined.contains(keyword.substring(i, i + 2))) return true;
        }
        // 如果只有1个字符，上面已通过单字符检查
        return keyword.length() == 1;
    }

    private static Set<String> collectExisting(ToolCallback[] allTools) {
        Set<String> existing = new HashSet<>();
        for (ToolCallback tc : allTools) existing.add(tc.getToolDefinition().name());
        return existing;
    }

    public void clearCache() {
        cache.clear();
        System.out.println("🗑️ 工具规划缓存已清除");
    }
}
