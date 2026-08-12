package com.example.desktopbrain.service;

import com.example.desktopbrain.common.AiResponseUtils;
import com.example.desktopbrain.common.PromptLoader;
import com.example.desktopbrain.config.DesktopBrainProperties;
import com.example.desktopbrain.config.SystemEnvironmentService;
import com.example.desktopbrain.memory.graph.RuleNode;
import com.example.desktopbrain.memory.vector.category.ToolCategoryService;
import com.example.desktopbrain.memory.vector.episode.Episode;
import com.example.desktopbrain.memory.vector.episode.EpisodeCacheService;
import com.example.desktopbrain.memory.vector.episode.ToolCallLog;
import com.example.desktopbrain.registry.ToolModel;
import com.example.desktopbrain.registry.ToolRegistry;
import com.example.desktopbrain.config.ModelRouter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;

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

    private static final Logger log = LoggerFactory.getLogger(ToolPlanner.class);

    private final ModelRouter modelRouter;
    private final EpisodeCacheService episodeCacheService;
    private final ToolCategoryService categoryService;
    private final ToolRegistry toolRegistry;
    private final RuleInductionService ruleInductionService;
    private final DesktopBrainProperties props;
    private final PromptLoader promptLoader;
    private final SystemEnvironmentService envService;

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, CacheEntry> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(200, 0.75f, true) {  // access-order LRU
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                    return size() > props.toolPlanner().maxCacheSize();
                }
            });

    public ToolPlanner(ModelRouter modelRouter,
                        EpisodeCacheService episodeCacheService,
                        ToolCategoryService categoryService,
                        ToolRegistry toolRegistry,
                        RuleInductionService ruleInductionService,
                        DesktopBrainProperties props,
                        PromptLoader promptLoader,
                        SystemEnvironmentService envService) {
        this.modelRouter = modelRouter;
        this.episodeCacheService = episodeCacheService;
        this.categoryService = categoryService;
        this.toolRegistry = toolRegistry;
        this.ruleInductionService = ruleInductionService;
        this.props = props;
        this.promptLoader = promptLoader;
        this.envService = envService;
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
            log.info("💾 命中内存缓存（失败计数: {}/{}{}）", entry.failureCount, props.toolPlanner().failureThreshold(),
                    (entry.episodeId != null ? ", episode=" + entry.episodeId.substring(0, 8) + "..." : ""));
            // L1 命中但仍检索失败警告（轻量，不额外 embed）
            List<EpisodeCacheService.FailureSearchResult> warnL1 = episodeCacheService.searchFailurePatterns(userInput, 3);
            return new PlanResult(entry.selectedToolNames, entry.missingDescriptions,
                    true, entry.episodeId, entry.episode, warnL1);
        }

        // ===== Layer 2: Episode 向量检索 + 失败模式并行检索 =====
        Optional<Episode> ep = episodeCacheService.findSimilarEpisode(userInput);
        List<EpisodeCacheService.FailureSearchResult> warnings = episodeCacheService.searchFailurePatterns(userInput, 3);

        if (!warnings.isEmpty()) {
            log.info("⚠️ 发现 {} 条相关失败警告: {}", warnings.size(),
                    warnings.stream().map(w -> w.type() + "(" + String.format("%.0f%%", w.score() * 100) + ")")
                            .reduce((a, b) -> a + ", " + b).orElse(""));
        }

        if (ep.isPresent()) {
            Episode episode = ep.get();
            // 回填内存缓存（带 episode 引用，含 lesson），下次相同输入直接内存命中
            cache.put(key, new CacheEntry(episode.selectedToolNames(), episode.missingDescriptions(),
                    episode.id(), episode));
            log.info("💾 命中 Episode 缓存（稳定度: {}, episode={}...）", String.format("%.2f", episode.computedStability()),
                    episode.id().substring(0, 8));
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
            log.info("ℹ️ 环境问题导致失败，不惩罚计划，保留缓存（分段继续）");
            return false;
        }

        // 计划问题：内存失败计数+1
        if (entry == null) {
            return true;
        }
        entry.failureCount++;
        int threshold = entry.episode != null
                ? effectiveFailureThreshold(entry.episode)
                : props.toolPlanner().failureThreshold();
        if (entry.failureCount >= threshold) {
            cache.remove(key);
            log.info("🗑️ 缓存连续失败 {} 次（阈值{}），已清除旧方案", entry.failureCount, threshold);
            return true;
        }
        log.info("⚠️ 缓存方案失败（计划问题，第 {} 次/{}），保留缓存，本次重新规划", entry.failureCount, threshold);
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
                        old.status(), canScript, old.failedStepIndex(),
                        old.exploreOptimizeCount(), old.exploreDebugCount(),
                        old.explorationType(), old.explorationSummary());
                if (canScript && !wasScriptable) {
                    log.info("🚀 内存缓存 episode 升级为可脚本化（id={}...）", old.id().substring(0, 8));
                }
            }
        }
        episodeCacheService.incrementSuccess(plan.episodeId());
    }

    // ========== 探索优化权重 ==========

    /** 计算某计划的优化权重：被优化越多次越不值（权重 ∝ 1/(1+次数)） */
    public double planOptimizeWeight(Episode episode) {
        return 1.0 / (1.0 + episode.exploreOptimizeCount());
    }

    /** 优化阈值：数据库数据少 → 阈值高（倾向新建扩充），数据多 → 阈值低（倾向优化精炼） */
    public double optimizeThreshold() {
        int dataVolume = episodeCacheService.countActiveEpisodes();
        if (dataVolume < 10) return 0.9;   // 库很小，几乎只新建
        if (dataVolume < 50) return 0.7;
        if (dataVolume < 100) return 0.5;
        return 0.3;                         // 库够大，多优化
    }

    /** 判断是否值得优化：planWeight >= threshold → 优化，否则新建 */
    public boolean isWorthOptimizing(Episode episode) {
        double weight = planOptimizeWeight(episode);
        double threshold = optimizeThreshold();
        boolean worth = weight >= threshold;
        log.info("⚖️ 优化权重判断: weight={} threshold={} optimizeCount={} dataVolume={} → {}",
                String.format("%.2f", weight), String.format("%.2f", threshold),
                episode.exploreOptimizeCount(),
                episodeCacheService.countActiveEpisodes(),
                worth ? "优化" : "新建");
        return worth;
    }

    /** 获取优化后的失败删除门槛：基础3次 + 每优化1次多加0.5次（最少3次） */
    public int effectiveFailureThreshold(Episode episode) {
        return Math.max(props.toolPlanner().failureThreshold(),
                props.toolPlanner().failureThreshold() + (int)(episode.exploreOptimizeCount() * 0.5));
    }

    // ========== 缓存失败/成功回调 ==========
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
                Episode.EpisodeStatus.ACTIVE, false, -1, 0, 0, null, null);
        cache.put(AiResponseUtils.normalizeKey(userInput), new CacheEntry(
                plan.selectedToolNames(), plan.missingDescriptions(), episodeId, memEpisode));
        log.info("💾 已写入内存缓存（{} 个工具，episode={}...{}）", plan.selectedToolNames().size(),
                episodeId.substring(0, 8),
                (successLesson != null ? ", 经验: " + AiResponseUtils.truncate(successLesson, 40) : ""));
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
    public int syncCategories(ToolCallback[] allTools, boolean force) {
        return categoryService.syncCategories(allTools, force);
    }

    /**
     * 内部：AI 规划 — 多轮树形分类交互（严格按 docs/临时文件 设计）。
     *
     * <h3>多轮交互流程</h3>
     * <ol>
     *   <li><b>Round 1 — L1 大类选择</b>：展示 L1 大类（name+desc+toolCount），AI 选 1-3 个</li>
     *   <li><b>Round 2 — L2 子类选择</b>（可选）：若选中 L1 有 L2 子类，展示子类列表，AI 选 1-2 个；
     *       超过 maxCategoryRounds 则不再交互，降级为全量工具前 50 个</li>
     *   <li><b>Round 3 — 工具选择 + 计划</b>：展示收集到的工具列表（name+desc），AI 挑工具 + 制定计划</li>
     * </ol>
     *
     * <h3>防死循环约束（严格按 临时文件 §4）</h3>
     * <ul>
     *   <li>maxCategoryRounds（3）：超过后不再展示分类树，降级为全量工具（前 50 个）</li>
     *   <li>max_selected_categories_per_round：每轮最多 3 个</li>
     *   <li>max_tools_displayed（50）：超出截断并提示用 searchTool</li>
     *   <li>路径记忆（§4.2）：AI 重选已展开的分类 → 拒绝 + 消耗轮次</li>
     *   <li>禁止回退（§4.2）：只向前或 searchTool，不提供"返回上级"</li>
     * </ul>
     *
     * <h3>异常处理（严格按 临时文件 §6）</h3>
     * <ul>
     *   <li>分类名不存在 → 模糊匹配 → 返回候选让 AI 重试（消耗轮次）</li>
     *   <li>AI 始终不选分类 → 允许直接 searchTool/listAllTools 搜</li>
     *   <li>轮次用尽 → 降级为全量工具前 50 个</li>
     * </ul>
     */
    private PlanResult doPlan(String userInput, ToolCallback[] allTools, String failureReason,
                               List<EpisodeCacheService.FailureSearchResult> warnings) {
        // ===== 验证分类数据 =====
        List<ToolCategoryService.CategorySummary> allCategories = categoryService.listAllCategories();

        if (allCategories.isEmpty()) {
            log.info("📦 工具分类为空，触发异步同步...");
            triggerAsyncClassification(allTools);
            return fallbackPlan(List.of("分类数据未就绪"));
        }

        if (toolRegistry.checkAndClearDirty()) {
            log.info("📦 检测到新工具，触发异步重新分类...");
            triggerAsyncClassification(allTools);
        }

        // 分离 L1 大类
        List<ToolCategoryService.CategorySummary> l1Categories = allCategories.stream()
                .filter(c -> c.level() == 1).toList();

        if (l1Categories.isEmpty()) {
            return fallbackPlan(List.of("无L1分类数据"));
        }

        String contextPrefix = envService.getOsInfo();
        Set<String> visitedIds = new HashSet<>();
        List<String> missing = new ArrayList<>();
        Set<String> allToolNames = new LinkedHashSet<>();
        int maxRounds = props.toolPlanner().maxCategoryRounds();
        int roundCount = 0;

        // ===== Round 1: L1 大类选择 =====
        List<String> selectedL1Names = List.of();
        List<ToolCategoryService.CategorySummary> remainingL1 = new ArrayList<>(l1Categories);

        while (roundCount < maxRounds) {
            // 过滤已访问的分类
            List<ToolCategoryService.CategorySummary> availableL1 = remainingL1.stream()
                    .filter(c -> !visitedIds.contains(c.id()))
                    .toList();

            if (availableL1.isEmpty()) {
                log.info("📦 L1 全部已访问，跳过 L1 选择");
                break;
            }

            String l1Prompt = buildL1Prompt(contextPrefix, availableL1, userInput, failureReason,
                    roundCount > 0 ? visitedIds : null);
            l1Prompt = injectWarnings(l1Prompt, warnings);
            l1Prompt = injectRules(l1Prompt, ruleInductionService.getActiveRules());

            String l1Response = callAISafe(l1Prompt, "L1选择-" + (roundCount + 1));
            selectedL1Names = parseCategoryNames(l1Response);
            roundCount++;

            log.info("📦 第{}轮 L1选择: {} 个L1大类 → AI选了 {} 个: {}",
                    roundCount, availableL1.size(), selectedL1Names.size(), selectedL1Names);

            if (selectedL1Names.isEmpty()) {
                // AI 不选分类 → 允许直接 searchTool/listAllTools（§6）
                log.info("📦 AI 未选择分类 → 降级为 searchTool/listAllTools 路径");
                visitedIds.add("__ALL__"); // 标记为全部访问，防止重试
                break;
            }

            // 解析 L1 名称为 CategorySummary（含路径记忆检查）
            boolean allValid = true;
            List<String> validatedNames = new ArrayList<>();
            for (String name : selectedL1Names) {
                var l1Opt = categoryService.findByName(name);
                if (l1Opt.isPresent()) {
                    String id = l1Opt.get().id();
                    if (visitedIds.contains(id)) {
                        // §4.2：路径记忆 → 拒绝重复选择
                        log.warn("⛔ 路径记忆拒绝: L1「{}」已展开过，消耗本轮", name);
                        allValid = false;
                        continue;
                    }
                    visitedIds.add(id);
                    validatedNames.add(name);
                } else {
                    // §6：分类名不存在 → 模糊匹配 → 候选重试
                    List<String> candidates = fuzzyMatchCategoryNames(name, availableL1);
                    if (!candidates.isEmpty()) {
                        if (roundCount < maxRounds) {
                            log.info("❓ L1「{}」不存在，候选: {} → 重新确认（消耗轮次）", name, candidates);
                            String retryPrompt = buildRetryPrompt(contextPrefix,
                                    "分类「" + name + "」不存在，以下是最接近的候选", candidates, userInput);
                            String retryResponse = callAISafe(retryPrompt, "L1重试-" + name);
                            List<String> retryNames = parseCategoryNames(retryResponse);
                            roundCount++;
                            for (String rn : retryNames) {
                                var ro = categoryService.findByName(rn);
                                if (ro.isPresent() && !visitedIds.contains(ro.get().id())) {
                                    visitedIds.add(ro.get().id());
                                    validatedNames.add(rn);
                                }
                            }
                        } else {
                            log.info("⚠️ 轮次已满，分类「{}」不存在 → 降级", name);
                            missing.add("MISSING: 分类「" + name + "」不存在");
                        }
                    } else {
                        missing.add("MISSING: 分类「" + name + "」不存在，无候选");
                    }
                    allValid = false;
                }
            }
            selectedL1Names = validatedNames;

            if (allValid || !selectedL1Names.isEmpty()) break;
            // 全部无效且未消耗太多轮次则继续循环
            if (roundCount >= maxRounds) break;
            // 重建 availableL1（已过滤 visitedIds）
            remainingL1 = availableL1;
        }

        if (roundCount >= maxRounds && selectedL1Names.isEmpty()) {
            // §6：轮次用尽 → 降级为全量工具前 50 个
            log.info("📦 轮次用尽 ({} 轮) → 降级为全量工具前 50 个", maxRounds);
            Set<String> existingNames = collectExisting(allTools);
            int count = 0;
            for (ToolCallback tc : allTools) {
                if (count >= 50) break;
                allToolNames.add(tc.getToolDefinition().name());
                count++;
            }
            return doFinalRound(contextPrefix, allTools, userInput, failureReason, warnings,
                    new ArrayList<>(allToolNames), missing);
        }

        if (selectedL1Names.isEmpty()) {
            // AI 始终不选分类 → searchTool/listAllTools 路径（§6）
            // 限制工具列表最大长度 50，超长截断并提示
            log.info("📦 AI 始终不选分类 → searchTool/listAllTools 路径，传前50个工具");
            return doFinalRound(contextPrefix, allTools, userInput, failureReason, warnings,
                    getFirstNToolNames(allTools, 50), missing);
        }

        // ===== Round 2: L2 子类选择 =====
        for (String name : selectedL1Names) {
            var l1Opt = categoryService.findByName(name);
            if (l1Opt.isEmpty()) continue;
            var l1 = l1Opt.get();

            List<ToolCategoryService.CategorySummary> l2Children = categoryService.getChildren(l1.id());
            // 过滤已访问的 L2
            l2Children = l2Children.stream().filter(c -> !visitedIds.contains(c.id())).toList();

            if (l2Children.isEmpty()) {
                // 无 L2（或全部已访问）→ 直接收集工具
                allToolNames.addAll(l1.toolNames());
                log.info("📦 L1「{}」无子类（或已全展开）→ 直接收集 {} 个工具", l1.name(), l1.toolNames().size());
            } else {
                if (roundCount >= maxRounds) {
                    // §6：轮次用尽 → 全量工具前 50 个
                    log.info("📦 已达最大轮次 {} → 降级为全量工具前 50 个", maxRounds);
                    Set<String> existingNames = collectExisting(allTools);
                    int count = 0;
                    allToolNames.clear();
                    for (ToolCallback tc : allTools) {
                        if (count >= 50) break;
                        allToolNames.add(tc.getToolDefinition().name());
                        count++;
                    }
                    break;
                } else {
                    // L2 选择轮
                    String l2Prompt = buildL2Prompt(contextPrefix, l2Children, l1.name(), userInput);
                    String l2Response = callAISafe(l2Prompt, "L2选择-" + l1.name());
                    List<String> selectedL2Names = parseCategoryNames(l2Response);
                    roundCount++;

                    log.info("📦 第{}轮 L2选择: L1「{}」下 {} 个子类 → AI选了 {} 个",
                            roundCount, l1.name(), l2Children.size(), selectedL2Names.size());

                    if (selectedL2Names.isEmpty()) {
                        // AI 没选 → §4.2 禁止抱怨，全量收集
                        for (var l2 : l2Children) {
                            allToolNames.addAll(l2.toolNames());
                            visitedIds.add(l2.id());
                        }
                    } else {
                        for (String l2Name : selectedL2Names) {
                            var l2Opt = findCategory(l2Name, l2Children);
                            if (l2Opt.isPresent()) {
                                if (visitedIds.contains(l2Opt.get().id())) {
                                    // §4.2：路径记忆拒绝
                                    log.warn("⛔ 路径记忆拒绝: L2「{}」已展开过", l2Name);
                                    continue;
                                }
                                allToolNames.addAll(l2Opt.get().toolNames());
                                visitedIds.add(l2Opt.get().id());
                            } else {
                                // §6：分类名不存在 → 模糊匹配 → 候选重试
                                List<String> candidates = fuzzyMatchCategoryNames(l2Name, l2Children);
                                if (!candidates.isEmpty() && roundCount < maxRounds) {
                                    log.info("❓ L2「{}」不存在，候选: {} → 重新确认（消耗轮次）", l2Name, candidates);
                                    String retryPrompt = buildRetryPrompt(contextPrefix,
                                            "子类「" + l2Name + "」不存在，以下是最接近的候选", candidates, userInput);
                                    String retryResponse = callAISafe(retryPrompt, "L2重试-" + l2Name);
                                    List<String> retryNames = parseCategoryNames(retryResponse);
                                    roundCount++;
                                    for (String rn : retryNames) {
                                        var ro = findCategory(rn, l2Children);
                                        if (ro.isPresent() && !visitedIds.contains(ro.get().id())) {
                                            allToolNames.addAll(ro.get().toolNames());
                                            visitedIds.add(ro.get().id());
                                        }
                                    }
                                } else {
                                    missing.add("MISSING: 子类「" + l2Name + "」不存在");
                                }
                            }
                        }
                    }
                }
            }
        }

        if (allToolNames.isEmpty()) {
            return fallbackPlan(missing);
        }

        return doFinalRound(contextPrefix, allTools, userInput, failureReason, warnings,
                new ArrayList<>(allToolNames), missing);
    }

    /** 执行最终轮：工具选择 + 计划制定 */
    private PlanResult doFinalRound(String contextPrefix, ToolCallback[] allTools,
                                     String userInput, String failureReason,
                                     List<EpisodeCacheService.FailureSearchResult> warnings,
                                     List<String> toolNames, List<String> missing) {
        // 构建工具列表文本
        Set<String> existingNames = collectExisting(allTools);
        StringBuilder toolList = new StringBuilder();
        int toolIdx = 0;
        for (String name : toolNames) {
            if (!existingNames.contains(name)) continue;
            toolIdx++;
            String desc = getToolDesc(name, allTools);
            toolList.append(toolIdx).append(". ").append(name);
            if (desc != null && !desc.isBlank()) {
                toolList.append(" - ").append(desc);
            }
            toolList.append("\n");
        }

        // §4.1：超出截断
        int maxToolsDisplayed = 50;
        String toolListStr = toolList.toString();
        if (toolIdx > maxToolsDisplayed) {
            String[] lines = toolListStr.split("\n");
            StringBuilder truncated = new StringBuilder();
            for (int i = 0; i < Math.min(lines.length, maxToolsDisplayed); i++) {
                truncated.append(lines[i]).append("\n");
            }
            truncated.append("...（共 ").append(toolIdx).append(" 个工具，已截断，请用 searchTool 搜索更多）\n");
            toolListStr = truncated.toString();
        }

        String finalPrompt;
        if (failureReason != null) {
            finalPrompt = promptLoader.getReplanning().formatted(contextPrefix, toolListStr, userInput, failureReason);
        } else {
            finalPrompt = promptLoader.getPlanning().formatted(contextPrefix, toolListStr, userInput);
        }
        finalPrompt = injectWarnings(finalPrompt, warnings);
        finalPrompt = injectRules(finalPrompt, ruleInductionService.getActiveRules());

        String finalResponse = callAISafe(finalPrompt, "最终工具选择");
        return parseFinalResponse(finalResponse, allTools, missing);
    }

    // ========== 多轮交互辅助方法 ==========

    /** 构建 L1 大类选择 prompt */
    private String buildL1Prompt(String contextPrefix, List<ToolCategoryService.CategorySummary> l1Categories,
                                  String userInput, String failureReason, Set<String> visitedIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个工具规划器。当前你处于「分类浏览模式」。\n");
        sb.append(contextPrefix);
        sb.append("\n\n根据用户请求，从以下L1大类中选择最相关的 1-3 个分类。\n");
        if (visitedIds != null && !visitedIds.isEmpty()) {
            sb.append("注意：以下分类已展开过，请勿重复选择: ").append(visitedIds.size()).append(" 个\n");
        }
        sb.append("\n--- L1 大类 ---\n");
        int idx = 1;
        for (var c : l1Categories) {
            sb.append(String.format("%d. 【%s】(%d个工具) - %s\n", idx++, c.name(), c.toolCount(), c.description()));
        }
        sb.append("\n用户请求: ").append(userInput);
        if (failureReason != null) {
            sb.append("\n上次失败原因: ").append(failureReason);
        }
        sb.append("\n\n规则:\n");
        sb.append("- 如果展示的是子分类，请继续选择；如果是工具列表，请从中挑选必要的工具。\n");
        sb.append("- 不要尝试「返回上一级」，只能向前或搜索。\n");
        sb.append("- 如果对当前工具列表不满意，请使用 searchTool 搜索。\n");
        sb.append("- 如果搜索无结果，请使用 listAllTools 查看全部工具名（仅名称），再用搜索定位。\n");
        sb.append("\n返回严格JSON（只返回JSON，不要其他内容）:\n");
        sb.append("{\"categories\": [\"大类名1\", \"大类名2\"], \"reason\": \"选择原因\"}");
        return sb.toString();
    }

    /** 构建 L2 子类选择 prompt */
    private String buildL2Prompt(String contextPrefix, List<ToolCategoryService.CategorySummary> l2Children,
                                  String l1Name, String userInput) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个工具规划器。当前你处于「分类浏览模式」。\n");
        sb.append(contextPrefix);
        sb.append("\n\n分类「").append(l1Name).append("」下有以下子类，请选择 1-2 个最相关的。\n\n");
        sb.append("--- 子类 ---\n");
        int idx = 1;
        for (var c : l2Children) {
            sb.append(String.format("%d. %s - %s (%d个工具)\n", idx++, c.name(), c.description(), c.toolCount()));
        }
        sb.append("\n用户请求: ").append(userInput);
        sb.append("\n\n规则:\n");
        sb.append("- 不要尝试「返回上一级」，只能向前或搜索。\n");
        sb.append("- 如果对当前分类不满意，使用 searchTool 搜索跨分类工具。\n");
        sb.append("- 如果搜索无结果，请使用 listAllTools 查看全部工具名（仅名称）。\n");
        sb.append("\n返回严格JSON（只返回JSON，不要其他内容）:\n");
        sb.append("{\"categories\": [\"子类名1\", \"子类名2\"], \"reason\": \"选择原因\"}");
        return sb.toString();
    }

    /** 构建分类名不存在时的重试 prompt */
    private String buildRetryPrompt(String contextPrefix,
                                     String hint, List<String> candidates, String userInput) {
        StringBuilder sb = new StringBuilder(contextPrefix);
        sb.append("\n\n").append(hint).append("：\n");
        for (String c : candidates) {
            sb.append("- ").append(c).append("\n");
        }
        sb.append("\n请从候选中重新选择（或选其他分类）。\n");
        sb.append("用户请求: ").append(userInput);
        sb.append("\n\n返回严格JSON:\n{\"categories\": [\"候选分类名\"], \"reason\": \"选择原因\"}");
        return sb.toString();
    }

    /** 模糊匹配：从候选列表中找最接近的 1-2 个分类名 */
    private List<String> fuzzyMatchCategoryNames(String input,
            List<ToolCategoryService.CategorySummary> candidates) {
        String key = input.toLowerCase().trim();
        List<String> results = new ArrayList<>();
        for (var c : candidates) {
            String name = c.name().toLowerCase().trim();
            // 包含匹配
            if (name.contains(key) || key.contains(name)) {
                results.add(c.name());
            }
            // 字符重叠度
            else {
                int overlap = 0;
                for (int i = 0; i < key.length(); i++) {
                    if (name.indexOf(key.charAt(i)) >= 0) overlap++;
                }
                if (overlap >= key.length() / 2 && overlap >= 2) {
                    results.add(c.name());
                }
            }
        }
        return results.size() > 2 ? results.subList(0, 2) : results;
    }

    /** 从 allTools 中取前 N 个工具名 */
    private List<String> getFirstNToolNames(ToolCallback[] allTools, int n) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < Math.min(allTools.length, n); i++) {
            names.add(allTools[i].getToolDefinition().name());
        }
        return names;
    }

    /** 解析分类名 JSON：{"categories": [...]} */
    @SuppressWarnings("unchecked")
    private List<String> parseCategoryNames(String response) {
        if (response == null || response.isBlank()) return List.of();
        // §3.3：AI 可能输出工具调用指令而非 JSON → 无法解析则降级
        String json = AiResponseUtils.stripMarkdownCodeBlock(response);
        if (!json.trim().startsWith("{")) {
            log.warn("⚠️ AI 返回非JSON响应（可能是工具调用指令），降级为空选择: {}", AiResponseUtils.truncate(response, 80));
            return List.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            List<String> cats = (List<String>) parsed.getOrDefault("categories", List.of());
            String reason = String.valueOf(parsed.getOrDefault("reason", ""));
            if (!"null".equals(reason) && !reason.isBlank()) {
                log.info("📝 AI选择原因: {}", AiResponseUtils.truncate(reason, 100));
            }
            // §4.1：硬截断，每轮最多3个
            if (cats.size() > 3) {
                log.warn("⚠️ AI选了 {} 个分类，超过上限3，截断", cats.size());
                cats = cats.subList(0, 3);
            }
            return cats;
        } catch (Exception e) {
            log.warn("⚠️ 分类名解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 按名称查找分类（模糊匹配） */
    private Optional<ToolCategoryService.CategorySummary> findCategory(String name,
            List<ToolCategoryService.CategorySummary> candidates) {
        String key = name.toLowerCase().trim();
        return candidates.stream()
                .filter(c -> c.name().toLowerCase().trim().equals(key)
                        || c.name().contains(name) || name.contains(c.name()))
                .findFirst();
    }

    /** 安全调用 AI，异常时返回空字符串 */
    private String callAISafe(String prompt, String stage) {
        try {
            return modelRouter.chat().prompt().user(prompt).call().content();
        } catch (Exception e) {
            log.warn("⚠️ AI调用失败 ({}): {}", stage, e.getMessage());
            return "";
        }
    }

    /** 获取工具简短描述 */
    private String getToolDesc(String name, ToolCallback[] allTools) {
        for (ToolCallback tc : allTools) {
            if (tc.getToolDefinition().name().equals(name)) {
                return AiResponseUtils.truncateNotNull(tc.getToolDefinition().description(), 80);
            }
        }
        return "";
    }

    /** 注入历史失败警告 */
    private String injectWarnings(String prompt, List<EpisodeCacheService.FailureSearchResult> warnings) {
        if (warnings == null || warnings.isEmpty()) return prompt;
        StringBuilder warnBlock = new StringBuilder("\n\n--- ⚠️ 历史失败警告（请避免以下做法） ---\n");
        for (int i = 0; i < warnings.size(); i++) {
            var w = warnings.get(i);
            warnBlock.append((i + 1)).append(". ").append(w.type())
                    .append("（失败 ").append(w.count()).append(" 次, 相似度 ")
                    .append(String.format("%.0f%%", w.score() * 100)).append("）\n")
                    .append("   描述: ").append(w.description()).append("\n");
            if (w.mitigation() != null && !w.mitigation().isBlank()) {
                warnBlock.append("   建议: ").append(w.mitigation()).append("\n");
            }
        }
        return prompt + warnBlock;
    }

    /** 注入归纳的通用规则 */
    private String injectRules(String prompt, List<RuleNode> activeRules) {
        if (activeRules == null || activeRules.isEmpty()) return prompt;
        StringBuilder ruleBlock = new StringBuilder("\n\n--- 📜 已知规则（请遵循） ---\n");
        for (int i = 0; i < Math.min(activeRules.size(), 10); i++) {
            var r = activeRules.get(i);
            ruleBlock.append((i + 1)).append(". ").append(r.getSummary())
                    .append("（置信度: ").append(String.format("%.0f%%", r.getConfidence() * 100)).append("）\n");
        }
        return prompt + ruleBlock;
    }

    /** 降级兜底：fallbackTools + searchTool + listAllTools */
    private PlanResult fallbackPlan(List<String> missing) {
        List<String> fallback = new ArrayList<>(props.toolPlanner().fallbackTools());
        if (!fallback.contains("searchTool")) fallback.add("searchTool");
        if (!fallback.contains("listAllTools")) fallback.add("listAllTools");
        return PlanResult.ofAIPlan(fallback, missing);
    }

    /**
     * 解析最终轮 AI 返回的工具+计划 JSON。
     *
     * <p>JSON 格式：{@code {"tools": ["tool1"], "plan": "...", "missing": [...]}}</p>
     */
    private PlanResult parseFinalResponse(String response, ToolCallback[] allTools, List<String> existingMissing) {
        List<String> missing = new ArrayList<>(existingMissing);

        if (response == null || response.isBlank()) {
            return fallbackPlan(List.of("AI返回为空"));
        }

        // §3.3：AI 可能输出工具调用指令而非 JSON → 降级
        String json = AiResponseUtils.stripMarkdownCodeBlock(response);
        if (!json.trim().startsWith("{")) {
            log.warn("⚠️ AI 返回非JSON响应（可能是工具调用指令），降级: {}", AiResponseUtils.truncate(response, 80));
            return fallbackPlan(List.of("AI返回非JSON格式"));
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});

            @SuppressWarnings("unchecked")
            List<String> selectedTools = (List<String>) parsed.getOrDefault("tools", List.of());
            String plan = String.valueOf(parsed.getOrDefault("plan", ""));
            @SuppressWarnings("unchecked")
            List<String> respMissing = (List<String>) parsed.getOrDefault("missing", List.of());
            missing.addAll(respMissing);

            Set<String> existingNames = collectExisting(allTools);
            Set<String> resolvedTools = new LinkedHashSet<>();

            // 验证 AI 选中的工具
            List<String> validTools = new ArrayList<>();
            List<String> hallucinatedTools = new ArrayList<>();
            for (String t : selectedTools) {
                if (existingNames.contains(t)) {
                    validTools.add(t);
                } else {
                    hallucinatedTools.add(t);
                }
            }
            resolvedTools.addAll(validTools);

            if (!hallucinatedTools.isEmpty()) {
                log.warn("⚠️ AI 脑补了不存在的工具: {}，已过滤", hallucinatedTools);
                for (String ht : hallucinatedTools) {
                    missing.add("MISSING: 工具「" + ht + "」不存在，请用 searchTool 搜索替代");
                }
            }

            // 始终加入 searchTool + listAllTools
            resolvedTools.add("searchTool");
            resolvedTools.add("listAllTools");
            resolvedTools.removeIf(t -> !existingNames.contains(t));

            // 加入 alwaysAppendTools
            for (String tool : props.toolPlanner().alwaysAppendTools()) {
                if (existingNames.contains(tool)) resolvedTools.add(tool);
            }

            if (resolvedTools.isEmpty()) {
                return fallbackPlan(missing);
            }

            List<String> finalTools = new ArrayList<>(resolvedTools);
            if (!"null".equals(plan) && !plan.isBlank()) {
                log.info("📋 AI 计划: {}", AiResponseUtils.truncate(plan, 200));
            }
            log.info("📦 最终选择 → {} 个工具 ({} 个缺失)", finalTools.size(), missing.size());
            return PlanResult.ofAIPlan(finalTools, missing);

        } catch (Exception e) {
            log.warn("⚠️ AI 返回的 JSON 解析失败: {}，使用兜底工具", e.getMessage());
            return fallbackPlan(List.of("JSON解析失败: " + e.getMessage()));
        }
    }

    /** 按关键词匹配工具（公开静态，供外部复用，保留原逻辑） */
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

    /**
     * 宽松兜底匹配：当严格 findToolsByKeywords 返回空时调用。
     * 按2/3-gram拆分描述，匹配具体关键词（过滤掉通用词如"工具""操作"）。
     * 用于避免"浏览器控制工具"因"控""制"字不在描述中而导致误判为缺失。
     */
    public static List<String> findToolsByDescriptionFallback(String missingDesc, ToolCallback[] allTools) {
        List<String> result = new ArrayList<>();
        if (missingDesc == null || missingDesc.isBlank()) return result;

        List<String> grams = extractMeaningfulGrams(missingDesc);
        if (grams.isEmpty()) {
            log.info("🔎 宽松兜底-全是通用词: '{}', 不匹配", missingDesc);
            return result;
        }

        log.info("🔎 宽松兜底-关键词: {} → {}", missingDesc, grams);

        for (ToolCallback tc : allTools) {
            String name = tc.getToolDefinition().name();
            String desc = tc.getToolDefinition().description();
            String combined = (name + " " + (desc != null ? desc : "")).toLowerCase();

            int matched = 0;
            for (String gram : grams) {
                if (combined.contains(gram)) {
                    matched++;
                }
            }
            if (matched >= Math.min(2, grams.size()) || (matched > 0 && matched * 2 >= grams.size())) {
                log.info("    ✅ 命中工具: {} ({}个关键词匹配)", name, matched);
                result.add(name);
            }
        }

        if (result.isEmpty()) {
            log.info("🔎 宽松兜底-未命中: '{}' (已查 {} 个工具)", missingDesc, allTools.length);
        } else {
            log.info("🔎 宽松兜底-命中 {} 个: {}", result.size(), result);
        }
        return result;
    }

    /** 通用词（2-gram）——在几乎所有工具描述里都会出现，没有区分度 */
    private static final Set<String> GENERIC_GRAMS = Set.of(
            "工具", "操作", "获取", "使用", "执行", "设置", "处理", "管理",
            "功能", "信息", "内容", "文件", "数据", "配置", "控制", "支持",
            "提供", "显示", "指定", "一个", "所有", "可以", "需要", "是否",
            "系统", "当前", "服务", "返回", "用于", "包含", "实现", "检查",
            "输入", "输出", "类型", "名称", "路径", "方法", "参数", "结果"
    );

    /** 提取2/3-gram并过滤通用词 */
    private static List<String> extractMeaningfulGrams(String desc) {
        List<String> grams = new ArrayList<>();
        String lower = desc.toLowerCase();
        // 2-gram
        for (int i = 0; i + 2 <= lower.length(); i++) {
            String g = lower.substring(i, i + 2);
            if (!GENERIC_GRAMS.contains(g)) {
                grams.add(g);
            }
        }
        // 3-gram（更具体，直接加入不检查通用词）
        for (int i = 0; i + 3 <= lower.length(); i++) {
            grams.add(lower.substring(i, i + 3));
        }
        return grams;
    }

    private static Set<String> collectExisting(ToolCallback[] allTools) {
        Set<String> existing = new HashSet<>();
        for (ToolCallback tc : allTools) existing.add(tc.getToolDefinition().name());
        return existing;
    }

    public void clearCache() {
        cache.clear();
        log.info("🗑️ 工具规划缓存已清除");
    }

    /** 异步触发分类同步（不阻塞规划） */
    private void triggerAsyncClassification(ToolCallback[] allTools) {
        CompletableFuture.runAsync(() -> {
            try {
                int n = categoryService.syncCategories(allTools, true);
                log.info("📁 异步分类完成: {} 类", n);
            } catch (Exception e) {
                log.warn("⚠️ 异步分类失败: {}", e.getMessage());
            }
        });
    }
}
