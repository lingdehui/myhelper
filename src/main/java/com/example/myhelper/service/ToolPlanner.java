package com.example.myhelper.service;

import com.example.myhelper.common.AiResponseUtils;
import com.example.myhelper.common.PromptLoader;
import com.example.myhelper.config.MyHelperProperties;
import com.example.myhelper.config.SystemEnvironmentService;
import com.example.myhelper.memory.graph.RuleNode;
import com.example.myhelper.memory.unit.Unit;
import com.example.myhelper.memory.unit.UnitKind;
import com.example.myhelper.memory.unit.ExplorationRecord;
import com.example.myhelper.memory.unit.FailureCause;
import com.example.myhelper.memory.unit.UnitStore;
import com.example.myhelper.memory.unit.UnitFailureService;
import com.example.myhelper.memory.vector.category.ToolCategoryService;
import com.example.myhelper.memory.vector.episode.ToolCallLog;
import com.example.myhelper.registry.ToolModel;
import com.example.myhelper.registry.ToolRegistry;
import com.example.myhelper.config.ModelRouter;
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
 * 三阶段工具规划器（升级版：三层缓存 + Unit 图化经验学习）。
 *
 * <p>在原有内存缓存基础上增加 Unit 语义检索层（Neo4j + Qdrant unit-registry），
 * 实现跨重启的语义级方案复用。</p>
 *
 * <h3>三层缓存查询逻辑</h3>
 * <pre>
 * plan(userInput, allTools):
 *   [Layer 1] 内存缓存（ConcurrentHashMap，key=AiResponseUtils.normalizeKey(userInput)）
 *             命中 → 返回 PlanResult(fromCache=true, unitId=entry.unitId)
 *             不查 Qdrant，避免重复 embed（200-500ms）
 *   [Layer 2] Unit 语义检索（Qdrant unit-registry collection）
 *             embed(userInput) → unitStore.findSimilar → 过滤 unitKind=PLAN_STEP
 *             命中 → 回填内存缓存 → 返回 PlanResult(fromCache=true, unitId=unit.unitId)
 *   [Layer 3] AI 规划（现有 doPlan 逻辑）
 *             返回 PlanResult(fromCache=false, unitId=null)
 * </pre>
 *
 * <h3>淘汰策略（两层独立）</h3>
 * <ul>
 *   <li>内存缓存：连续失败 3 次 → 清除（重启后也清空）</li>
 *   <li>Unit：PLAN 失败 3 次 → ARCHIVED（不检索但数据保留，跨重启持久化，见 UnitFailureService）</li>
 * </ul>
 *
 * <h3>反馈学习</h3>
 * <ul>
 *   <li>命中缓存+成功 → 内存重置失败计数 + unitStore.incrementSuccess（successCount+1，脚本化升级判定）</li>
 *   <li>命中缓存+失败 → 内存失败计数+1 + unitFailureService.recordFailure（PLAN 达阈值归档）</li>
 *   <li>首次规划+成功 → 由 UnitSedimentationService 沉淀 PLAN_STEP Unit（图化记忆，非本类职责）</li>
 * </ul>
 */
@Component
public class ToolPlanner {

    private static final Logger log = LoggerFactory.getLogger(ToolPlanner.class);

    private final ModelRouter modelRouter;
    private final UnitStore unitStore;
    private final UnitFailureService unitFailureService;
    private final ToolCategoryService categoryService;
    private final ToolRegistry toolRegistry;
    private final RuleInductionService ruleInductionService;
    private final MyHelperProperties props;
    private final PromptLoader promptLoader;
    private final SystemEnvironmentService envService;

    /** 缓存条目：方案 + unitId + Unit引用 + 连续失败次数 */
    private static class CacheEntry {
        final List<String> selectedToolNames;
        final List<String> missingDescriptions;
        final String unitId;  // 关联的 Unit id（可能为 null）
        volatile Unit unit;   // Unit 引用（含 script/notes，可能为 null）
        volatile int failureCount;     // volatile：onCacheHitSuccess 和 onCacheHitFailure 可能并发
        CacheEntry(List<String> selectedToolNames, List<String> missingDescriptions,
                   String unitId, Unit unit) {
            this.selectedToolNames = selectedToolNames;
            this.missingDescriptions = missingDescriptions;
            this.unitId = unitId;
            this.unit = unit;
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
                        UnitStore unitStore,
                        UnitFailureService unitFailureService,
                        ToolCategoryService categoryService,
                        ToolRegistry toolRegistry,
                        RuleInductionService ruleInductionService,
                        MyHelperProperties props,
                        PromptLoader promptLoader,
                        SystemEnvironmentService envService) {
        this.modelRouter = modelRouter;
        this.unitStore = unitStore;
        this.unitFailureService = unitFailureService;
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
     * @param fromCache           是否来自缓存（内存或 Unit）
     * @param unitId              关联的 Unit id（内存命中或 Unit 命中时非 null；AI 规划时为 null）
     * @param unit                命中的完整 Unit（含 script/notes）；
     *                            AI 规划时为 null。调用方可据此增强 prompt（参考计划+经验提示）
     * @param failureWarnings     历史失败警告列表（向量检索匹配的 FailurePattern），用于注入 AI prompt 避坑
     */
    public record PlanResult(
            List<String> selectedToolNames,
            List<String> missingDescriptions,
            boolean fromCache,
            String unitId,
            Unit unit,
            List<FailureCause> failureWarnings,
            boolean allowListAllTools
    ) {
        public PlanResult {
            // 确保列表可变：TurnProcessor 执行阶段会对 selectedToolNames.addAll / missingDescriptions.retainAll
            selectedToolNames = selectedToolNames == null ? new ArrayList<>() : new ArrayList<>(selectedToolNames);
            missingDescriptions = missingDescriptions == null ? new ArrayList<>() : new ArrayList<>(missingDescriptions);
        }

        /** AI 规划的便捷工厂（unit=null, failureWarnings=empty） */
        public static PlanResult ofAIPlan(List<String> tools, List<String> missing) {
            return new PlanResult(tools, missing, false, null, null, List.of(), false);
        }
        /** 带失败警告的 AI 规划工厂 */
        public static PlanResult ofAIPlan(List<String> tools, List<String> missing,
                                           List<FailureCause> warnings) {
            return new PlanResult(tools, missing, false, null, null, warnings, false);
        }
        /** 标记本计划已降级为全量工具兜底，执行阶段允许调用 listAllTools 翻页查看全量 */
        public PlanResult withListAllToolsAllowed() {
            return new PlanResult(selectedToolNames, missingDescriptions, fromCache,
                    unitId, unit, failureWarnings, true);
        }
    }

    /**
     * 正常规划入口：三层缓存查询（内存 → Unit → AI 多轮规划）。
     *
     * <h3>查询链路</h3>
     * <ol>
     *   <li><b>Layer 1 — 内存缓存</b>：ConcurrentHashMap 精确匹配（normalizeKey 处理中文标点），
     *       命中直接返回，附带 unit 引用（含 script/notes）</li>
     *   <li><b>Layer 2 — Unit 语义检索</b>：Qdrant unit-registry 语义相似搜索，
     *       过滤 unitKind=PLAN_STEP，命中回填 L1 内存缓存，同时并行检索失败原因（searchFailureCauses）</li>
     *   <li><b>Layer 3 — AI 多轮规划</b>：调用 {@link #doPlan(String, ToolCallback[], String, List)}，
     *       走完整的 3 阶段树形分类→工具选择流程</li>
     * </ol>
     *
     * <p>缓存命中时 PlanResult.fromCache=true，PlanResult.unit 非 null，
     * 调用方可据此判断可用性 + 带参考计划执行。</p>
     *
     * @param userInput 用户输入文本
     * @param allTools  全部可用工具（147 个，MCP + Java + Generated）
     * @return PlanResult（fromCache 标识命中状态，unit 含历史经验）
     */
    public PlanResult plan(String userInput, ToolCallback[] allTools) {
        String key = AiResponseUtils.normalizeKey(userInput);
        String retrievalKey = extractRetrievalKey(userInput);

        // ===== Layer 1: 内存缓存（精确匹配，最快）=====
        CacheEntry entry = cache.get(key);
        if (entry != null) {
            log.info("💾 命中内存缓存（失败计数: {}/{}{}）", entry.failureCount, props.toolPlanner().failureThreshold(),
                    (entry.unitId != null ? ", unit=" + entry.unitId.substring(0, 8) + "..." : ""));
            // L1 命中但仍检索失败警告（轻量，不额外 embed）
            List<FailureCause> warnL1 = unitFailureService.searchFailureCauses(retrievalKey, 3);
            return new PlanResult(entry.selectedToolNames, entry.missingDescriptions,
                    true, entry.unitId, entry.unit, warnL1, false);
        }

        // ===== Layer 2: Unit 语义检索（unit-registry）+ 失败模式并行检索 =====
        List<FailureCause> warnings = unitFailureService.searchFailureCauses(retrievalKey, 3);
        Optional<Unit> matchedUnit = unitStore.findSimilar(retrievalKey, 1).stream()
                .filter(u -> u.unitKind() == UnitKind.PLAN_STEP)
                .findFirst();

        if (!warnings.isEmpty()) {
            log.info("⚠️ 发现 {} 条相关失败原因: {}", warnings.size(),
                    warnings.stream().map(w -> "[" + w.category() + "] " + w.reason())
                            .reduce((a, b) -> a + ", " + b).orElse(""));
        }

        if (matchedUnit.isPresent()) {
            Unit unit = matchedUnit.get();
            List<String> toolNames = unit.script().stream().map(ToolCallLog::toolName).toList();
            // 回填内存缓存（带 Unit 引用，含 script/notes），下次相同输入直接内存命中
            cache.put(key, new CacheEntry(toolNames, List.of(), unit.unitId(), unit));
            log.info("💾 命中 Unit 缓存（稳定度: {}, unit={}...）", String.format("%.2f", unit.computedStability()),
                    unit.unitId().substring(0, 8));
            return new PlanResult(toolNames, List.of(), true, unit.unitId(), unit, warnings, false);
        }

        // ===== Layer 3: AI 规划 =====
        return doPlan(userInput, allTools, null, warnings);
    }

    /**
     * 提取检索用的简洁 query。
     *
     * <p>探索模式的 userInput 是完整 prompt（学习方法 + 工具清单 + 学习目标 + 期望成果 + 成功标准），
     * 直接用整段做向量检索会被工具清单噪声污染。这里只取「学习目标」片段，
     * 普通用户输入（无「学习目标」标记）原样返回。</p>
     */
    private String extractRetrievalKey(String userInput) {
        return AiResponseUtils.extractLearningGoal(userInput);
    }

    /**
     * 缓存命中但失败时调用（带 AI 归因结果）。
     *
     * <p>用户核心逻辑："按问题判断是否是计划问题→是计划问题→失败数+1→不是计划问题→分段继续"。</p>
     *
     * <p>归因结果决定惩罚策略：
     * <ul>
     *   <li>isPlanIssue=true（计划问题）：内存失败计数+1（达阈值清除）+ Unit PLAN 失败+1（达阈值归档）</li>
     *   <li>isPlanIssue=false（环境问题）：不惩罚计划，只写 Unit notes，保留缓存（下次还能用）</li>
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

        // 记录 Unit 失败（PLAN 原因才计数，ENVIRONMENT 只写 notes；达阈值归档 + DISABLES）
        unitFailureService.recordFailure(plan.unitId(), failureLesson, isPlanIssue, null);

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
        int threshold = props.toolPlanner().failureThreshold();
        if (entry.failureCount >= threshold) {
            cache.remove(key);
            log.info("🗑️ 缓存连续失败 {} 次（阈值{}），已清除旧方案", entry.failureCount, threshold);
            return true;
        }
        log.info("⚠️ 缓存方案失败（计划问题，第 {} 次/{}），保留缓存，本次重新规划", entry.failureCount, threshold);
        return false;
    }

    /**
     * 缓存命中且成功：重置内存失败计数 + Unit 成功计数+1（含脚本化升级判定）。
     *
     * <p>Unit 是 immutable record，脚本化升级由 {@link UnitStore#incrementSuccess} 内部
     * 走 {@code tryUpgradeScriptable} 判定（successCount≥5 且 stability>0.9）。</p>
     *
     * @param userInput 用户原话（用于查内存缓存 key）
     * @param plan      当前命中的 PlanResult（用 plan.unitId() 调 Unit 成功计数）
     */
    public void onCacheHitSuccess(String userInput, PlanResult plan) {
        String key = AiResponseUtils.normalizeKey(userInput);
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.failureCount > 0) {
            entry.failureCount = 0;
        }
        unitStore.incrementSuccess(plan.unitId());
    }

    // ========== 探索优化权重 ==========

    private static long optimizeCount(Unit unit) {
        if (unit == null || unit.explorationRecords() == null) return 0;
        return unit.explorationRecords().stream()
                .filter(r -> r.declareType() == ExplorationRecord.DeclareType.OPTIMIZE).count();
    }

    /** 计算某计划的优化权重：被优化越多次越不值（权重 ∝ 1/(1+次数)） */
    public double planOptimizeWeight(Unit unit) {
        return 1.0 / (1.0 + optimizeCount(unit));
    }

    /** 优化阈值：数据库数据少 → 阈值高（倾向新建扩充），数据多 → 阈值低（倾向优化精炼） */
    public double optimizeThreshold() {
        int dataVolume = unitStore.countActiveUnits();
        if (dataVolume < 10) return 0.9;   // 库很小，几乎只新建
        if (dataVolume < 50) return 0.7;
        if (dataVolume < 100) return 0.5;
        return 0.3;                         // 库够大，多优化
    }

    /** 判断是否值得优化：planWeight >= threshold → 优化，否则新建 */
    public boolean isWorthOptimizing(Unit unit) {
        double weight = planOptimizeWeight(unit);
        double threshold = optimizeThreshold();
        boolean worth = weight >= threshold;
        log.info("⚖️ 优化权重判断: weight={} threshold={} optimizeCount={} dataVolume={} → {}",
                String.format("%.2f", weight), String.format("%.2f", threshold),
                optimizeCount(unit),
                unitStore.countActiveUnits(),
                worth ? "优化" : "新建");
        return worth;
    }

    /** 获取优化后的失败删除门槛：基础3次 + 每优化1次多加0.5次（最少3次） */
    public int effectiveFailureThreshold(Unit unit) {
        return Math.max(props.toolPlanner().failureThreshold(),
                props.toolPlanner().failureThreshold() + (int)(optimizeCount(unit) * 0.5));
    }

    // ========== 缓存失败/成功回调 ==========
    public PlanResult replan(String userInput, ToolCallback[] allTools, String failureReason) {
        List<FailureCause> warnings = unitFailureService.searchFailureCauses(userInput, 3);
        return doPlan(userInput, allTools, failureReason, warnings);
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
     * 增量分类：只有初始化（分类为空）才全量，之后只为新增工具归类（硬阈值 + 容量上限，放不进强制新建）。
     */
    public int syncCategoriesIncremental(ToolCallback[] allTools) {
        return categoryService.syncCategoriesIncremental(allTools);
    }

    /**
     * 内部：AI 规划 — 多轮树形分类交互（严格按 docs/临时文件 设计）。
     *
     * <h3>多轮交互流程（3 阶段）</h3>
     * <ol>
     *   <li><b>阶段1 — L1 大类选择</b>：展示 L1 大类（name+desc+toolCount），AI 选 1-3 个，每轮最多消耗 1 次 roundCount</li>
     *   <li><b>阶段2 — L2 子类展开</b>（可选）：对每个选中 L1，若有 L2 子类则展示子类列表，AI 选 1-2 个；无子类则直接收集工具</li>
     *   <li><b>阶段3 — 最终轮工具选择 + 计划</b>：展示收集到的工具列表（name+desc），AI 挑工具 + 制定执行计划</li>
     * </ol>
     * <p>每阶段均有独立日志标记（===== 阶段1: L1选择 =====），方便追踪。</p>
     *
     * <h3>防死循环约束（严格按 临时文件 §4）</h3>
     * <ul>
     *   <li>maxCategoryRounds（3）：超过后不再展示分类树，降级为全量工具（前 50 个）</li>
     *   <li>max_selected_categories_per_round：L1 每轮最多 3 个，L2 每轮最多 2 个（解析 hard cap + prompt 软约束）</li>
     *   <li>max_tools_displayed（50）：超出截断并提示用 searchTool</li>
     *   <li>路径记忆（§4.2）：AI 重选已展开的分类 → 拒绝 + 消耗轮次（visitedIds 检查）</li>
     *   <li>禁止回退（§4.2）：只向前或 searchTool，不提供"返回上级"</li>
     * </ul>
     *
     * <h3>异常处理（严格按 临时文件 §6）</h3>
     * <ul>
     *   <li>分类名不存在 → 模糊匹配 → 返回候选让 AI 重试（消耗轮次）</li>
     *   <li>AI 始终不选分类 → 允许直接 searchTool/listAllTools 搜（不降级，走 searchTool 路径）</li>
     *   <li>轮次用尽（roundCount >= maxRounds）→ 降级为全量工具前 50 个</li>
     *   <li>allToolNames 为空 → fallbackPlan（传 missing 描述）</li>
     * </ul>
     *
     * @param userInput    用户输入文本
     * @param allTools     全部可用工具（147 个，MCP + Java + Generated）
     * @param failureReason 失败原因（replan 场景），null 表示首次规划
     * @param warnings     历史失败经验（向量检索，供 AI 参考避坑）
     * @return PlanResult（含 selectedToolNames、missing、fromCache=false）
     */
    private PlanResult doPlan(String userInput, ToolCallback[] allTools, String failureReason,
                               List<FailureCause> warnings) {
        // === 入点日志：标识首次规划还是重规划 ===
        // failureReason != null → replan 场景，doFinalRound 使用 replanning.txt 避免重复犯错
        log.info("========== 多轮规划开始 [{}] ========== userInput='{}', allTools={}, reason={}",
                failureReason == null ? "首次规划" : "重规划",
                AiResponseUtils.truncate(userInput, 60), // 截断过长用户输入
                allTools.length,                          // 全量工具数（通常 147）
                failureReason == null ? "无" : AiResponseUtils.truncate(failureReason, 40));

        // === 拉取分类数据（Qdrant 向量存储，ToolCategoryService.syncCategories() 写入） ===
        List<ToolCategoryService.CategorySummary> allCategories = categoryService.listAllCategories();

        // 分类为空 → 首次启动尚未同步 / 旧数据已清除 → 触发异步同步 + 降级返回
        if (allCategories.isEmpty()) {
            log.info("📦 工具分类为空，触发异步同步...");
            triggerAsyncClassification(allTools);          // 异步通知 DeepSeek 生成树形分类
            return fallbackPlan(List.of("分类数据未就绪")); // 降级：返回 fallback 工具 + missing 描述
        }

        // 工具集脏标记：新增/删除了工具 → 异步触发重分类（不阻塞当前请求）
        if (toolRegistry.checkAndClearDirty()) {           // 检查 + 原子清空脏标记
            log.info("📦 检测到新工具，触发异步重新分类...");
            triggerAsyncClassification(allTools);          // 异步，当前请求继续用旧分类
        }

        // 从全量分类中过滤 level=1 的 L1 大类节点（树形分类的根节点）
        List<ToolCategoryService.CategorySummary> l1Categories = allCategories.stream()
                .filter(c -> c.level() == 1)               // level: 1=L1大类, 2=L2子类
                .toList();

        // L1 为空 → 数据异常（有分类但不是树形结构） → 降级
        if (l1Categories.isEmpty()) {
            return fallbackPlan(List.of("无L1分类数据"));
        }

        // === 初始化阶段1+2 共享状态变量 ===
        String contextPrefix = envService.getOsInfo();      // 系统环境信息头（OS/CPU/JVM）
        Set<String> visitedIds = new HashSet<>();           // 路径记忆（§4.2）：已展开的分类 id
        List<String> missing = new ArrayList<>();           // 收集 MISSING 描述，最终返回
        Set<String> allToolNames = new LinkedHashSet<>();   // 阶段1+2 收集的工具名（去重+保序）
        int maxRounds = props.toolPlanner().maxCategoryRounds(); // 轮次上限（默认 3）
        int roundCount = 0;                                 // 已消耗轮次（L1/L2 选择+重试均计入）
        boolean degradedToFullList = false;                 // 分类轮次用尽/拒绝分类 → 允许 listAllTools 兜底

        // ================================================================
        //  阶段1: L1 大类选择（临时文件 §3.1）
        //  输入：L1 大类列表（name+desc+toolCount）
        //  输出：selectedL1Names（1-3 个 L1 名）
        // ================================================================
        log.info("===== 阶段1: L1大类选择 ===== L1={} 个, maxRounds={}",
                l1Categories.size(), maxRounds);
        List<String> selectedL1Names = List.of();           // AI 选中的 L1 大类名（空=list.of() 不可变安全初始值）
        // remainingL1：每轮循环被重建为 availableL1 过滤后的剩余 L1 列表
        List<ToolCategoryService.CategorySummary> remainingL1 = new ArrayList<>(l1Categories);

        // === L1 选择循环：每轮消耗 1 次 roundCount，直到 AI 选择有效或轮次耗尽 ===
        while (roundCount < maxRounds) {                    // 轮次守卫
            // 构建可用 L1 列表：排除 visitedIds 中的（路径记忆拒绝）
            List<ToolCategoryService.CategorySummary> availableL1 = remainingL1.stream()
                    .filter(c -> !visitedIds.contains(c.id()))  // 排除已展开的分类
                    .toList();

            // 全部 L1 已访问 → 无法再选，退出循环
            if (availableL1.isEmpty()) {
                log.info("📦 L1 全部已访问，跳过 L1 选择");
                break;
            }

            // 构建 L1 prompt：分类列表 + 用户请求 + 失败经验 + 有效规则
            String l1Prompt = buildL1Prompt(contextPrefix, availableL1, userInput, failureReason,
                    roundCount > 0 ? visitedIds : null);    // 非首轮才提示"已访问"
            l1Prompt = injectWarnings(l1Prompt, warnings);  // 注入历史失败警告（⚠️ 行）
            l1Prompt = injectRules(l1Prompt, ruleInductionService.getActiveRules()); // 注入有效规则

            // 调用 AI → 期望返回 JSON: {"categories": [...], "reason": "..."}
            String l1Response = callAISafe(l1Prompt, "L1选择-" + (roundCount + 1));
            selectedL1Names = parseCategoryNames(l1Response);   // 解析 + 硬截断到最多 3 个
            roundCount++;                                       // 消耗 1 轮

            log.info("📦 第{}轮 L1选择: {} 个L1大类 → AI选了 {} 个: {}",
                    roundCount, availableL1.size(), selectedL1Names.size(), selectedL1Names);

            // AI 不选任何分类 → 不降级，允许后续走 searchTool/listAllTools 自由搜索（§6）
            if (selectedL1Names.isEmpty()) {
                log.info("📦 AI 未选择分类 → 降级为 searchTool/listAllTools 路径");
                visitedIds.add("__ALL__");                   // 哨兵值：标记全部已访问，防止循环重试
                break;
            }

            // === 逐名验证 AI 选中的 L1 分类名（三态判断） ===
            boolean allValid = true;                         // 是否全部有效（无任何拒绝/缺失）
            List<String> validatedNames = new ArrayList<>(); // 只收集通过验证的分类名
            for (String name : selectedL1Names) {            // 遍历 AI 选中的分类名
                var l1Opt = categoryService.findByName(name); // 按名查找（含模糊包含匹配）
                if (l1Opt.isPresent()) {                     // 分类名存在
                    String id = l1Opt.get().id();            // Qdrant point UUID
                    if (visitedIds.contains(id)) {            // 路径记忆：已展开过 → 拒绝（§4.2）
                        log.warn("⛔ 路径记忆拒绝: L1「{}」已展开过，消耗本轮", name);
                        allValid = false;                    // 标记有拒绝
                        continue;
                    }
                    visitedIds.add(id);                      // 记录到路径记忆
                    validatedNames.add(name);                // 通过验证
                } else {                                     // 分类名不存在 → 模糊匹配
                    List<String> candidates = fuzzyMatchCategoryNames(name, availableL1); // 找 1-2 个候选
                    if (!candidates.isEmpty()) {             // 有候选
                        if (roundCount < maxRounds) {        // 还有轮次 → 重试
                            log.info("❓ L1「{}」不存在，候选: {} → 重新确认（消耗轮次）", name, candidates);
                            String retryPrompt = buildRetryPrompt(contextPrefix, // 构建"原名不存在"提示
                                    "分类「" + name + "」不存在，以下是最接近的候选", candidates, userInput);
                            String retryResponse = callAISafe(retryPrompt, "L1重试-" + name); // AI 重选
                            List<String> retryNames = parseCategoryNames(retryResponse); // 解析重选结果
                            roundCount++;                    // 重试消耗 1 轮
                            for (String rn : retryNames) {   // 验证重选结果
                                var ro = categoryService.findByName(rn);
                                if (ro.isPresent() && !visitedIds.contains(ro.get().id())) {
                                    visitedIds.add(ro.get().id());
                                    validatedNames.add(rn);  // 重选有效 → 加入
                                }
                            }
                        } else {                             // 轮次已满 → 无法重试，记录 missing
                            log.info("⚠️ 轮次已满，分类「{}」不存在 → 降级", name);
                            missing.add("MISSING: 分类「" + name + "」不存在");
                        }
                    } else {                                 // 无候选 → 直接记录 missing
                        missing.add("MISSING: 分类「" + name + "」不存在，无候选");
                    }
                    allValid = false;                        // 标记有异常
                }
            }
            selectedL1Names = validatedNames;                // 只保留通过验证的分类名

            if (allValid || !selectedL1Names.isEmpty()) break; // 有有效选择 → 进入阶段2
            if (roundCount >= maxRounds) break;               // 轮次耗尽 → 退出，走降级出口
            remainingL1 = availableL1;                        // 重建可用列表，继续下一轮循环
        }

        // === 阶段1 异常出口1：轮次用尽且无选中 → 降级为全量工具前 50 个（§6） ===
        if (roundCount >= maxRounds && selectedL1Names.isEmpty()) {
            log.info("📦 轮次用尽 ({} 轮) → 降级为全量工具前 50 个", maxRounds);
            Set<String> existingNames = collectExisting(allTools); // 构建有效工具名集合
            int count = 0;
            for (ToolCallback tc : allTools) {                // 遍历全量工具
                if (count >= 50) break;                       // 截断到 50 个
                allToolNames.add(tc.getToolDefinition().name()); // 收集工具名
                count++;
            }
            return doFinalRound(contextPrefix, allTools, userInput, failureReason, warnings,
                    new ArrayList<>(allToolNames), missing).withListAllToolsAllowed();  // 允许 listAllTools 兜底
        }

        // === 阶段1 异常出口2：AI 始终不选分类 → searchTool/listAllTools 路径 ===
        if (selectedL1Names.isEmpty()) {
            log.info("📦 AI 始终不选分类 → searchTool/listAllTools 路径，传前50个工具");
            return doFinalRound(contextPrefix, allTools, userInput, failureReason, warnings,
                    getFirstNToolNames(allTools, 50), missing).withListAllToolsAllowed(); // 允许 listAllTools 兜底
        }

        // ================================================================
        //  阶段2: L2 子类展开（临时文件 §3.2）
        //  对每个选中的 L1，按子类情况分三种处理：
        //    A) 无子类 → 直接收工具名（跳过 AI）
        //    B) 轮次用尽 → 降级全量工具前 50 个
        //    C) 有子类 → AI 选 L2 → 收工具名
        // ================================================================
        log.info("===== 阶段2: L2子类展开 ===== selectedL1={}, roundCount={}/{}",
                selectedL1Names, roundCount, maxRounds);
        for (String name : selectedL1Names) {                 // 遍历阶段1 选中的每个 L1
            var l1Opt = categoryService.findByName(name);     // 按名查找 L1
            if (l1Opt.isEmpty()) continue;                    // 防御：找不到则跳过
            var l1 = l1Opt.get();

            // 获取该 L1 的直接子节点（L2），并排除已访问的
            List<ToolCategoryService.CategorySummary> l2Children = categoryService.getChildren(l1.id());
            l2Children = l2Children.stream()
                    .filter(c -> !visitedIds.contains(c.id())) // 路径记忆过滤
                    .toList();

            if (l2Children.isEmpty()) {                       // 情况A：无 L2 或全部已访问
                allToolNames.addAll(l1.toolNames());           // 直接收集该 L1 下所有工具名
                log.info("📦 L1「{}」无子类（或已全展开）→ 直接收集 {} 个工具",
                        l1.name(), l1.toolNames().size());
            } else {                                          // 有未访问的 L2 子类
                if (roundCount >= maxRounds) {                // 情况B：轮次耗尽
                    log.info("📦 已达最大轮次 {} → 降级为全量工具前 50 个", maxRounds);
                    degradedToFullList = true;                 // 允许 listAllTools 兜底
                    Set<String> existingNames = collectExisting(allTools);
                    int count = 0;
                    allToolNames.clear();                      // 清空之前收集的，改用全量
                    for (ToolCallback tc : allTools) {         // 遍历全量工具
                        if (count >= 50) break;                // 截断 50
                        allToolNames.add(tc.getToolDefinition().name());
                        count++;
                    }
                    break;                                     // 退出 L1 遍历循环
                } else {                                       // 情况C：正常 L2 选择
                    String l2Prompt = buildL2Prompt(contextPrefix, l2Children, l1.name(), userInput);
                    String l2Response = callAISafe(l2Prompt, "L2选择-" + l1.name());
                    List<String> selectedL2Names = parseCategoryNames(l2Response); // 最多 2 个
                    roundCount++;                                // 消耗 1 轮

                    log.info("📦 第{}轮 L2选择: L1「{}」下 {} 个子类 → AI选了 {} 个",
                            roundCount, l1.name(), l2Children.size(), selectedL2Names.size());

                    if (selectedL2Names.isEmpty()) {            // AI 没选子类
                        // §4.2 禁止抱怨 → 照收全部子类的工具
                        for (var l2 : l2Children) {
                            allToolNames.addAll(l2.toolNames()); // 收集该 L2 下所有工具
                            visitedIds.add(l2.id());             // 标记为已访问
                        }
                    } else {                                    // AI 选了子类 → 逐名验证
                        for (String l2Name : selectedL2Names) {  // 遍历 AI 选中的 L2 名
                            var l2Opt = findCategory(l2Name, l2Children); // 在当前 L1 下查找 L2
                            if (l2Opt.isPresent()) {             // L2 名存在
                                if (visitedIds.contains(l2Opt.get().id())) { // 路径记忆拒绝
                                    log.warn("⛔ 路径记忆拒绝: L2「{}」已展开过", l2Name);
                                    continue;
                                }
                                allToolNames.addAll(l2Opt.get().toolNames()); // 收集工具
                                visitedIds.add(l2Opt.get().id()); // 记录到路径记忆
                            } else {                             // L2 名不存在 → 模糊匹配
                                List<String> candidates = fuzzyMatchCategoryNames(l2Name, l2Children);
                                if (!candidates.isEmpty() && roundCount < maxRounds) { // 有候选+有轮次
                                    log.info("❓ L2「{}」不存在，候选: {} → 重新确认（消耗轮次）", l2Name, candidates);
                                    String retryPrompt = buildRetryPrompt(contextPrefix,
                                            "子类「" + l2Name + "」不存在，以下是最接近的候选", candidates, userInput);
                                    String retryResponse = callAISafe(retryPrompt, "L2重试-" + l2Name);
                                    List<String> retryNames = parseCategoryNames(retryResponse);
                                    roundCount++;                // 重试消耗 1 轮
                                    for (String rn : retryNames) { // 验证重选结果
                                        var ro = findCategory(rn, l2Children);
                                        if (ro.isPresent() && !visitedIds.contains(ro.get().id())) {
                                            allToolNames.addAll(ro.get().toolNames());
                                            visitedIds.add(ro.get().id());
                                        }
                                    }
                                } else {                         // 无候选或轮次已满
                                    missing.add("MISSING: 子类「" + l2Name + "」不存在");
                                }
                            }
                        }
                    }
                }
            }
        }

        // === 阶段2 异常出口：allToolNames 为空（极端情况） ===
        if (allToolNames.isEmpty()) {
            return fallbackPlan(missing);
        }

        // ================================================================
        //  阶段3: 最终轮 — 工具选择 + 计划制定（临时文件 §3.3）
        //  输入：阶段1+2 收集的工具名列表
        //  输出：PlanResult（含 selectedToolNames + plan + missing）
        // ================================================================
        log.info("===== 阶段3: 工具选择+计划制定 ===== 收集到 {} 个工具名, missing={}",
                allToolNames.size(), missing.size());
        PlanResult result = doFinalRound(contextPrefix, allTools, userInput, failureReason, warnings,
                new ArrayList<>(allToolNames), missing);     // 最终轮：AI 选工具 + 定计划
        log.info("========== 多轮规划结束 ========== selectedTools={}/{}, missing={}, rounds={}/{}, visitedIds={}",
                result.selectedToolNames().size(), allTools.length,     // 选中/全量
                result.missingDescriptions().size(),                    // missing 数
                roundCount, maxRounds,                                   // 消耗轮次/上限
                visitedIds.size());                                      // 路径记忆条目数
        return degradedToFullList ? result.withListAllToolsAllowed() : result;
    }

    /**
     * 执行最终轮：工具列表 → AI 筛选 → 制定计划。
     *
     * <p>将阶段1+2 收集到的工具名列表组装为编号列表（name + desc），
     * 发给 AI 完成最终选择。AI 返回 JSON: {tools, plan, missing}。</p>
     *
     * <p>无论 planning 还是 replanning 都走此方法，区别在于使用的 prompt 模板不同：
     * <ul>
     *   <li>首次规划 → planning.txt（提示先选工具再做计划）</li>
     *   <li>重规划 → replanning.txt（额外提示失败原因，要求 AI 避坑）</li>
     * </ul>
     * </p>
     *
     * @param contextPrefix 系统环境信息头
     * @param allTools      全部工具（用于验证 AI 选择的工具名是否真实存在）
     * @param userInput     用户请求
     * @param failureReason 失败原因（null=首次规划）
     * @param warnings      历史失败经验
     * @param toolNames     阶段1+2 收集到的工具名列表
     * @param missing       此前已收集的 missing 描述
     * @return PlanResult（fromCache=false）
     */
    private PlanResult doFinalRound(String contextPrefix, ToolCallback[] allTools,
                                     String userInput, String failureReason,
                                     List<FailureCause> warnings,
                                     List<String> toolNames, List<String> missing) {
        // 构建工具列表文本（编号 + name + desc），供 AI 浏览和选择
        Set<String> existingNames = collectExisting(allTools);
        StringBuilder toolList = new StringBuilder();
        int toolIdx = 0;
        for (String name : toolNames) {
            if (!existingNames.contains(name)) continue; // 过滤不存在的工具名（防御性）
            toolIdx++;
            String desc = getToolDesc(name, allTools);
            toolList.append(toolIdx).append(". ").append(name);
            if (desc != null && !desc.isBlank()) {
                toolList.append(" - ").append(desc);
            }
            toolList.append("\n");
        }

        // 截断：超过 50 个工具则截断并提示用 searchTool（§4.1）
        int maxToolsDisplayed = 50;
        String toolListStr = toolList.toString();
        boolean truncated = false;
        if (toolIdx > maxToolsDisplayed) {
            String[] lines = toolListStr.split("\n");
            StringBuilder sb2 = new StringBuilder();
            for (int i = 0; i < Math.min(lines.length, maxToolsDisplayed); i++) {
                sb2.append(lines[i]).append("\n");
            }
            sb2.append("...（共 ").append(toolIdx).append(" 个工具，已截断，请用 searchTool 搜索更多）\n");
            toolListStr = sb2.toString();
            truncated = true;
        }

        log.info("===== 最终轮: {} 个工具 → AI 筛选 ===== truncated={}, reason={}",
                toolIdx, truncated, failureReason == null ? "首次规划" : "重规划");

        // 选择 prompt 模板：首次用 planning.txt，重规划用 replanning.txt
        String finalPrompt;
        if (failureReason != null) {
            finalPrompt = promptLoader.getReplanning().formatted(contextPrefix, toolListStr, userInput, failureReason);
        } else {
            finalPrompt = promptLoader.getPlanning().formatted(contextPrefix, toolListStr, userInput);
        }
        finalPrompt = injectWarnings(finalPrompt, warnings);
        finalPrompt = injectRules(finalPrompt, ruleInductionService.getActiveRules());

        // AI 返回 JSON: {tools: [...], plan: "...", missing: [...]}
        String finalResponse = callAISafe(finalPrompt, "最终工具选择");
        PlanResult result = parseFinalResponse(finalResponse, allTools, missing);
        // 工具列表超限被截断 → 也允许 listAllTools 兜底（§4.1 max_tools_displayed）
        return truncated ? result.withListAllToolsAllowed() : result;
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
            String resp = modelRouter.chat().prompt().user(prompt).call().content();
            log.info("📞 [{}] AI 响应 {} 字符", stage, resp != null ? resp.length() : 0);
            return resp;
        } catch (Exception e) {
            log.warn("⚠️ AI调用失败 ({}): {}", stage, e.getMessage());
            return "";
        }
    }

    /** 获取工具简短描述（分类混排：展示时加 [组合]/[工具] 标记，§3.3）。 */
    private String getToolDesc(String name, ToolCallback[] allTools) {
        for (ToolCallback tc : allTools) {
            if (tc.getToolDefinition().name().equals(name)) {
                String marker = name.startsWith("planStep_") ? "[组合] " : "[工具] ";
                return marker + AiResponseUtils.truncateNotNull(tc.getToolDefinition().description(), 80);
            }
        }
        return "";
    }

    /** 注入历史失败原因（指向 FailureCause 图） */
    private String injectWarnings(String prompt, List<FailureCause> warnings) {
        if (warnings == null || warnings.isEmpty()) return prompt;
        StringBuilder warnBlock = new StringBuilder("\n\n--- ⚠️ 历史失败原因（请避免以下做法） ---\n");
        for (int i = 0; i < warnings.size(); i++) {
            var w = warnings.get(i);
            warnBlock.append((i + 1)).append(". [").append(w.category()).append("] ")
                    .append(w.reason()).append("\n");
            if (w.analysis() != null && !w.analysis().isBlank()) {
                warnBlock.append("   建议: ").append(w.analysis()).append("\n");
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
     * 解析 AI 最终轮响应，验证工具名并构建 PlanResult。
     *
     * <p>AI 返回 JSON: {tools: [...], plan: "...", missing: [...]}。
     * 解析后执行以下验证步骤：</p>
     * <ol>
     *   <li>非 JSON → fallback（§3.3 降级处理）</li>
     *   <li>验证每个工具名是否存在于 allTools 中 → 脑补工具记录为 missing</li>
     *   <li>始终附加 searchTool + listAllTools（AI 可随时搜索未展示的工具）</li>
     *   <li>附加 alwaysAppendTools 配置项（如 sleep/listWindows）</li>
     *   <li>去重：过滤掉不存在的工具名（resolvedTools.removeIf）</li>
     * </ol>
     *
     * @param response        AI 原始响应文本
     * @param allTools        全部工具（用于验证）
     * @param existingMissing 此前阶段已收集的 missing 描述
     * @return PlanResult（fromCache=false），工具名列表已去重+验证
     */
    private PlanResult parseFinalResponse(String response, ToolCallback[] allTools, List<String> existingMissing) {
        // 合并此前阶段收集的 missing（分类不存在等）
        List<String> missing = new ArrayList<>(existingMissing);

        if (response == null || response.isBlank()) {
            return fallbackPlan(List.of("AI返回为空"));
        }

        // §3.3：AI 可能输出工具调用指令而非 JSON → 用 startsWith("{") 检测并降级
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

            // 构建全量工具名集合（用于验证 AI 选中的工具是否真实存在）
            Set<String> existingNames = collectExisting(allTools);
            Set<String> resolvedTools = new LinkedHashSet<>();

            // 逐工具验证：分两路 → validTools（存在）、hallucinatedTools（脑补）
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

            // 脑补工具：记录 missing，提示 AI 用 searchTool 搜索替代
            if (!hallucinatedTools.isEmpty()) {
                log.warn("⚠️ AI 脑补了不存在的工具: {}，已过滤", hallucinatedTools);
                for (String ht : hallucinatedTools) {
                    missing.add("MISSING: 工具「" + ht + "」不存在，请用 searchTool 搜索替代");
                }
            }

            // 始终加入 searchTool + listAllTools（AI 可随时搜索/查看未展示的工具）
            resolvedTools.add("searchTool");
            resolvedTools.add("listAllTools");

            // 防御性去重：过滤掉不存在的工具名
            resolvedTools.removeIf(t -> !existingNames.contains(t));

            // 加入 alwaysAppendTools 配置项（如 sleep/listWindows 等常驻工具）
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
            log.info("📦 最终选择 → {} 个工具 ({} 个缺失): {}",
                    finalTools.size(), missing.size(),
                    finalTools.size() <= 20 ? finalTools : finalTools.subList(0, 20) + "...共" + finalTools.size() + "个");
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

    /** 异步触发增量分类（不阻塞规划） */
    private void triggerAsyncClassification(ToolCallback[] allTools) {
        CompletableFuture.runAsync(() -> {
            try {
                int n = categoryService.syncCategoriesIncremental(allTools);
                log.info("📁 异步增量分类完成: 归类 {} 个", n);
            } catch (Exception e) {
                log.warn("⚠️ 异步增量分类失败: {}", e.getMessage());
            }
        });
    }
}
