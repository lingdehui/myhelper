package com.example.desktopbrain.service;

import com.example.desktopbrain.autogen.GeneratedToolRegistry;
import com.example.desktopbrain.config.DesktopBrainProperties;
import com.example.desktopbrain.config.SystemEnvironmentService;
import com.example.desktopbrain.registry.ToolModel;
import com.example.desktopbrain.registry.ToolRegistry;
import com.example.desktopbrain.service.SkillConfig;
import com.example.desktopbrain.memory.vector.episode.*;
import com.example.desktopbrain.config.ModelRouter;
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
 * AI Turn 处理器 —— 从 {@code DesktopBrainApplication} 抽离出的核心 AI 对话处理逻辑。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>技能匹配 → 注入 prompt</li>
 *   <li>工具规划（三层缓存）→ 缺失工具自动生成</li>
 *   <li>缓存命中 → PlanMatcher 判可用 → 脚本执行 / AI 带参考计划执行</li>
 *   <li>新规划 → DRAFT → 执行 → Reflect → ACTIVE/FAILED</li>
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
    private final FailureExperienceHandler failureExperienceHandler;
    private final GeneratedToolRegistry generatedToolRegistry;
    private final ToolSearchService toolSearchService;
    private final ToolRegistry toolRegistry;
    private final SkillConfig skillConfig;
    private final DesktopBrainProperties props;
    private final SystemEnvironmentService envService;
    private final EpisodeCacheService episodeCacheService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // ========== AI 中断控制 ==========
    private final AtomicLong aiTurnId = new AtomicLong(0);
    private volatile long currentAiTurnId = -1;
    private volatile int silenceCount = 0;

    /** 动态工具数量（上次检查时的值，用于判断是否需要重同步分类） */
    private volatile int lastDynamicToolCount = 0;

    /** 当前 Turn 是否为探索模式（由 processExploration 设置，process 结束时复位） */
    private boolean explorationMode = false;

    private ModelRouter.Mode currentMode() {
        return explorationMode ? ModelRouter.Mode.EXPLORATION : ModelRouter.Mode.NORMAL;
    }

    public TurnProcessor(ToolPlanner toolPlanner,
                          PlanMatcher planMatcher,
                          ReflectService reflectService,
                          PlanExecutor planExecutor,
                          FailureExperienceHandler failureExperienceHandler,
                          GeneratedToolRegistry generatedToolRegistry,
                          ToolSearchService toolSearchService,
                          ToolRegistry toolRegistry,
                          SkillConfig skillConfig,
                          DesktopBrainProperties props,
                          SystemEnvironmentService envService,
                          EpisodeCacheService episodeCacheService) {
        this.toolPlanner = toolPlanner;
        this.planMatcher = planMatcher;
        this.reflectService = reflectService;
        this.planExecutor = planExecutor;
        this.failureExperienceHandler = failureExperienceHandler;
        this.generatedToolRegistry = generatedToolRegistry;
        this.toolSearchService = toolSearchService;
        this.toolRegistry = toolRegistry;
        this.skillConfig = skillConfig;
        this.props = props;
        this.envService = envService;
        this.episodeCacheService = episodeCacheService;
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
        try {
            if (plan.fromCache() && plan.episode() != null) {
                handleCacheHit(modelRouter, tools, effectiveInput, userInput, plan, toolCallLogs, myTurnId, ttsService);
            } else {
                handleNewPlan(modelRouter, tools, effectiveInput, userInput, plan, toolCallLogs, myTurnId, ttsService);
            }
        } catch (Exception e) {
            log.error("❌ AI 调用失败（模型可能暂时不可用）", e);
            log.info("🤖 抱歉，模型暂时不可用，请稍后再试。");
        }

        if (currentAiTurnId == myTurnId) {
            currentAiTurnId = -1;
            silenceCount = 0;
        }
    }

    // ========================================================================
    // 工具管理
    // ========================================================================

    /** 合并静态工具 + 动态生成工具 */
    private ToolCallback[] mergeDynamicTools(ToolCallback[] baseTools) {
        ToolCallback[] dynamics = generatedToolRegistry.getDynamicTools();
        if (dynamics.length == 0) return baseTools;
        Map<String, ToolCallback> unique = new LinkedHashMap<>();
        for (ToolCallback t : baseTools) unique.putIfAbsent(t.getToolDefinition().name(), t);
        for (ToolCallback t : dynamics) unique.putIfAbsent(t.getToolDefinition().name(), t);
        return unique.values().toArray(new ToolCallback[0]);
    }

    /** 如果动态工具有新增，触发 Qdrant 分类重同步 */
    private void syncCategoriesIfNewTools(ToolCallback[] currentTools) {
        int currentDynamicCount = generatedToolRegistry.getDynamicTools().length;
        if (currentDynamicCount > lastDynamicToolCount) {
            lastDynamicToolCount = currentDynamicCount;
            log.info("🔄 检测到新动态工具，重同步分类（强制刷新）...");
            int catCount = toolPlanner.syncCategories(currentTools, true);
            if (catCount > 0) log.info("📁 工具分类已重同步: {} 类（含 {} 个动态工具）", catCount, currentDynamicCount);
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
        Episode episode = plan.episode();
        if (episode == null) {
            log.info("🔄 探索缓存命中但无 Episode，走新规划");
            return ToolPlanner.PlanResult.ofAIPlan(List.of(), List.of());
        }

        // 1. 问 AI：调试还是优化？
        String decision = askExploreDecision(modelRouter, userInput, episode);
        if (decision == null) {
            log.info("🔄 AI 决策失败，默认走新规划");
            return ToolPlanner.PlanResult.ofAIPlan(plan.selectedToolNames(), plan.missingDescriptions());
        }

        boolean isDebug = "debug".equalsIgnoreCase(decision);

        if (isDebug) {
            log.info("🔧 探索: AI 选择调试已有计划");
            episodeCacheService.incrementDebugCount(plan.episodeId());
            return plan; // 保留缓存，走 handleCacheHit
        }

        // 2. 优化分支：检查权重
        log.info("📝 探索: AI 选择优化已有计划，检查权重...");
        if (toolPlanner.isWorthOptimizing(episode)) {
            log.info("✅ 优化权重达标，优化现有计划");
            episodeCacheService.incrementOptimizeCount(plan.episodeId());
            return plan; // 保留缓存，走 handleCacheHit
        }

        log.info("🆕 优化权重不达标，走新规划");
        return ToolPlanner.PlanResult.ofAIPlan(List.of(), List.of());
    }

    /** 询问 AI：对缓存中的计划是调试还是优化？返回 "debug" 或 "optimize"，失败返回 null */
    private String askExploreDecision(ModelRouter modelRouter, String userInput, Episode episode) {
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
                episode.successLesson() != null ? episode.successLesson() : episode.userInput(),
                String.join(", ", episode.selectedToolNames()),
                episode.successCount(), episode.failureCount(),
                episode.exploreOptimizeCount(), episode.exploreDebugCount());

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

    // ============================================================
    // 缓存命中处理
    // ============================================================
    private void handleCacheHit(ModelRouter modelRouter, ToolCallback[] tools,
                                 String effectiveInput, String userInput,
                                 ToolPlanner.PlanResult plan, List<ToolCallLog> toolCallLogs,
                                 long myTurnId, TtsService ttsService) {
        Episode episode = plan.episode();

        // Step 1: AI 判断计划可用性 + 提取变量（探索模式走指定模型）
        ChatClient planMatchClient = explorationMode ? executionClient(modelRouter) : null;
        PlanMatcher.MatchResult matchResult = planMatchClient != null
                ? planMatcher.match(userInput, episode, planMatchClient)
                : planMatcher.match(userInput, episode);
        if (!matchResult.applicable()) {
            log.info("❌ 计划不适用（{}），降级为 AI 新规划", matchResult.reason());
            handleNewPlan(modelRouter, tools, effectiveInput, userInput,
                    ToolPlanner.PlanResult.ofAIPlan(plan.selectedToolNames(), plan.missingDescriptions()),
                    toolCallLogs, myTurnId, ttsService);
            return;
        }

        Map<String, String> variables = matchResult.variables();
        log.info("✅ 计划可用（变量: {}）", variables);

        // Step 2: 附加 lesson + 失败警告到 prompt
        String augmentedInput = effectiveInput;
        if (episode.successLesson() != null && !episode.successLesson().isEmpty()) {
            augmentedInput += "\n\n--- 历史成功经验 ---\n上次类似任务成功经验: " + episode.successLesson();
        }
        if (episode.failureLesson() != null && !episode.failureLesson().isEmpty()) {
            augmentedInput += "\n\n--- 历史失败教训 ---\n注意避免: " + episode.failureLesson();
        }
        if (plan.failureWarnings() != null && !plan.failureWarnings().isEmpty()) {
            StringBuilder warnBlock = new StringBuilder("\n\n--- ⚠️ 历史失败警告（请避免以下做法） ---\n");
            for (int i = 0; i < plan.failureWarnings().size(); i++) {
                var w = plan.failureWarnings().get(i);
                warnBlock.append((i + 1)).append(". ").append(w.type())
                        .append("（失败 ").append(w.count()).append(" 次）\n")
                        .append("   描述: ").append(w.description()).append("\n");
                if (w.mitigation() != null && !w.mitigation().isBlank()) {
                    warnBlock.append("   建议: ").append(w.mitigation()).append("\n");
                }
            }
            augmentedInput += warnBlock.toString();
        }

        // Step 3: 判断是否可脚本化
        if (episode.isScriptable()) {
            handleScriptableExecution(modelRouter, tools, effectiveInput, userInput, plan, episode,
                    variables, toolCallLogs, myTurnId, ttsService);
        } else {
            handleAiExecutionWithPlan(modelRouter, tools, augmentedInput, userInput, plan,
                    effectiveInput, toolCallLogs, myTurnId, ttsService);
        }
    }

    /** 脚本化执行 */
    private void handleScriptableExecution(ModelRouter modelRouter, ToolCallback[] tools,
                                            String effectiveInput, String userInput,
                                            ToolPlanner.PlanResult plan, Episode episode,
                                            Map<String, String> variables,
                                            List<ToolCallLog> toolCallLogs,
                                            long myTurnId, TtsService ttsService) {
        log.info("🚀 计划稳定度高（可脚本化），跳过 AI 直接执行脚本");
        PlanExecutor.ExecutionResult execResult = planExecutor.executeScript(episode, variables, tools);

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
            failureExperienceHandler.handle(userInput, analysis.lesson(), analysis.isPlanIssue(), false, plan.selectedToolNames());

            if (!analysis.isPlanIssue()) {
                int fromStep = execResult.failedStepIndex() + 1;
                if (fromStep < episode.toolCalls().size()) {
                    log.info("ℹ️ 脚本环境问题，从第 {} 步继续执行", (fromStep + 1));
                    PlanExecutor.ExecutionResult continueResult = planExecutor.executeFromStep(episode, fromStep, variables, tools);
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
                        toolPlanner.saveSalvageableChains(userInput, toolCallLogs,
                                verify.salvageableChains(), plan.episode().id());
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
                toolPlanner.onCacheHitFailure(userInput, plan, analysis.lesson(), analysis.isPlanIssue());
                failureExperienceHandler.handle(userInput, analysis.lesson(), analysis.isPlanIssue(), false, plan.selectedToolNames());

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
                                toolPlanner.saveSalvageableChains(userInput, toolCallLogs,
                                        verify.salvageableChains(), plan.episode().id());
                            }
                            failureExperienceHandler.handle(userInput, verify.reason(), true,
                                    !verify.salvageableChains().isEmpty(), plan.selectedToolNames());
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
        String draftId = toolPlanner.createDraftEpisode(userInput, plan);
        if (draftId == null) {
            log.info("⚠️ DRAFT 创建失败（Qdrant 不可用），本次不保存 Episode");
        }

        boolean turnedAway = false;  // 被其他turn打断（仅影响TTS播报，不影响DRAFT清理）
        try {
            String response = executeWithTools(modelRouter, effectiveInput, tools, plan, toolCallLogs);
            if (currentAiTurnId == myTurnId) {
                var verify = reflectService.verifyExecution(currentMode(), userInput, toolCallLogs, response);
                if (!verify.success()) {
                    log.info("🔍 校验未通过: {}", verify.reason());
                    if (!verify.salvageableChains().isEmpty()) {
                        toolPlanner.saveSalvageableChains(userInput, toolCallLogs,
                                verify.salvageableChains(), draftId);
                    }
                    throw new RuntimeException("校验未通过: " + verify.reason());
                }

                log.info("🤖 {}", response);
                // 入库前校验：无实际工具调用的 draft 不入库
                boolean hasToolCalls = toolCallLogs.stream()
                        .anyMatch(tc -> tc.success() && !tc.toolName().equals("exploration_step"));
                String successLesson = hasToolCalls
                        ? reflectService.reflectSuccess(currentMode(), userInput, toolCallLogs, response)
                        : null;
                if (successLesson != null) {
                    log.info("📝 成功经验: {}", successLesson);
                } else {
                    log.info("📝 无有效工具调用/成功经验，draft 不入库");
                }

                if (draftId != null && successLesson != null) {
                    toolPlanner.activateDraftEpisode(draftId, toolCallLogs, response, successLesson);
                    toolPlanner.cacheToMemory(userInput, plan, draftId, toolCallLogs, response, successLesson);
                }
                speakIfPossible(ttsService, response);
            } else {
                turnedAway = true;
                log.info("🔄 当前Turn已被打断，跳过TTS播报，但仍处理DRAFT...");
            }
        } catch (Exception e) {
            // DRAFT→FAILED 永远执行（即使被其他turn打断），避免DRAFT泄漏
            ChatClient reflectClient = executionClient(modelRouter);
            ReflectService.FailureAnalysis analysis = reflectClient != null
                    ? reflectService.reflectFailure(reflectClient, userInput, toolCallLogs, e.getMessage())
                    : reflectService.reflectFailure(currentMode(), userInput, toolCallLogs, e.getMessage());
            log.info("🔍 失败归因: {}{}", (analysis.isPlanIssue() ? "计划问题" : "环境问题"),
                    (analysis.lesson() != null ? "（" + analysis.lesson() + "）" : ""));
            toolPlanner.failDraftEpisode(draftId, toolCallLogs, analysis.lesson(), -1);
            failureExperienceHandler.handle(userInput, analysis.lesson(), analysis.isPlanIssue(), false, plan.selectedToolNames());
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
                            toolPlanner.saveSalvageableChains(userInput, toolCallLogs,
                                    verify.salvageableChains(), draftId);
                        }
                        failureExperienceHandler.handle(userInput, verify.reason(), true,
                                !verify.salvageableChains().isEmpty(), plan.selectedToolNames());
                    } else {
                        log.info("🤖 {}", resp);
                        toolPlanner.activateDraftEpisode(draftId, toolCallLogs, resp, null);
                        toolPlanner.cacheToMemory(userInput, plan, draftId, toolCallLogs, resp, null);
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

            String newDraftId = toolPlanner.createDraftEpisode(userInput, newPlan);
            String response = executeWithTools(modelRouter, effectiveInput, tools, newPlan, toolCallLogs);
            if (currentAiTurnId == myTurnId) {
                var verify = reflectService.verifyExecution(currentMode(), userInput, toolCallLogs, response);
                if (!verify.success()) {
                    log.info("🔍 重规划校验未通过: {}", verify.reason());
                    if (!verify.salvageableChains().isEmpty()) {
                        toolPlanner.saveSalvageableChains(userInput, toolCallLogs,
                                verify.salvageableChains(), newDraftId);
                    }
                    throw new RuntimeException("重规划校验未通过: " + verify.reason());
                }
                log.info("🤖 {}", response);
                String successLesson = reflectService.reflectSuccess(currentMode(), userInput, toolCallLogs, response);
                toolPlanner.activateDraftEpisode(newDraftId, toolCallLogs, response, successLesson);
                toolPlanner.cacheToMemory(userInput, newPlan, newDraftId, toolCallLogs, response, successLesson);
                speakIfPossible(ttsService, response);
            }
        } catch (Exception retryEx) {
            if (currentAiTurnId == myTurnId) {
                log.info("❌ 重试失败: {}", retryEx.getMessage());
                failureExperienceHandler.handle(userInput, retryEx.getMessage(), true, false, oldPlan.selectedToolNames());
                speakIfPossible(ttsService, "抱歉，我没做好这个任务");
            }
        }
    }

    // ========================================================================
    // AI 执行（过滤工具 + 收集日志）
    // ========================================================================

    private String executeWithTools(ModelRouter modelRouter, String input,
                                    ToolCallback[] allTools, ToolPlanner.PlanResult plan,
                                    List<ToolCallLog> toolCallLogs) {
        var selectedNames = new LinkedHashSet<>(plan.selectedToolNames());
        selectedNames.addAll(props.toolPlanner().alwaysAppendTools());

        // 探索模式：附加 fallback-tools（MCP核心动作工具），AI搜到就能调
        if (explorationMode) {
            selectedNames.addAll(props.toolPlanner().fallbackTools());
        }

        ToolCallback[] selectedTools = Arrays.stream(allTools)
                .filter(tc -> selectedNames.contains(tc.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);

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

        // 注入工具使用规则
        fullPrompt.append("[工具使用规则]\n");
        fullPrompt.append("- 你只能调用上面列出的工具，不要凭空想象或虚构不存在的工具。\n");
        fullPrompt.append("- 如果要调用的工具不在列表中，请调用 searchTool(中文描述, 英文关键词) 搜索。\n");
        fullPrompt.append("- searchTool 支持按中文名、英文名、功能描述进行语义搜索，会返回匹配的工具及完整参数信息。\n");
        fullPrompt.append("- 如果 searchTool 也搜不到，可以调用 listAllTools 查看所有可用工具。\n");
        fullPrompt.append("- 调用工具时确保参数类型和名称与工具定义一致。\n\n");

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

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                ChatClient client = executionClient(modelRouter);
                String result;
                if (client != null) {
                    result = client.prompt().user(fullPrompt.toString()).toolCallbacks(loggedTools).call().content();
                } else {
                    result = modelRouter.chat(currentMode()).prompt().user(fullPrompt.toString()).toolCallbacks(loggedTools).call().content();
                }
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

    /** 从异常中提取脑补工具名。只识别 Spring AI "No ToolCallback found" 异常（含嵌套）。 */
    private String extractHallucinatedToolName(Throwable e) {
        if (e == null) return null;
        String msg = e.getMessage();
        if (msg != null && msg.contains("No ToolCallback found for tool name:")) {
            return msg.substring(msg.indexOf("No ToolCallback found for tool name:") + 38).trim();
        }
        // 递归查 cause
        return extractHallucinatedToolName(e.getCause());
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
     * 模型选择由 desktopbrain.exploration.exploration-model 统一控制（model1/model2/默认）。
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
