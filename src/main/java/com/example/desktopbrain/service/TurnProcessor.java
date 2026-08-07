package com.example.desktopbrain.service;

import com.example.desktopbrain.autogen.GeneratedToolRegistry;
import com.example.desktopbrain.config.DesktopBrainProperties;
import com.example.desktopbrain.service.SkillConfig;
import com.example.desktopbrain.memory.vector.episode.*;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

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

    // ========== 依赖 ==========
    private final ToolPlanner toolPlanner;
    private final PlanMatcher planMatcher;
    private final ReflectService reflectService;
    private final PlanExecutor planExecutor;
    private final FailureExperienceHandler failureExperienceHandler;
    private final GeneratedToolRegistry generatedToolRegistry;
    private final ToolSearchService toolSearchService;
    private final SkillConfig skillConfig;
    private final DesktopBrainProperties props;

    // ========== AI 中断控制 ==========
    private final AtomicLong aiTurnId = new AtomicLong(0);
    private volatile long currentAiTurnId = -1;
    private volatile int silenceCount = 0;

    /** 动态工具数量（上次检查时的值，用于判断是否需要重同步分类） */
    private volatile int lastDynamicToolCount = 0;

    public TurnProcessor(ToolPlanner toolPlanner,
                          PlanMatcher planMatcher,
                          ReflectService reflectService,
                          PlanExecutor planExecutor,
                          FailureExperienceHandler failureExperienceHandler,
                          GeneratedToolRegistry generatedToolRegistry,
                          ToolSearchService toolSearchService,
                          SkillConfig skillConfig,
                          DesktopBrainProperties props) {
        this.toolPlanner = toolPlanner;
        this.planMatcher = planMatcher;
        this.reflectService = reflectService;
        this.planExecutor = planExecutor;
        this.failureExperienceHandler = failureExperienceHandler;
        this.generatedToolRegistry = generatedToolRegistry;
        this.toolSearchService = toolSearchService;
        this.skillConfig = skillConfig;
        this.props = props;
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

    /** 工具搜索服务初始化（供 DBA 启动时调用） */
    public void initToolSearch(ToolCallback[] allTools) {
        toolSearchService.updateTools(allTools);
    }

    /** 动态 ClassLoader 初始化 */
    public void initDynamicClassLoader() {
        generatedToolRegistry.initDynamicClassLoader();
    }

    /** 触发工具分类同步 */
    public int syncCategories(ToolCallback[] tools) {
        return toolPlanner.syncCategories(tools);
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
     * @param chatClient AI ChatClient（每次重建以确保工具列表新鲜）
     * @param baseTools  基础工具列表（MCP + 本地 @Tool）
     * @param userInput  用户输入
     * @param ttsService TTS 服务（用于播报）
     */
    public void process(ChatClient chatClient, ToolCallback[] baseTools,
                         String userInput, TtsService ttsService) {
        long myTurnId = aiTurnId.incrementAndGet();
        currentAiTurnId = myTurnId;
        silenceCount = 0;

        // 刷新工具列表（包含运行时动态加载的工具）
        ToolCallback[] tools = mergeDynamicTools(baseTools);
        syncCategoriesIfNewTools(tools);

        System.out.println("🤖 思考中...");

        String effectiveInput = userInput;
        List<ToolCallLog> toolCallLogs = Collections.synchronizedList(new ArrayList<>());

        // 1. 技能匹配
        String skillInstructions = skillConfig.getInstructions(userInput);
        if (!skillInstructions.isEmpty()) {
            effectiveInput = skillInstructions + "\n用户请求：" + userInput;
            System.out.println("📋 已注入技能: " + skillConfig.getMatchedSkillNames(userInput));
        }

        // 2. 工具规划（三层缓存）
        ToolPlanner.PlanResult plan = toolPlanner.plan(userInput, tools);

        // 工具缺失 → 关键词兜底 / AI 自动生成
        if (!plan.missingDescriptions().isEmpty()) {
            List<String> unresolved = new ArrayList<>();
            for (String desc : plan.missingDescriptions()) {
                List<String> found = ToolPlanner.findToolsByKeywords(List.of(desc), tools);
                if (!found.isEmpty()) {
                    System.out.println("🔎 缓存 MISSING '" + desc + "' → 已有工具: " + found);
                    plan.selectedToolNames().addAll(found);
                } else {
                    unresolved.add(desc);
                    if (!plan.fromCache()) {
                        System.out.println("⚠️ 缺少工具: " + desc + "（尝试让 AI 自动生成）");
                        triggerToolGeneration(desc, ttsService);
                    } else {
                        System.out.println("⚠️ 缓存命中但缺少工具: " + desc + "（让 AI 用 searchTool 查找）");
                    }
                }
            }
            plan.missingDescriptions().retainAll(unresolved);
        }

        // 3. 命中缓存 → 走缓存逻辑；未命中 → 新规划
        try {
            if (plan.fromCache() && plan.episode() != null) {
                handleCacheHit(chatClient, tools, effectiveInput, userInput, plan, toolCallLogs, myTurnId, ttsService);
            } else {
                handleNewPlan(chatClient, tools, effectiveInput, userInput, plan, toolCallLogs, myTurnId, ttsService);
            }
        } catch (Exception e) {
            System.err.println("❌ AI 调用失败（模型可能暂时不可用）: " + e.getMessage());
            System.out.println("🤖 抱歉，模型暂时不可用，请稍后再试。");
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
        ToolCallback[] merged = Arrays.copyOf(baseTools, baseTools.length + dynamics.length);
        System.arraycopy(dynamics, 0, merged, baseTools.length, dynamics.length);
        toolSearchService.updateTools(merged);
        return merged;
    }

    /** 如果动态工具有新增，触发 Qdrant 分类重同步 */
    private void syncCategoriesIfNewTools(ToolCallback[] currentTools) {
        int currentDynamicCount = generatedToolRegistry.getDynamicTools().length;
        if (currentDynamicCount > lastDynamicToolCount) {
            lastDynamicToolCount = currentDynamicCount;
            System.out.println("🔄 检测到新动态工具，重同步分类...");
            int catCount = toolPlanner.syncCategories(currentTools);
            if (catCount > 0) System.out.println("📁 工具分类已重同步: " + catCount + " 类（含 " + currentDynamicCount + " 个动态工具）");
        }
    }

    /**
     * 异步触发生成新工具（"工具缺失 → 自己写工具 → 即时生效"）。
     * 不阻塞当前请求执行。生成完成即时生效，无需重启。
     */
    private void triggerToolGeneration(String description, TtsService ttsService) {
        CompletableFuture.runAsync(() -> {
            try {
                System.out.println("🔧 开始自动生成工具: " + description);
                GeneratedToolRegistry.GenerationOutcome outcome =
                        generatedToolRegistry.generateAndPersist(description);
                if (outcome.success()) {
                    String msg = outcome.message();
                    if (!msg.contains("跳过重复生成") && !msg.contains("已存在")) {
                        ttsService.speakAsync("检测到缺失能力，" + msg);
                    }
                } else {
                    System.out.println("⚠️ 工具自动生成失败: " + outcome.message());
                    ttsService.speakAsync("工具自动生成失败，" + outcome.message());
                }
            } catch (Exception e) {
                System.err.println("❌ 工具生成异步任务异常: " + e.getMessage());
            }
        });
    }

    // ========================================================================
    // 缓存命中处理
    // ========================================================================

    private void handleCacheHit(ChatClient chatClient, ToolCallback[] tools,
                                 String effectiveInput, String userInput,
                                 ToolPlanner.PlanResult plan, List<ToolCallLog> toolCallLogs,
                                 long myTurnId, TtsService ttsService) {
        Episode episode = plan.episode();

        // Step 1: AI 判断计划可用性 + 提取变量
        PlanMatcher.MatchResult matchResult = planMatcher.match(userInput, episode);
        if (!matchResult.applicable()) {
            System.out.println("❌ 计划不适用（" + matchResult.reason() + "），降级为 AI 新规划");
            handleNewPlan(chatClient, tools, effectiveInput, userInput,
                    ToolPlanner.PlanResult.ofAIPlan(plan.selectedToolNames(), plan.missingDescriptions()),
                    toolCallLogs, myTurnId, ttsService);
            return;
        }

        Map<String, String> variables = matchResult.variables();
        System.out.println("✅ 计划可用（变量: " + variables + "）");

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
            handleScriptableExecution(chatClient, tools, effectiveInput, userInput, plan, episode,
                    variables, toolCallLogs, myTurnId, ttsService);
        } else {
            handleAiExecutionWithPlan(chatClient, tools, augmentedInput, userInput, plan,
                    effectiveInput, toolCallLogs, myTurnId, ttsService);
        }
    }

    /** 脚本化执行 */
    private void handleScriptableExecution(ChatClient chatClient, ToolCallback[] tools,
                                            String effectiveInput, String userInput,
                                            ToolPlanner.PlanResult plan, Episode episode,
                                            Map<String, String> variables,
                                            List<ToolCallLog> toolCallLogs,
                                            long myTurnId, TtsService ttsService) {
        System.out.println("🚀 计划稳定度高（可脚本化），跳过 AI 直接执行脚本");
        PlanExecutor.ExecutionResult execResult = planExecutor.executeScript(episode, variables, tools);

        if (execResult.success()) {
            toolCallLogs.addAll(execResult.executedSteps());
            if (currentAiTurnId == myTurnId) {
                toolPlanner.onCacheHitSuccess(userInput, plan);
                String response = "已按脚本完成（" + execResult.executedSteps().size() + " 步）";
                System.out.println("🤖 " + response);
                ttsService.speakAsync(response);
            }
        } else {
            toolCallLogs.addAll(execResult.executedSteps());
            ReflectService.FailureAnalysis analysis = reflectService.reflectFailure(userInput, toolCallLogs, execResult.errorMessage());
            System.out.println("🔍 脚本归因: " + (analysis.isPlanIssue() ? "计划问题" : "环境问题") +
                    (analysis.lesson() != null ? "（" + analysis.lesson() + "）" : ""));
            failureExperienceHandler.handle(userInput, analysis.lesson(), analysis.isPlanIssue(), false, plan.selectedToolNames());

            if (!analysis.isPlanIssue()) {
                int fromStep = execResult.failedStepIndex() + 1;
                if (fromStep < episode.toolCalls().size()) {
                    System.out.println("ℹ️ 脚本环境问题，从第 " + (fromStep + 1) + " 步继续执行");
                    PlanExecutor.ExecutionResult continueResult = planExecutor.executeFromStep(episode, fromStep, variables, tools);
                    if (continueResult.success()) {
                        toolCallLogs.addAll(continueResult.executedSteps());
                        if (currentAiTurnId == myTurnId) {
                            toolPlanner.onCacheHitSuccess(userInput, plan);
                            int totalSteps = execResult.executedSteps().size() + continueResult.executedSteps().size();
                            ttsService.speakAsync("已从失败处继续完成（共 " + totalSteps + " 步）");
                        }
                        return;
                    }
                    // 分段继续也失败，fall through to replan
                }
            }
            toolPlanner.onCacheHitFailure(userInput, plan, analysis.lesson(), analysis.isPlanIssue());
            handleCacheFailure(chatClient, tools, effectiveInput, userInput, plan,
                    toolCallLogs, myTurnId, ttsService, execResult.errorMessage());
        }
    }

    /** AI 带参考计划执行 */
    private void handleAiExecutionWithPlan(ChatClient chatClient, ToolCallback[] tools,
                                            String augmentedInput, String userInput,
                                            ToolPlanner.PlanResult plan, String effectiveInput,
                                            List<ToolCallLog> toolCallLogs,
                                            long myTurnId, TtsService ttsService) {
        try {
            String response = executeWithTools(chatClient, augmentedInput, tools, plan, toolCallLogs);
            if (currentAiTurnId == myTurnId) {
                var verify = reflectService.verifyExecution(userInput, toolCallLogs, response);
                if (!verify.success()) {
                    System.out.println("🔍 校验未通过: " + verify.reason());
                    if (!verify.salvageableChains().isEmpty()) {
                        toolPlanner.saveSalvageableChains(userInput, toolCallLogs,
                                verify.salvageableChains(), plan.episode().id());
                    }
                    throw new RuntimeException("校验未通过: " + verify.reason());
                }
                System.out.println("🤖 " + response);
                toolPlanner.onCacheHitSuccess(userInput, plan);
                ttsService.speakAsync(response);
            }
        } catch (Exception e) {
            if (currentAiTurnId == myTurnId) {
                ReflectService.FailureAnalysis analysis = reflectService.reflectFailure(userInput, toolCallLogs, e.getMessage());
                System.out.println("🔍 归因: " + (analysis.isPlanIssue() ? "计划问题" : "环境问题") +
                        (analysis.lesson() != null ? "（" + analysis.lesson() + "）" : ""));
                toolPlanner.onCacheHitFailure(userInput, plan, analysis.lesson(), analysis.isPlanIssue());
                failureExperienceHandler.handle(userInput, analysis.lesson(), analysis.isPlanIssue(), false, plan.selectedToolNames());

                if (!analysis.isPlanIssue()) {
                    System.out.println("ℹ️ 环境问题导致失败，分段继续执行");
                    String continuePrompt = buildContinuePrompt(userInput, e.getMessage(), analysis.lesson());
                    toolCallLogs.clear();
                    ToolPlanner.PlanResult fallbackPlan = toolPlanner.plan(userInput, tools);
                    String resp = executeWithTools(chatClient, continuePrompt, tools, fallbackPlan, toolCallLogs);
                    if (currentAiTurnId == myTurnId) {
                        var verify = reflectService.verifyExecution(userInput, toolCallLogs, resp);
                        if (!verify.success()) {
                            System.out.println("⚠️ 分段继续校验未通过: " + verify.reason());
                            if (!verify.salvageableChains().isEmpty()) {
                                toolPlanner.saveSalvageableChains(userInput, toolCallLogs,
                                        verify.salvageableChains(), plan.episode().id());
                            }
                            failureExperienceHandler.handle(userInput, verify.reason(), true,
                                    !verify.salvageableChains().isEmpty(), plan.selectedToolNames());
                        } else {
                            System.out.println("🤖 " + resp);
                            toolPlanner.onCacheHitSuccess(userInput, plan);
                            ttsService.speakAsync(resp);
                        }
                    }
                } else {
                    handleCacheFailure(chatClient, tools, effectiveInput, userInput, plan,
                            toolCallLogs, myTurnId, ttsService, e.getMessage());
                }
            }
        }
    }

    // ========================================================================
    // 新规划处理（首次 + 降级）
    // ========================================================================

    private void handleNewPlan(ChatClient chatClient, ToolCallback[] tools,
                                String effectiveInput, String userInput,
                                ToolPlanner.PlanResult plan, List<ToolCallLog> toolCallLogs,
                                long myTurnId, TtsService ttsService) {
        String draftId = toolPlanner.createDraftEpisode(userInput, plan);
        if (draftId == null) {
            System.out.println("⚠️ DRAFT 创建失败（Qdrant 不可用），本次不保存 Episode");
        }

        try {
            String response = executeWithTools(chatClient, effectiveInput, tools, plan, toolCallLogs);
            if (currentAiTurnId == myTurnId) {
                var verify = reflectService.verifyExecution(userInput, toolCallLogs, response);
                if (!verify.success()) {
                    System.out.println("🔍 校验未通过: " + verify.reason());
                    if (!verify.salvageableChains().isEmpty()) {
                        toolPlanner.saveSalvageableChains(userInput, toolCallLogs,
                                verify.salvageableChains(), draftId);
                    }
                    throw new RuntimeException("校验未通过: " + verify.reason());
                }

                System.out.println("🤖 " + response);
                String successLesson = reflectService.reflectSuccess(userInput, toolCallLogs, response);
                if (successLesson != null) {
                    System.out.println("📝 成功经验: " + successLesson);
                }

                if (draftId != null) {
                    toolPlanner.activateDraftEpisode(draftId, toolCallLogs, response, successLesson);
                    toolPlanner.cacheToMemory(userInput, plan, draftId, toolCallLogs, response, successLesson);
                }
                ttsService.speakAsync(response);
            }
        } catch (Exception e) {
            if (currentAiTurnId == myTurnId) {
                ReflectService.FailureAnalysis analysis = reflectService.reflectFailure(userInput, toolCallLogs, e.getMessage());
                System.out.println("🔍 失败归因: " + (analysis.isPlanIssue() ? "计划问题" : "环境问题") +
                        (analysis.lesson() != null ? "（" + analysis.lesson() + "）" : ""));
                toolPlanner.failDraftEpisode(draftId, toolCallLogs, analysis.lesson(), -1);
                failureExperienceHandler.handle(userInput, analysis.lesson(), analysis.isPlanIssue(), false, plan.selectedToolNames());
                System.out.println("❌ " + e.getMessage());

                if (!analysis.isPlanIssue()) {
                    String continuePrompt = buildContinuePrompt(userInput, e.getMessage(), analysis.lesson());
                    toolCallLogs.clear();
                    String resp = executeWithTools(chatClient, continuePrompt, tools, plan, toolCallLogs);
                    if (currentAiTurnId == myTurnId) {
                        var verify = reflectService.verifyExecution(userInput, toolCallLogs, resp);
                        if (!verify.success()) {
                            System.out.println("⚠️ 分段继续校验未通过: " + verify.reason());
                            if (!verify.salvageableChains().isEmpty()) {
                                toolPlanner.saveSalvageableChains(userInput, toolCallLogs,
                                        verify.salvageableChains(), draftId);
                            }
                            failureExperienceHandler.handle(userInput, verify.reason(), true,
                                    !verify.salvageableChains().isEmpty(), plan.selectedToolNames());
                        } else {
                            System.out.println("🤖 " + resp);
                            toolPlanner.activateDraftEpisode(draftId, toolCallLogs, resp, null);
                            toolPlanner.cacheToMemory(userInput, plan, draftId, toolCallLogs, resp, null);
                            ttsService.speakAsync(resp);
                        }
                    }
                }
            }
        }
    }

    // ========================================================================
    // 缓存失败处理（重新规划）
    // ========================================================================

    private void handleCacheFailure(ChatClient chatClient, ToolCallback[] tools,
                                     String effectiveInput, String userInput,
                                     ToolPlanner.PlanResult oldPlan, List<ToolCallLog> toolCallLogs,
                                     long myTurnId, TtsService ttsService, String errorReason) {
        try {
            ToolPlanner.PlanResult newPlan = toolPlanner.replan(userInput, tools, errorReason);
            newPlan.missingDescriptions().forEach(desc ->
                    System.out.println("⚠️ 缺少工具: " + desc + "（可补写本地 @Tool）"));
            System.out.println("📦 重新选用工具: " + newPlan.selectedToolNames().size() + "/" + tools.length);

            String newDraftId = toolPlanner.createDraftEpisode(userInput, newPlan);
            String response = executeWithTools(chatClient, effectiveInput, tools, newPlan, toolCallLogs);
            if (currentAiTurnId == myTurnId) {
                var verify = reflectService.verifyExecution(userInput, toolCallLogs, response);
                if (!verify.success()) {
                    System.out.println("🔍 重规划校验未通过: " + verify.reason());
                    if (!verify.salvageableChains().isEmpty()) {
                        toolPlanner.saveSalvageableChains(userInput, toolCallLogs,
                                verify.salvageableChains(), newDraftId);
                    }
                    throw new RuntimeException("重规划校验未通过: " + verify.reason());
                }
                System.out.println("🤖 " + response);
                String successLesson = reflectService.reflectSuccess(userInput, toolCallLogs, response);
                toolPlanner.activateDraftEpisode(newDraftId, toolCallLogs, response, successLesson);
                toolPlanner.cacheToMemory(userInput, newPlan, newDraftId, toolCallLogs, response, successLesson);
                ttsService.speakAsync(response);
            }
        } catch (Exception retryEx) {
            if (currentAiTurnId == myTurnId) {
                System.out.println("❌ 重试失败: " + retryEx.getMessage());
                failureExperienceHandler.handle(userInput, retryEx.getMessage(), true, false, oldPlan.selectedToolNames());
                ttsService.speakAsync("抱歉，我没做好这个任务");
            }
        }
    }

    // ========================================================================
    // AI 执行（过滤工具 + 收集日志）
    // ========================================================================

    private String executeWithTools(ChatClient chatClient, String input,
                                    ToolCallback[] allTools, ToolPlanner.PlanResult plan,
                                    List<ToolCallLog> toolCallLogs) {
        var selectedNames = new LinkedHashSet<>(plan.selectedToolNames());
        selectedNames.addAll(props.toolPlanner().alwaysAppendTools());
        ToolCallback[] selectedTools = Arrays.stream(allTools)
                .filter(tc -> selectedNames.contains(tc.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);

        ToolCallback[] loggedTools = Arrays.stream(selectedTools)
                .map(tc -> new LoggingToolCallback(tc, toolCallLogs))
                .toArray(ToolCallback[]::new);

        System.out.println("📦 选用工具: " + selectedTools.length + "/" + allTools.length
                + (plan.fromCache() ? " (缓存命中)" : ""));

        try {
            return chatClient.prompt().user(input).toolCallbacks(loggedTools).call().content();
        } catch (Exception e) {
            if (plan.fromCache()) {
                throw new RuntimeException("🔄 缓存方案执行失败", e);
            }
            throw e;
        }
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
}
