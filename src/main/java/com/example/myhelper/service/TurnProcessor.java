package com.example.myhelper.service;

import com.example.myhelper.autogen.GeneratedToolRegistry;
import com.example.myhelper.config.MyHelperProperties;
import com.example.myhelper.config.SystemEnvironmentService;
import com.example.myhelper.registry.ToolModel;
import com.example.myhelper.registry.ToolRegistry;
import com.example.myhelper.service.SkillConfig;
import com.example.myhelper.memory.vector.episode.*;
import com.example.myhelper.memory.unit.Unit;
import com.example.myhelper.memory.unit.UnitSedimentationService;
import com.example.myhelper.memory.unit.UnitFailureService;
import com.example.myhelper.memory.unit.UniversalUnitExecutor;
import com.example.myhelper.memory.unit.ExplorationRecord;
import com.example.myhelper.config.ModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * AI Turn 处理器 —— 从 {@code MyHelperApplication} 抽离出的核心 AI 对话处理逻辑。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>技能匹配 → 注入 prompt</li>
 *   <li>工具规划（三层缓存）→ 缺失工具自动生成</li>
 *   <li>缓存命中 → PlanMatcher 判可用 → 脚本执行 / AI 带参考计划执行</li>
 *   <li>新规划 → 执行 → Reflect 校验 → 成功沉淀 PLAN_STEP Unit</li>
 *   <li>失败处理 → AI 归因 → 惩罚/分段继续/重规划</li>
 * </ol>
 *
 * <p>中断控制：提供 {@link #getCurrentTurnId()} / {@link #interruptCurrentTurn()}
 * 供外部（语音线程）中断正在进行的 AI Turn。</p>
 */
@Component
public class TurnProcessor {

    private static final Logger log = LoggerFactory.getLogger(TurnProcessor.class);

    // ========== 依赖 ==========
    private final ToolPlanner toolPlanner;
    private final PlanMatcher planMatcher;
    private final ReflectService reflectService;
    private final PlanExecutor planExecutor;
    private final GeneratedToolRegistry generatedToolRegistry;
    private final ToolSearchService toolSearchService;
    private final ToolRegistry toolRegistry;
    private final SkillConfig skillConfig;
    private final MyHelperProperties props;
    private final SystemEnvironmentService envService;
    private final UniversalUnitExecutor universalUnitExecutor;
    private final UnitSedimentationService unitSedimentationService;
    private final UnitFailureService unitFailureService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // ========== AI 中断控制 ==========
    private final AtomicLong aiTurnId = new AtomicLong(0);
    private volatile long currentAiTurnId = -1;
    private volatile int silenceCount = 0;

    /** 动态工具数量（上次检查时的值，用于判断是否需要重同步分类） */
    private volatile int lastDynamicToolCount = 0;
    /** 上次合并时活跃 PLAN_STEP Unit 工具数量（用于判断是否需要重同步分类） */
    private volatile int lastUnitToolCount = 0;
    /** 最近一次 process 的 baseTools（基础工具），供沉淀完成后重同步分类使用 */
    private volatile ToolCallback[] lastBaseTools = null;

    /** 当前 Turn 是否为探索模式（由 processExploration 设置，process 结束时复位） */
    private boolean explorationMode = false;

    private ModelRouter.Mode currentMode() {
        return explorationMode ? ModelRouter.Mode.EXPLORATION : ModelRouter.Mode.NORMAL;
    }

    public TurnProcessor(ToolPlanner toolPlanner,
                          PlanMatcher planMatcher,
                          ReflectService reflectService,
                          PlanExecutor planExecutor,
                          GeneratedToolRegistry generatedToolRegistry,
                          ToolSearchService toolSearchService,
                          ToolRegistry toolRegistry,
                          SkillConfig skillConfig,
                          MyHelperProperties props,
                          SystemEnvironmentService envService,
                          UniversalUnitExecutor universalUnitExecutor,
                          UnitSedimentationService unitSedimentationService,
                          UnitFailureService unitFailureService) {
        this.toolPlanner = toolPlanner;
        this.planMatcher = planMatcher;
        this.reflectService = reflectService;
        this.planExecutor = planExecutor;
        this.generatedToolRegistry = generatedToolRegistry;
        this.toolSearchService = toolSearchService;
        this.toolRegistry = toolRegistry;
        this.skillConfig = skillConfig;
        this.props = props;
        this.envService = envService;
        this.universalUnitExecutor = universalUnitExecutor;
        this.unitSedimentationService = unitSedimentationService;
        this.unitFailureService = unitFailureService;
        this.objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    }

    // ========================================================================
    // 中断控制（供外部调用）
    // ========================================================================

    /** 当前活跃的 AI Turn ID（-1 = 无活跃 Turn） */
    public long getCurrentTurnId() { return currentAiTurnId; }

    /** 是否有 AI Turn 正在执行 */
    public boolean isActive() { return currentAiTurnId != -1; }

    /** 中断当前 AI Turn */
    public boolean interruptCurrentTurn() {
        if (currentAiTurnId != -1) {
            currentAiTurnId = -1;
            return true;
        }
        return false;
    }

    public int getSilenceCount() { return silenceCount; }
    public void resetSilenceCount() { silenceCount = 0; }
    public void incrementSilenceCount() { silenceCount++; }

    /** 启动时初始化：不再需要传递 ToolCallback[] 到 ToolSearchService（已改用 ToolRegistry） */
    public void initToolSearch(ToolCallback[] allTools) {
        // 工具注册表已由 ToolSyncService 同步，此处无需额外操作
    }

    /** 动态 ClassLoader 初始化 */
    public void initDynamicClassLoader() {
        generatedToolRegistry.initDynamicClassLoader();
    }

    /** 触发工具分类同步（使用 Qdrant 分类服务） */
    public int syncCategories(ToolCallback[] tools, boolean force) {
        return toolPlanner.syncCategories(tools, force);
    }

    /** 触发工具分类增量同步（只有初始化才全量，之后只为新增工具归类） */
    public int syncCategoriesIncremental(ToolCallback[] tools) {
        return toolPlanner.syncCategoriesIncremental(tools);
    }

    /** 合并静态工具 + 动态生成工具（供外部获取完整工具列表） */
    public ToolCallback[] mergeTools(ToolCallback[] baseTools) {
        return mergeDynamicTools(baseTools);
    }

    // ========================================================================
    // 核心处理入口
    // ========================================================================

    /**
     * 处理一次 AI Turn（文字或语音输入）。
     *
     * @param modelRouter 模型路由器
     * @param baseTools  基础工具列表（MCP + 本地 @Tool）
     * @param userInput  用户输入
     * @param ttsService TTS 服务（用于播报）
     */
    public void process(ModelRouter modelRouter, ToolCallback[] baseTools,
                         String userInput, TtsService ttsService) {
        process(modelRouter, baseTools, userInput, ttsService, false);
    }

    /** 内部入口，isExploration=true 时探索模式走特殊缓存策略 */
    private void process(ModelRouter modelRouter, ToolCallback[] baseTools,
                         String userInput, TtsService ttsService, boolean isExploration) {
        long myTurnId = aiTurnId.incrementAndGet();
        currentAiTurnId = myTurnId;
        silenceCount = 0;
        this.lastBaseTools = baseTools;
        universalUnitExecutor.clearExplorationDeclareType();

        long turnStartTime = System.currentTimeMillis();
        boolean turnSuccess = true;
        log.info("===== TurnProcessor.processTurn 开始 ===== userInput='{}', allTools={}, time={}",
                userInput, baseTools.length, java.time.LocalDateTime.now());

        // 刷新工具列表（包含运行时动态加载的工具）
        ToolCallback[] tools = mergeDynamicTools(baseTools);
        syncCategoriesIfNewTools(tools);

        log.info("🤖 思考中...");

        String effectiveInput = userInput;
        List<ToolCallLog> toolCallLogs = Collections.synchronizedList(new ArrayList<>());

        // 1. 技能匹配（探索模式跳过，探索有自己的上下文prompt）
        String skillInstructions = isExploration ? "" : skillConfig.getInstructions(userInput);
        if (!skillInstructions.isEmpty()) {
            effectiveInput = skillInstructions + "\n用户请求：" + userInput;
            log.info("📋 已注入技能: {}", skillConfig.getMatchedSkillNames(userInput));
        }

        // 2. 工具规划（三层缓存）
        ToolPlanner.PlanResult plan = toolPlanner.plan(userInput, tools);
        log.info("Plan结果: {}个工具, {}个缺失, fromCache={}",
                plan.selectedToolNames().size(), plan.missingDescriptions().size(), plan.fromCache());

        // 探索模式：缓存命中 → AI 决策：调试 vs 优化 → 权重决定是否新建
        if (isExploration && plan.fromCache()) {
            plan = handleExplorationCacheHit(modelRouter, userInput, plan);
        }

        // 防护：缓存命中但 0 工具 → 走新规划
        if (plan.fromCache() && plan.selectedToolNames().isEmpty()) {
            log.info("🛡️ 缓存命中但选中 0 个工具，降级为新规划");
            plan = ToolPlanner.PlanResult.ofAIPlan(List.of(), List.of());
        }

        // 工具缺失 → 关键词兜底 / 宽松匹配 / AI 自动生成
        if (!plan.missingDescriptions().isEmpty()) {
            List<String> unresolved = new ArrayList<>();
            for (String desc : plan.missingDescriptions()) {
                List<String> found = ToolPlanner.findToolsByKeywords(List.of(desc), tools);
                // 严格匹配失败 → 宽松兜底（避免"浏览器控制工具"因"控""制"不在描述中误判缺失）
                if (found.isEmpty()) {
                    found = ToolPlanner.findToolsByDescriptionFallback(desc, tools);
                    if (!found.isEmpty()) {
                        log.info("🔎 严格匹配未命中 '{}' → 宽松兜底命中: {}", desc, found);
                    }
                }
                if (!found.isEmpty()) {
                    log.info("🔎 缓存 MISSING '{}' → 已有工具: {}", desc, found);
                    plan.selectedToolNames().addAll(found);
                } else {
                    unresolved.add(desc);
                    if (!plan.fromCache()) {
                        log.info("⚠️ 缺少工具: {}（尝试让 AI 自动生成）", desc);
                        triggerToolGeneration(desc, ttsService);
                    } else {
                        log.info("⚠️ 缓存命中但缺少工具: {}（让 AI 用 searchTool 查找）", desc);
                    }
                }
            }
            plan.missingDescriptions().retainAll(unresolved);
        }

        // 3. 命中缓存 → 走缓存逻辑；未命中 → 新规划
        log.info("注入上下文 + 构建system prompt...");
        try {
            if (plan.fromCache() && plan.unit() != null) {
                log.info("  调用AI 开始 (缓存命中)...");
                handleCacheHit(modelRouter, tools, effectiveInput, userInput, plan, toolCallLogs, myTurnId, ttsService);
            } else {
                log.info("  调用AI 开始 (新规划)...");
                handleNewPlan(modelRouter, tools, effectiveInput, userInput, plan, toolCallLogs, myTurnId, ttsService);
            }
        } catch (Exception e) {
            turnSuccess = false;
            log.error("❌ AI 调用失败（模型可能暂时不可用）", e);
            log.info("🤖 抱歉，模型暂时不可用，请稍后再试。");
        }

        long turnElapsed = System.currentTimeMillis() - turnStartTime;
        log.info("===== TurnProcessor.processTurn 结束 ===== {}, 耗时={}s",
                turnSuccess ? "成功" : "失败", turnElapsed / 1000.0);

        if (currentAiTurnId == myTurnId) {
            currentAiTurnId = -1;
            silenceCount = 0;
        }
        universalUnitExecutor.clearExplorationDeclareType();
    }

    // ========================================================================
    // 工具管理
    // ========================================================================

    /** 合并静态工具 + 动态生成工具 + 计划步骤 Unit 工具 */
    private ToolCallback[] mergeDynamicTools(ToolCallback[] baseTools) {
        ToolCallback[] dynamics = generatedToolRegistry.getDynamicTools();
        ToolCallback[] unitTools = universalUnitExecutor.buildUnitTools();
        lastUnitToolCount = unitTools.length;
        if (dynamics.length == 0 && unitTools.length == 0) return baseTools;
        Map<String, ToolCallback> unique = new LinkedHashMap<>();
        for (ToolCallback t : baseTools) unique.putIfAbsent(t.getToolDefinition().name(), t);
        for (ToolCallback t : dynamics) unique.putIfAbsent(t.getToolDefinition().name(), t);
        for (ToolCallback t : unitTools) {
            unique.putIfAbsent(t.getToolDefinition().name(), t);
            registerPlanStepTool(t);   // 计划步骤也作为工具入库，可被 searchTool 搜到 + 分类关联
        }
        return unique.values().toArray(new ToolCallback[0]);
    }

    /** 把 planStep_ 工具注册进 tool-registry（Neo4j + Qdrant），使其与普通工具一致可检索/分类。 */
    private void registerPlanStepTool(ToolCallback tc) {
        try {
            var def = tc.getToolDefinition();
            String name = def.name();
            if (name == null || !name.startsWith("planStep_")) return;
            String id = "GENERATED:planStep:" + name;
            // 已存在且描述未变 → 跳过，避免每次 turn 重复 Neo4j save + Qdrant 向量化（Ollama embed 很慢）
            Optional<ToolModel> existing = toolRegistry.findById(id);
            if (existing.isPresent() && java.util.Objects.equals(existing.get().description(), def.description())) {
                return;
            }
            ToolModel model = ToolModel.of(id, name, def.description(), "GENERATED",
                    "planStep", List.of(), "String", List.of(), null);
            toolRegistry.upsertTool(model);
        } catch (Exception e) {
            log.warn("⚠️ 注册 planStep 工具失败: {}", e.getMessage());
        }
    }

    /** 如果动态工具或计划步骤 Unit 工具有新增，触发 Qdrant 分类重同步 */
    private void syncCategoriesIfNewTools(ToolCallback[] currentTools) {
        int currentDynamicCount = generatedToolRegistry.getDynamicTools().length;
        int currentExtraCount = currentDynamicCount + lastUnitToolCount;
        if (currentExtraCount > lastDynamicToolCount) {
            lastDynamicToolCount = currentExtraCount;
            log.info("🔄 检测到新工具（动态{} + 计划步骤{}），增量归类...",
                    currentDynamicCount, lastUnitToolCount);
            int catCount = toolPlanner.syncCategoriesIncremental(currentTools);
            if (catCount >= 0) log.info("📁 工具分类已增量更新: 归类 {} 个", catCount);
        }
    }

    /** 成功沉淀新 PLAN_STEP Unit 后，异步重同步分类，把新 planStep_ 工具纳入分类。 */
    private void resyncCategoriesAfterSediment() {
        ToolCallback[] base = lastBaseTools;
        if (base == null) return;
        try {
            ToolCallback[] latest = mergeDynamicTools(base);
            int catCount = toolPlanner.syncCategoriesIncremental(latest);
            if (catCount >= 0) log.info("📁 沉淀后分类已增量更新: 归类 {} 个", catCount);
        } catch (Exception e) {
            log.warn("⚠️ 沉淀后分类增量更新失败: {}", e.getMessage());
        }
    }

    /**
     * 异步触发生成新工具（"工具缺失 → 自己写工具 → 即时生效"）。
     * 不阻塞当前请求执行。生成完成即时生效，无需重启。
     */
    private void triggerToolGeneration(String description, TtsService ttsService) {
        CompletableFuture.runAsync(() -> {
            try {
                log.info("🔧 开始自动生成工具: {}", description);
                GeneratedToolRegistry.GenerationOutcome outcome =
                        generatedToolRegistry.generateAndPersist(description);
                if (outcome.success()) {
                    String msg = outcome.message();
                    if (!msg.contains("跳过重复生成") && !msg.contains("已存在")) {
                        speakIfPossible(ttsService, "检测到缺失能力，" + msg);
                    }
                } else {
                    log.info("⚠️ 工具自动生成失败: {}", outcome.message());
                    speakIfPossible(ttsService, "工具自动生成失败，" + outcome.message());
                }
            } catch (Exception e) {
                log.error("❌ 工具生成异步任务异常", e);
            }
        });
    }

    // ============================================================
    // 探索模式缓存决策
    // ============================================================

    /**
     * 探索模式缓存命中：AI 判断调试 vs 优化 → 权重决策（优化 vs 新建）。
     */
    private ToolPlanner.PlanResult handleExplorationCacheHit(ModelRouter modelRouter,
                                                              String userInput,
                                                              ToolPlanner.PlanResult plan) {
        Unit unit = plan.unit();
        if (unit == null) {
            log.info("🔄 探索缓存命中但无 Unit，走新规划");
            return ToolPlanner.PlanResult.ofAIPlan(List.of(), List.of());
        }

        // 1. 问 AI：调试还是优化？
        String decision = askExploreDecision(modelRouter, userInput, unit);
        if (decision == null) {
            log.info("🔄 AI 决策失败，默认走新规划");
            return ToolPlanner.PlanResult.ofAIPlan(plan.selectedToolNames(), plan.missingDescriptions());
        }

        boolean isDebug = "debug".equalsIgnoreCase(decision);

        if (isDebug) {
            log.info("🔧 探索: AI 选择调试已有计划");
            universalUnitExecutor.setExplorationDeclareType(ExplorationRecord.DeclareType.VALIDATE);
            return plan; // 保留缓存，走 handleCacheHit
        }

        // 2. 优化分支：检查权重
        log.info("📝 探索: AI 选择优化已有计划，检查权重...");
        if (toolPlanner.isWorthOptimizing(unit)) {
            log.info("✅ 优化权重达标，优化现有计划");
            universalUnitExecutor.setExplorationDeclareType(ExplorationRecord.DeclareType.OPTIMIZE);
            return plan; // 保留缓存，走 handleCacheHit
        }

        log.info("🆕 优化权重不达标，走新规划");
        return ToolPlanner.PlanResult.ofAIPlan(List.of(), List.of());
    }

    /** 询问 AI：对缓存中的计划是调试还是优化？返回 "debug" 或 "optimize"，失败返回 null */
    private String askExploreDecision(ModelRouter modelRouter, String userInput, Unit unit) {
        String prompt = String.format("""
                探索模式：缓存命中了一个已有计划。
                用户目标：%s
                计划摘要：%s（工具：%s，成功%d次，失败%d次，已优化%d次，已调试%d次）
                
                请判断应该【调试】还是【优化】这个计划？
                - 调试：计划本身没问题，尝试用不同参数/环境再跑一次
                - 优化：计划需要改进，调整步骤或工具组合
                
                请用 JSON 回答（无其他文字）：{"action":"debug或optimize","reason":"原因"}
                """,
                userInput,
                unit.goal() != null && !unit.goal().isBlank() ? unit.goal() : userInput,
                String.join(", ", unit.script().stream().map(ToolCallLog::toolName).toList()),
                unit.successCount(), unit.failureCount(),
                optimizeCount(unit), validateCount(unit));

        try {
            ChatClient client = executionClient(modelRouter);
            if (client == null) {
                client = modelRouter.chat(ModelRouter.Mode.NORMAL);
            }
            String response = client.prompt().user(prompt).call().content();
            if (response == null || response.isBlank()) return null;
            String json = response.trim();
            if (json.startsWith("```")) json = json.replaceAll("```[a-z]*", "").replace("```", "").trim();
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            String action = String.valueOf(map.getOrDefault("action", ""));
            log.info("🤔 探索决策: {} → {}", action, map.getOrDefault("reason", ""));
            return action;
        } catch (Exception e) {
            log.warn("⚠️ 探索决策失败: {}", e.getMessage());
            return null;
        }
    }

    /** 探索记录中 OPTIMIZE 类型计数（§9） */
    private static long optimizeCount(Unit unit) {
        if (unit == null || unit.explorationRecords() == null) return 0;
        return unit.explorationRecords().stream()
                .filter(r -> r.declareType() == ExplorationRecord.DeclareType.OPTIMIZE).count();
    }

    /** 探索记录中 VALIDATE 类型计数（§9） */
    private static long validateCount(Unit unit) {
        if (unit == null || unit.explorationRecords() == null) return 0;
        return unit.explorationRecords().stream()
                .filter(r -> r.declareType() == ExplorationRecord.DeclareType.VALIDATE).count();
    }

    // ============================================================
    // 缓存命中处理
    // ============================================================
    private void handleCacheHit(ModelRouter modelRouter, ToolCallback[] tools,
                                 String effectiveInput, String userInput,
                                 ToolPlanner.PlanResult plan, List<ToolCallLog> toolCallLogs,
                                 long myTurnId, TtsService ttsService) {
        Unit unit = plan.unit();

        // Step 1: AI 判断计划可用性 + 提取变量（探索模式走指定模型）
        ChatClient planMatchClient = explorationMode ? executionClient(modelRouter) : null;
        PlanMatcher.MatchResult matchResult = planMatchClient != null
                ? planMatcher.match(userInput, unit, planMatchClient)
                : planMatcher.match(userInput, unit);
        if (!matchResult.applicable()) {
            log.info("❌ 计划不适用（{}），降级为 AI 新规划", matchResult.reason());
            // 重新规划（绕过缓存），而不是复用旧缓存的 selectedToolNames —— 否则 AI 拿不到
            // 真正需要的工具（如写小说却只拿到"打开番茄网站"的旧工具 + searchTool）
            ToolPlanner.PlanResult freshPlan = toolPlanner.replan(userInput, tools, matchResult.reason());
            log.info("📦 降级重新规划: 选中 {} 个工具", freshPlan.selectedToolNames().size());
            handleNewPlan(modelRouter, tools, effectiveInput, userInput, freshPlan,
                    toolCallLogs, myTurnId, ttsService);
            return;
        }

        Map<String, String> variables = matchResult.variables();
        log.info("✅ 计划可用（变量: {}）", variables);

        // Step 2: 附加 notes + 失败警告到 prompt
        String augmentedInput = effectiveInput;
        if (unit.notes() != null && !unit.notes().isEmpty()) {
            augmentedInput += "\n\n--- 历史经验 ---\n" + String.join("\n", unit.notes());
        }
        if (plan.failureWarnings() != null && !plan.failureWarnings().isEmpty()) {
            StringBuilder warnBlock = new StringBuilder("\n\n--- ⚠️ 历史失败原因（请避免以下做法） ---\n");
            for (int i = 0; i < plan.failureWarnings().size(); i++) {
                var w = plan.failureWarnings().get(i);
                warnBlock.append((i + 1)).append(". [").append(w.category()).append("] ")
                        .append(w.reason()).append("\n");
                if (w.analysis() != null && !w.analysis().isBlank()) {
                    warnBlock.append("   建议: ").append(w.analysis()).append("\n");
                }
            }
            augmentedInput += warnBlock.toString();
        }

        // Step 3: 判断是否可脚本化
        if (unit.isScriptable()) {
            handleScriptableExecution(modelRouter, tools, effectiveInput, userInput, plan, unit,
                    variables, toolCallLogs, myTurnId, ttsService);
        } else {
            handleAiExecutionWithPlan(modelRouter, tools, augmentedInput, userInput, plan,
                    effectiveInput, toolCallLogs, myTurnId, ttsService);
        }
    }

    /** 脚本化执行 */
    private void handleScriptableExecution(ModelRouter modelRouter, ToolCallback[] tools,
                                            String effectiveInput, String userInput,
                                            ToolPlanner.PlanResult plan, Unit unit,
                                            Map<String, String> variables,
                                            List<ToolCallLog> toolCallLogs,
                                            long myTurnId, TtsService ttsService) {
        log.info("🚀 计划稳定度高（可脚本化），跳过 AI 直接执行脚本");
        PlanExecutor.ExecutionResult execResult = planExecutor.executeScript(unit.script(), variables, tools);

        if (execResult.success()) {
            toolCallLogs.addAll(execResult.executedSteps());
            if (currentAiTurnId == myTurnId) {
                toolPlanner.onCacheHitSuccess(userInput, plan);
                String response = "已按脚本完成（" + execResult.executedSteps().size() + " 步）";
                log.info("🤖 {}", response);
                speakIfPossible(ttsService, response);
            }
        } else {
            toolCallLogs.addAll(execResult.executedSteps());
            ReflectService.FailureAnalysis analysis = reflectService.reflectFailure(currentMode(), userInput, toolCallLogs, execResult.errorMessage());
            log.info("🔍 脚本归因: {}{}", (analysis.isPlanIssue() ? "计划问题" : "环境问题"),
                    (analysis.lesson() != null ? "（" + analysis.lesson() + "）" : ""));
            recordUnitFailures(toolCallLogs, analysis.lesson(), analysis.isPlanIssue(), userInput);
            unitFailureService.recordFailure(null, analysis.lesson(), analysis.isPlanIssue(), null);

            if (!analysis.isPlanIssue()) {
                int fromStep = execResult.failedStepIndex() + 1;
                if (fromStep < unit.script().size()) {
                    log.info("ℹ️ 脚本环境问题，从第 {} 步继续执行", (fromStep + 1));
                    PlanExecutor.ExecutionResult continueResult = planExecutor.executeFromStep(unit.script(), fromStep, variables, tools);
                    if (continueResult.success()) {
                        toolCallLogs.addAll(continueResult.executedSteps());
                        if (currentAiTurnId == myTurnId) {
                            toolPlanner.onCacheHitSuccess(userInput, plan);
                            int totalSteps = execResult.executedSteps().size() + continueResult.executedSteps().size();
                            speakIfPossible(ttsService, "已从失败处继续完成（共 " + totalSteps + " 步）");
                        }
                        return;
                    }
                    // 分段继续也失败，fall through to replan
                }
            }
            toolPlanner.onCacheHitFailure(userInput, plan, analysis.lesson(), analysis.isPlanIssue());
            handleCacheFailure(modelRouter, tools, effectiveInput, userInput, plan,
                    toolCallLogs, myTurnId, ttsService, execResult.errorMessage());
        }
    }

    /** AI 带参考计划执行 */
    private void handleAiExecutionWithPlan(ModelRouter modelRouter, ToolCallback[] tools,
                                            String augmentedInput, String userInput,
                                            ToolPlanner.PlanResult plan, String effectiveInput,
                                            List<ToolCallLog> toolCallLogs,
                                            long myTurnId, TtsService ttsService) {
        try {
            String response = executeWithTools(modelRouter, augmentedInput, tools, plan, toolCallLogs);
            if (currentAiTurnId == myTurnId) {
                var verify = reflectService.verifyExecution(currentMode(), userInput, toolCallLogs, response);
                if (!verify.success()) {
                    log.info("🔍 校验未通过: {}", verify.reason());
                    if (!verify.salvageableChains().isEmpty()) {
                        sedimentSalvageableChains(userInput, toolCallLogs, verify.salvageableChains());
                    }
                    throw new RuntimeException("校验未通过: " + verify.reason());
                }
                log.info("🤖 {}", response);
                toolPlanner.onCacheHitSuccess(userInput, plan);
                speakIfPossible(ttsService, response);
            }
        } catch (Exception e) {
            if (currentAiTurnId == myTurnId) {
                ReflectService.FailureAnalysis analysis = reflectService.reflectFailure(currentMode(), userInput, toolCallLogs, e.getMessage());
                log.info("🔍 归因: {}{}", (analysis.isPlanIssue() ? "计划问题" : "环境问题"),
                        (analysis.lesson() != null ? "（" + analysis.lesson() + "）" : ""));
                recordUnitFailures(toolCallLogs, analysis.lesson(), analysis.isPlanIssue(), userInput);
                toolPlanner.onCacheHitFailure(userInput, plan, analysis.lesson(), analysis.isPlanIssue());
                unitFailureService.recordFailure(null, analysis.lesson(), analysis.isPlanIssue(), null);

                if (!analysis.isPlanIssue()) {
                    log.info("ℹ️ 环境问题导致失败，分段继续执行");
                    String continuePrompt = buildContinuePrompt(userInput, e.getMessage(), analysis.lesson());
                    toolCallLogs.clear();
                    ToolPlanner.PlanResult fallbackPlan = toolPlanner.plan(userInput, tools);
                    String resp = executeWithTools(modelRouter, continuePrompt, tools, fallbackPlan, toolCallLogs);
                    if (currentAiTurnId == myTurnId) {
                        var verify = reflectService.verifyExecution(currentMode(), userInput, toolCallLogs, resp);
                        if (!verify.success()) {
                            log.info("⚠️ 分段继续校验未通过: {}", verify.reason());
                            if (!verify.salvageableChains().isEmpty()) {
                                sedimentSalvageableChains(userInput, toolCallLogs, verify.salvageableChains());
                            }
                            unitFailureService.recordFailure(null, verify.reason(), true, null);
                        } else {
                            log.info("🤖 {}", resp);
                            toolPlanner.onCacheHitSuccess(userInput, plan);
                            speakIfPossible(ttsService, resp);
                        }
                    }
                } else {
                    handleCacheFailure(modelRouter, tools, effectiveInput, userInput, plan,
                            toolCallLogs, myTurnId, ttsService, e.getMessage());
                }
            }
        }
    }

    // ========================================================================
    // 新规划处理（首次 + 降级）
    // ========================================================================

    private void handleNewPlan(ModelRouter modelRouter, ToolCallback[] tools,
                                String effectiveInput, String userInput,
                                ToolPlanner.PlanResult plan, List<ToolCallLog> toolCallLogs,
                                long myTurnId, TtsService ttsService) {
        try {
            String response = executeWithTools(modelRouter, effectiveInput, tools, plan, toolCallLogs);
            if (currentAiTurnId == myTurnId) {
                var verify = reflectService.verifyExecution(currentMode(), userInput, toolCallLogs, response);
                if (!verify.success()) {
                    log.info("🔍 校验未通过: {}", verify.reason());
                    if (!verify.salvageableChains().isEmpty()) {
                        sedimentSalvageableChains(userInput, toolCallLogs, verify.salvageableChains());
                    }
                    throw new RuntimeException("校验未通过: " + verify.reason());
                }

                log.info("🤖 {}", response);
                // 入库前校验：无实际工具调用的轨迹不入库
                boolean hasToolCalls = toolCallLogs.stream()
                        .anyMatch(tc -> tc.success() && !tc.toolName().equals("exploration_step"));
                String successLesson = hasToolCalls
                        ? reflectService.reflectSuccess(currentMode(), userInput, toolCallLogs, response)
                        : null;
                if (successLesson != null) {
                    log.info("📝 成功经验: {}", successLesson);
                } else {
                    log.info("📝 无有效工具调用/成功经验，轨迹不入库");
                }

                // 成功沉淀：把本次执行轨迹异步生成 PLAN_STEP Unit（图化记忆）
                if (hasToolCalls) {
                    unitSedimentationService.sediment(currentMode(), userInput, toolCallLogs, successLesson)
                            .thenAccept(created -> {
                                if (created) resyncCategoriesAfterSediment();
                            });
                }
                speakIfPossible(ttsService, response);
            } else {
                log.info("🔄 当前Turn已被打断，跳过TTS播报");
            }
        } catch (Exception e) {
            ChatClient reflectClient = executionClient(modelRouter);
            ReflectService.FailureAnalysis analysis = reflectClient != null
                    ? reflectService.reflectFailure(reflectClient, userInput, toolCallLogs, e.getMessage())
                    : reflectService.reflectFailure(currentMode(), userInput, toolCallLogs, e.getMessage());
            log.info("🔍 失败归因: {}{}", (analysis.isPlanIssue() ? "计划问题" : "环境问题"),
                    (analysis.lesson() != null ? "（" + analysis.lesson() + "）" : ""));
            recordUnitFailures(toolCallLogs, analysis.lesson(), analysis.isPlanIssue(), userInput);
            unitFailureService.recordFailure(null, analysis.lesson(), analysis.isPlanIssue(), null);
            log.info("❌ {}", e.getMessage());

            // 探索模式不重试执行，下轮巡检重新规划
            if (!analysis.isPlanIssue() && !explorationMode) {
                String continuePrompt = buildContinuePrompt(userInput, e.getMessage(), analysis.lesson());
                toolCallLogs.clear();
                String resp = executeWithTools(modelRouter, continuePrompt, tools, plan, toolCallLogs);
                if (currentAiTurnId == myTurnId) {
                    var verify = reflectService.verifyExecution(currentMode(), userInput, toolCallLogs, resp);
                    if (!verify.success()) {
                        log.info("⚠️ 分段继续校验未通过: {}", verify.reason());
                        if (!verify.salvageableChains().isEmpty()) {
                            sedimentSalvageableChains(userInput, toolCallLogs, verify.salvageableChains());
                        }
                        unitFailureService.recordFailure(null, verify.reason(), true, null);
                    } else {
                        log.info("🤖 {}", resp);
                        // 分段继续成功后沉淀
                        boolean hasToolCalls = toolCallLogs.stream()
                                .anyMatch(tc -> tc.success() && !tc.toolName().equals("exploration_step"));
                        if (hasToolCalls) {
                            String successLesson = reflectService.reflectSuccess(currentMode(), userInput, toolCallLogs, resp);
                            unitSedimentationService.sediment(currentMode(), userInput, toolCallLogs, successLesson)
                                    .thenAccept(created -> {
                                        if (created) resyncCategoriesAfterSediment();
                                    });
                        }
                        speakIfPossible(ttsService, resp);
                    }
                }
            } else if (!analysis.isPlanIssue() && explorationMode) {
                log.info("🔍 探索模式：跳过重试，下轮重新规划");
            }
        }
    }

    // ========================================================================
    // 缓存失败处理（重新规划）
    // ========================================================================

    private void handleCacheFailure(ModelRouter modelRouter, ToolCallback[] tools,
                                     String effectiveInput, String userInput,
                                     ToolPlanner.PlanResult oldPlan, List<ToolCallLog> toolCallLogs,
                                     long myTurnId, TtsService ttsService, String errorReason) {
        try {
            ToolPlanner.PlanResult newPlan = toolPlanner.replan(userInput, tools, errorReason);
            newPlan.missingDescriptions().forEach(desc ->
                    log.info("⚠️ 缺少工具: {}（可补写本地 @Tool）", desc));
            log.info("📦 重新选用工具: {}/{}", newPlan.selectedToolNames().size(), tools.length);

            String response = executeWithTools(modelRouter, effectiveInput, tools, newPlan, toolCallLogs);
            if (currentAiTurnId == myTurnId) {
                var verify = reflectService.verifyExecution(currentMode(), userInput, toolCallLogs, response);
                if (!verify.success()) {
                    log.info("🔍 重规划校验未通过: {}", verify.reason());
                    if (!verify.salvageableChains().isEmpty()) {
                        sedimentSalvageableChains(userInput, toolCallLogs, verify.salvageableChains());
                    }
                    throw new RuntimeException("重规划校验未通过: " + verify.reason());
                }
                log.info("🤖 {}", response);
                String successLesson = reflectService.reflectSuccess(currentMode(), userInput, toolCallLogs, response);
                boolean hasToolCalls = toolCallLogs.stream()
                        .anyMatch(tc -> tc.success() && !tc.toolName().equals("exploration_step"));
                if (hasToolCalls) {
                    unitSedimentationService.sediment(currentMode(), userInput, toolCallLogs, successLesson)
                            .thenAccept(created -> {
                                if (created) resyncCategoriesAfterSediment();
                            });
                }
                speakIfPossible(ttsService, response);
            }
        } catch (Exception retryEx) {
            if (currentAiTurnId == myTurnId) {
                log.info("❌ 重试失败: {}", retryEx.getMessage());
                unitFailureService.recordFailure(null, retryEx.getMessage(), true, null);
                speakIfPossible(ttsService, "抱歉，我没做好这个任务");
            }
        }
    }

    // ========================================================================
    // AI 执行（过滤工具 + 收集日志）
    // ========================================================================

    /** 扫描执行轨迹中失败的 planStep_ 包装工具，反查 unitId 记录失败；并把已成功前 N-1 步沉淀（§6）。 */
    private void recordUnitFailures(List<ToolCallLog> toolCallLogs, String failureLesson,
                                    boolean isPlanIssue, String userInput) {
        if (toolCallLogs == null) return;
        List<ToolCallLog> successfulPrefix = new ArrayList<>();
        boolean hadUnitFailure = false;
        for (ToolCallLog tc : toolCallLogs) {
            if (tc.success()) {
                successfulPrefix.add(tc);
                continue;
            }
            String unitId = universalUnitExecutor.resolveUnitId(tc.toolName());
            if (unitId != null) {
                hadUnitFailure = true;
                unitFailureService.recordFailure(unitId, failureLesson, isPlanIssue, tc.args());
            }
        }
        // §6 成功步骤提取：失败时把已成功的前 N-1 步沉淀为可复用 PLAN_STEP
        if (hadUnitFailure && !successfulPrefix.isEmpty()) {
            unitSedimentationService.sedimentSalvageable(currentMode(), userInput, successfulPrefix)
                    .thenAccept(created -> {
                        if (created) resyncCategoriesAfterSediment();
                    });
        }
    }

    /** 把校验失败时 AI 指出的可复用步骤链（工具索引）沉淀为 PLAN_STEP（替代旧 Episode 的 saveSalvageableChains）。 */
    private void sedimentSalvageableChains(String userInput, List<ToolCallLog> toolCallLogs,
                                           List<List<Integer>> chains) {
        if (chains == null || chains.isEmpty()) return;
        for (List<Integer> chain : chains) {
            List<ToolCallLog> steps = new ArrayList<>();
            for (int idx : chain) {
                if (idx >= 0 && idx < toolCallLogs.size()) {
                    ToolCallLog tc = toolCallLogs.get(idx);
                    if (tc.success()) steps.add(tc);
                }
            }
            if (!steps.isEmpty()) {
                unitSedimentationService.sedimentSalvageable(currentMode(), userInput, steps)
                        .thenAccept(created -> {
                            if (created) resyncCategoriesAfterSediment();
                        });
            }
        }
    }

    private String executeWithTools(ModelRouter modelRouter, String input,
                                    ToolCallback[] allTools, ToolPlanner.PlanResult plan,
                                    List<ToolCallLog> toolCallLogs) {
        // 设置本轮 listAllTools 是否放行：由规划阶段降级信号（分类轮次用尽/工具超限）决定（§4.1/§6）
        toolSearchService.setListAllToolsAllowed(plan.allowListAllTools());

        var selectedNames = new LinkedHashSet<>(plan.selectedToolNames());
        selectedNames.addAll(props.toolPlanner().alwaysAppendTools());

        // 探索模式：附加 fallback-tools（MCP核心动作工具），AI搜到就能调
        if (explorationMode) {
            selectedNames.addAll(props.toolPlanner().fallbackTools());
        }

        ToolCallback[] selectedTools = Arrays.stream(allTools)
                .filter(tc -> selectedNames.contains(tc.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);

        log.info("===== executeWithTools 开始 ===== selectedTools={}, promptSize={}字符",
                selectedTools.length, input.length());

        // Map 记录展示给 AI 的工具名 → 用于对比检测脑补工具
        Map<String, ToolCallback> shownToolMap = new LinkedHashMap<>();
        for (ToolCallback tc : selectedTools) {
            shownToolMap.put(tc.getToolDefinition().name(), tc);
        }

        // 构建全量工具名→ToolCallback 映射（用于脑补时快速查找）
        Map<String, ToolCallback> allToolMap = Arrays.stream(allTools)
                .collect(Collectors.toMap(tc -> tc.getToolDefinition().name(), tc -> tc, (a, b) -> a, LinkedHashMap::new));

        // 🔍 诊断：检查 ToolCallback 重名（Spring AI 用 name 做 key，重名会覆盖）
        Map<String, Long> nameCounts = Arrays.stream(allTools)
                .collect(Collectors.groupingBy(tc -> tc.getToolDefinition().name(), Collectors.counting()));
        List<String> dupes = nameCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        if (!dupes.isEmpty()) {
            log.warn("🔍 [诊断] ToolCallback 重名 ({} 个): {}", dupes.size(), dupes);
            for (String name : dupes) {
                Object[] classes = Arrays.stream(allTools)
                        .filter(tc -> tc.getToolDefinition().name().equals(name))
                        .map(tc -> tc.getClass().getSimpleName())
                        .toArray();
                log.warn("🔍 [诊断]   '{}' 实例类型: {}", name, java.util.Arrays.toString(classes));
            }
        }

        // 🔍 诊断：打印选不中的工具名（分类里有但 ToolCallback 名对不上）
        Set<String> allToolNameSet = allToolMap.keySet();
        List<String> notMatched = selectedNames.stream()
                .filter(n -> !allToolNameSet.contains(n))
                .toList();
        if (!notMatched.isEmpty()) {
            log.warn("🔍 [诊断] 选不中的工具名 ({}个): {}", notMatched.size(), notMatched);
            for (String missing : notMatched) {
                List<String> candidates = allToolNameSet.stream()
                        .filter(atn -> atn.toLowerCase().contains(missing.toLowerCase())
                                || missing.toLowerCase().contains(atn.toLowerCase()))
                        .toList();
                if (!candidates.isEmpty()) {
                    log.warn("🔍 [诊断]   '{}' 可能匹配: {}", missing, candidates);
                }
            }
        }

        // 初始只传选中工具给 Spring AI（不全量），脑补工具走异常拦截 → 查库 → 动态注册
        ToolCallback[] loggedTools = buildLoggedTools(shownToolMap, toolCallLogs);

        log.info("📦 选用工具: {}/{}{}", selectedTools.length, allTools.length,
                (plan.fromCache() ? " (缓存命中)" : ""));

        // 注入环境 + 工具参数信息到 prompt
        StringBuilder fullPrompt = new StringBuilder();
        fullPrompt.append("[系统环境] ").append(envService.getOsInfo()).append("\n\n");

        // 注入完整工具名列表（防止 LLM 截断/改编工具名，如 listMonitors → istMonitors）
        fullPrompt.append("[可用工具完整名称]\n");
        fullPrompt.append("以下是你可调用的工具，调用时请使用这些精确名称，不要截断、缩写或改编：\n");
        for (String name : shownToolMap.keySet()) {
            fullPrompt.append("- ").append(name).append("\n");
        }
        fullPrompt.append("\n");

        // 注入工具使用规则
        fullPrompt.append("[工具使用规则]\n");
        fullPrompt.append("- 你只能调用[可用工具完整名称]里列出的工具，不要凭空想象或虚构不存在的工具。\n");
        fullPrompt.append("- 如果要调用的工具不在列表中，请调用 searchTool(中文描述, 英文关键词) 搜索。\n");
        fullPrompt.append("- searchTool 支持按中文名、英文名、功能描述进行语义搜索，会返回匹配的工具及完整参数信息。\n");
        fullPrompt.append("- 如果 searchTool 也搜不到，可以调用 listAllTools 查看所有可用工具。\n");
        fullPrompt.append("- 当 searchTool 反复搜不到目标工具时，不要继续换关键词重搜；直接用系统命令完成：打开网页 cmd /c start <url>、打开文件 explorer <path>、运行命令 cmd /c <command>。\n");
        fullPrompt.append("- 调用工具时确保参数类型和名称与工具定义一致。\n");
        fullPrompt.append("- 每个关键操作执行后都必须立即验证结果是否达标，禁止在错误结果上继续后续操作：打开网页后核对窗口标题/内容是否为目标网站（不符就重开）、创建/下载文件后确认文件是否存在、安装软件后确认是否成功、发送消息后确认是否发出。\n\n");

        // 从注册表注入选中参数信息（qwen3:30b function calling 弱，直接放文本里）
        fullPrompt.append("[选中工具参数要求]\n");
        for (String name : selectedNames) {
            toolRegistry.findByName(name).ifPresent(m -> {
                if (!m.parameters().isEmpty()) {
                    fullPrompt.append("- ").append(name).append(": ");
                    for (int i = 0; i < m.parameters().size(); i++) {
                        ToolModel.ParamInfo p = m.parameters().get(i);
                        if (i > 0) fullPrompt.append(", ");
                        fullPrompt.append(p.name()).append("(").append(p.type()).append(")");
                        if (p.required()) fullPrompt.append("[必填]");
                    }
                    fullPrompt.append("\n");
                }
            });
        }
        fullPrompt.append("\n").append(input);

        // 脑补检测 + 动态注册重试循环
        int maxRetries = 5;
        Set<String> notifiedMissing = new LinkedHashSet<>();
        int hallucinationCount = 0;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                ChatClient client = executionClient(modelRouter);
                String result;
                int logCountBefore = toolCallLogs.size();
                log.info("  发送请求给AI... [attempt {}]", attempt);
                if (client != null) {
                    result = client.prompt().user(fullPrompt.toString()).toolCallbacks(loggedTools).call().content();
                } else {
                    result = modelRouter.chat(currentMode()).prompt().user(fullPrompt.toString()).toolCallbacks(loggedTools).call().content();
                }
                int newToolCalls = toolCallLogs.size() - logCountBefore;
                log.info("  AI返回: {}个工具调用, content={}字符",
                        newToolCalls, result != null ? result.length() : 0);
                if (hallucinationCount > 0) {
                    log.info("  🧠 脑补检测: 本回合共{}个异常工具", hallucinationCount);
                }
                log.info("===== executeWithTools 结束 =====");
                return result;
            } catch (Exception e) {
                String hallucinated = extractHallucinatedToolName(e);
                if (hallucinated == null) {
                    if (plan.fromCache()) {
                        throw new RuntimeException("🔄 缓存方案执行失败", e);
                    }
                    throw e;
                }

                // 检测：不在 shownToolMap 中 → 脑补工具
                if (shownToolMap.containsKey(hallucinated)) {
                    // 在展示列表中但执行失败 → 不是脑补，正常抛
                    throw e;
                }

                log.warn("🧠 AI脑补工具: '{}'（不在展示列表中，展示:{})", hallucinated, shownToolMap.keySet());
                hallucinationCount++;

                // 1. 精确匹配 allTools
                ToolCallback realTool = allToolMap.get(hallucinated);
                if (realTool != null) {
                    log.info("✅ 脑补工具 '{}' 精确命中，动态注册", hallucinated);
                    shownToolMap.put(hallucinated, realTool);
                    loggedTools = buildLoggedTools(shownToolMap, toolCallLogs);
                    fullPrompt.append("\n[系统提示] 工具 '").append(hallucinated)
                            .append("' 已自动找到并注册，可以继续调用。\n");
                    continue;
                }

                // 1.5 近似名纠错（工具名被截断：istMonitors → listMonitors、aptureScreen → captureScreen）
                String corrected = fuzzyMatchToolName(hallucinated, allToolMap.keySet());
                if (corrected != null) {
                    ToolCallback correctedTool = allToolMap.get(corrected);
                    log.info("✅ 脑补工具 '{}' 近似纠正为 '{}'，动态注册", hallucinated, corrected);
                    shownToolMap.put(corrected, correctedTool);
                    loggedTools = buildLoggedTools(shownToolMap, toolCallLogs);
                    fullPrompt.append("\n[系统提示] 工具名 '").append(hallucinated)
                            .append("' 疑似被截断，已纠正为 '").append(corrected)
                            .append("' 并注册。请用 '").append(corrected).append("' 继续调用。\n");
                    continue;
                }

                // 2. 语义搜索（通过 toolRegistry）
                List<ToolModel> found = toolRegistry.searchTools(hallucinated, 3, 0.5);
                if (!found.isEmpty()) {
                    List<String> foundNames = found.stream().map(ToolModel::name).toList();
                    log.info("🔍 脑补工具 '{}' 语义搜索命中: {}", hallucinated, foundNames);

                    // 从 allTools 中找到对应的 ToolCallback 并注册
                    int added = 0;
                    for (String name : foundNames) {
                        ToolCallback tc = allToolMap.get(name);
                        if (tc != null && shownToolMap.putIfAbsent(name, tc) == null) {
                            added++;
                        }
                    }
                    if (added > 0) {
                        loggedTools = buildLoggedTools(shownToolMap, toolCallLogs);
                        fullPrompt.append("\n[系统提示] 工具 '").append(hallucinated)
                                .append("' 不存在，但搜索到相似工具: ").append(foundNames)
                                .append("，已自动注册。请用这些工具替代。\n");
                        continue;
                    }
                }

                // 3. 搜不到 → 通知AI
                if (notifiedMissing.add(hallucinated)) {
                    log.warn("❌ 脑补工具 '{}' 不存在，通知AI重选", hallucinated);
                    fullPrompt.append("\n[系统提示] 工具 '").append(hallucinated)
                            .append("' 在系统中不存在。请调用 searchTool 搜索替代工具，或重新选择工具分类。"
                                    + "也可以尝试自行生成该工具（描述所需功能即可触发自动生成）。\n");
                }
                // 已通知过但AI还在尝试 → 继续重试（可能是其他脑补工具触发）
            }
        }

        throw new RuntimeException("脑补工具处理重试次数用尽（" + maxRetries + "次）");
    }

    /** 从 shownToolMap 构建 LoggingToolCallback 数组 */
    private ToolCallback[] buildLoggedTools(Map<String, ToolCallback> toolMap, List<ToolCallLog> toolCallLogs) {
        return toolMap.values().stream()
                .map(tc -> new LoggingToolCallback(tc, toolCallLogs, generatedToolRegistry))
                .toArray(ToolCallback[]::new);
    }

    /** Spring AI 抛出的 "No ToolCallback found for tool name: XXX" 异常前缀（含尾部空格，长度 37）。 */
    private static final String NO_TOOL_CALLBACK_PREFIX = "No ToolCallback found for tool name: ";

    /** 从异常中提取脑补工具名。只识别 Spring AI "No ToolCallback found" 异常（含嵌套）。 */
    private String extractHallucinatedToolName(Throwable e) {
        if (e == null) return null;
        String msg = e.getMessage();
        if (msg != null && msg.contains(NO_TOOL_CALLBACK_PREFIX)) {
            // 不能用写死的 +38：前缀实际 37 字符，+38 会吞掉工具名首字母（getWindowBounds→etWindowBounds）
            return msg.substring(msg.indexOf(NO_TOOL_CALLBACK_PREFIX) + NO_TOOL_CALLBACK_PREFIX.length()).trim();
        }
        // 递归查 cause
        return extractHallucinatedToolName(e.getCause());
    }

    /**
     * 近似名纠错：把被截断的工具名（如 istMonitors）纠正回真实名（listMonitors）。
     * 优先子串匹配（首字母丢失即真实名包含输入名），再退化到编辑距离 ≤ 1。
     */
    private String fuzzyMatchToolName(String input, Set<String> candidates) {
        if (input == null || input.isBlank() || candidates == null || candidates.isEmpty()) return null;
        String lower = input.toLowerCase();

        // 子串关系：istMonitors ⊂ listMonitors（截断恢复），长度 ≥3 避免短词误匹配
        for (String c : candidates) {
            if (c != null && c.toLowerCase().contains(lower) && lower.length() >= 3) {
                return c;
            }
        }

        // 编辑距离 ≤ 1（单字符丢失/替换/插入）
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String c : candidates) {
            if (c == null) continue;
            int dist = levenshtein(lower, c.toLowerCase());
            if (dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return bestDist <= 1 ? best : null;
    }

    /** 编辑距离（Levenshtein），用于工具名近似匹配。 */
    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    private String buildContinuePrompt(String originalInput, String errorMsg, String failureLesson) {
        StringBuilder sb = new StringBuilder(originalInput);
        sb.append("\n\n--- 分段继续执行 ---\n");
        sb.append("上次执行失败: ").append(errorMsg).append("\n");
        if (failureLesson != null && !failureLesson.isEmpty()) {
            sb.append("注意: ").append(failureLesson).append("\n");
        }
        sb.append("请继续完成上述任务，忽略已完成的步骤。");
        return sb.toString();
    }

    // ============================================================
    // 工具分类缓存同步
    // ============================================================

    /** TTS null-safe 包装 */
    private void speakIfPossible(TtsService ttsService, String text) {
        if (ttsService != null) {
            ttsService.speakAsync(text);
        }
    }

    /**
     * 探索模式入口：与 process() 共用同一管线（缓存→规划→执行→反思），不触发 TTS 播报、不重试执行。
     * 模型选择由 myhelper.exploration.exploration-model 统一控制（model1/model2/默认）。
     */
    public void processExploration(ModelRouter modelRouter, ToolCallback[] baseTools, String userInput) {
        this.explorationMode = true;
        try {
            process(modelRouter, baseTools, userInput, null, true);
        } finally {
            this.explorationMode = false;
        }
    }

    /** 执行客户端 — 统一走 ModelRouter.chat(mode)，不读配置 */
    private ChatClient executionClient(ModelRouter modelRouter) {
        return modelRouter.chat(currentMode());
    }
}
