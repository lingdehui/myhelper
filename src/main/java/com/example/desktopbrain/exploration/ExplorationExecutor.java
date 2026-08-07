package com.example.desktopbrain.exploration;

import com.example.desktopbrain.common.AiResponseUtils;
import com.example.desktopbrain.memory.vector.episode.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 探索执行器：按 ExplorationDecision 逐步执行学习任务。
 *
 * <p>执行流程：
 * 1. 创建 ExplorationEpisode (IN_PROGRESS)
 * 2. 根据 method 选择路径执行，每步记录 ToolCallLog
 * 3. 成功步 → 记录 SubEpisode (EXPLORATION_ATOMIC)
 * 4. 失败步 → FailureExperienceHandler.handle()
 * 5. 全部完成 → selfAssess → 决定是否生成 KnowledgeSnippet
 * </p>
 */
@Service
public class ExplorationExecutor {

    private final EpisodeCacheService episodeCache;
    private final FailureExperienceHandler failureHandler;
    private final ChatClient explorationChatClient;
    private final ToolCallback[] tools;
    private final ObjectMapper objectMapper;

    /** 探索最大时长（分钟） */
    private static final int MAX_DURATION_MINUTES = 15;

    public ExplorationExecutor(EpisodeCacheService episodeCache,
                                FailureExperienceHandler failureHandler,
                                @Qualifier("ollama") ChatClient explorationChatClient,
                                ToolCallbackProvider mcpTools) {
        this.episodeCache = episodeCache;
        this.failureHandler = failureHandler;
        this.objectMapper = new ObjectMapper();
        this.tools = mcpTools.getToolCallbacks();
        this.explorationChatClient = explorationChatClient;
    }

    /**
     * 异步执行探索任务。
     */
    public void execute(ExplorationDecision decision) {
        CompletableFuture.runAsync(() -> {
            String episodeId = null;
            List<ToolCallLog> toolCallLogs = Collections.synchronizedList(new ArrayList<>());

            try {
                // 1. 创建探索 Episode
                episodeId = createExplorationDraft(decision);

                // 2. 执行步骤
                long startedAt = System.currentTimeMillis();
                boolean allSuccess = executeSteps(decision, toolCallLogs, episodeId, startedAt);

                // 3. 自我评估
                ExplorationSelfAssessment assessment = selfAssess(decision, toolCallLogs, allSuccess);

                // 4. 更新 Episode
                if (assessment.goalAchieved()) {
                    episodeCache.activateDraft(episodeId, toolCallLogs,
                            assessment.summary(), assessment.knowledgeSnippet());
                } else {
                    episodeCache.failDraft(episodeId, toolCallLogs, assessment.summary(), -1);
                }

                // 5. 生成 KnowledgeSnippet
                if (assessment.worthStoring() && assessment.knowledgeSnippet() != null) {
                    saveKnowledgeSnippet(assessment, episodeId);
                }

            } catch (Exception e) {
                System.err.println("❌ 探索执行失败: " + e.getMessage());
                if (episodeId != null) {
                    episodeCache.failDraft(episodeId, toolCallLogs,
                            "探索异常: " + e.getMessage(), -1);
                    failureHandler.handle(decision.learningGoal(),
                            "探索异常: " + e.getMessage(), true, false, List.of());
                }
            }
        });
    }

    /** 创建探索 DRAFT Episode */
    private String createExplorationDraft(ExplorationDecision decision) {
        return episodeCache.createDraft(decision.learningGoal(),
                List.of("exploration-" + decision.method()),
                List.of());
    }

    /** 逐步执行 */
    private boolean executeSteps(ExplorationDecision decision,
                                  List<ToolCallLog> toolCallLogs,
                                  String episodeId,
                                  long startedAt) {
        boolean allSuccess = true;

        for (int i = 0; i < decision.steps().size(); i++) {
            // 超时检查
            if (System.currentTimeMillis() - startedAt > TimeUnit.MINUTES.toMillis(MAX_DURATION_MINUTES)) {
                System.out.println("⏰ 探索超时，停止执行（已执行 " + i + "/" + decision.steps().size() + " 步）");
                break;
            }

            String step = decision.steps().get(i);
            System.out.println("  🔧 [" + (i + 1) + "/" + decision.steps().size() + "] " + step);

            try {
                long stepStart = System.currentTimeMillis();

                // 实际执行步骤（通过 AI + 工具调用）
                String stepResult = executeStep(step, decision.method());

                long elapsed = System.currentTimeMillis() - stepStart;
                ToolCallLog log = new ToolCallLog(
                        "exploration_step", step, AiResponseUtils.truncate(stepResult, 500),
                        true, elapsed);
                toolCallLogs.add(log);
                System.out.println("    ✅ 完成 (" + elapsed + "ms)");

                // 记录子步骤（可作为 ATOMIC 被后续复用）
                recordSubEpisode(step, stepResult, episodeId);

            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - startedAt;
                ToolCallLog log = new ToolCallLog(
                        "exploration_step", step, AiResponseUtils.truncate(e.getMessage(), 500),
                        false, elapsed);
                toolCallLogs.add(log);
                System.out.println("    ❌ 失败: " + e.getMessage());

                // 走失败经验处理
                failureHandler.handle(decision.learningGoal(),
                        "步骤失败: " + e.getMessage(), true, false,
                        List.of("exploration-" + decision.method()));

                allSuccess = false;
            }
        }

        return allSuccess;
    }

    /** 执行单个步骤（通过 AI 调用工具） */
    private String executeStep(String step, String method) {
        String prompt = "请执行以下探索步骤：" + step + "\n方法：" + method
                + "\n只使用工具执行，不要问问题。";
        try {
            return explorationChatClient.prompt()
                    .user(prompt)
                    .tools(tools)
                    .call()
                    .content();
        } catch (Exception e) {
            throw new RuntimeException("步骤执行异常: " + e.getMessage(), e);
        }
    }

    /** 记录子步骤为 ATOMIC Episode */
    private void recordSubEpisode(String step, String result, String parentEpisodeId) {
        // 子步骤作为 ATOMIC 存入 episodes collection，可被后续任务检索复用
        episodeCache.saveSalvageableAtomicChains(
                step,
                List.of(new ToolCallLog("exploration_step", step,
                        AiResponseUtils.truncate(result, 500), true, 0)),
                List.of(List.of(0)),
                parentEpisodeId);
    }

    /** AI 自我评估 */
    private ExplorationSelfAssessment selfAssess(ExplorationDecision decision,
                                                  List<ToolCallLog> toolCallLogs,
                                                  boolean allSuccess) {
        try {
            StringBuilder stepsLog = new StringBuilder();
            for (ToolCallLog log : toolCallLogs) {
                stepsLog.append("- ").append(log.toolName()).append(": ")
                        .append(log.success() ? "成功" : "失败").append("\n");
            }

            String prompt = String.format("""
                    评估以下探索任务是否达成目标：
                    
                    学习目标: %s
                    期望成果: %s
                    成功标准: %s
                    执行结果（全部成功: %s）:
                    %s
                    
                    请用 JSON 回答（无其他文字）：
                    {"goalAchieved": true/false, "summary": "总结", "worthStoring": true/false, "knowledgeSnippet": "知识片段", "newCapabilities": ["能力1"], "followUpSuggestion": "后续建议"}
                    """,
                    decision.learningGoal(), decision.expectedOutcome(),
                    decision.successCriteria(), allSuccess, stepsLog);

            String response = explorationChatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                return new ExplorationSelfAssessment(false, "评估失败", false, null, List.of(), null);
            }

            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```[a-z]*", "").replace("```", "").trim();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);

            @SuppressWarnings("unchecked")
            List<String> caps = (List<String>) map.getOrDefault("newCapabilities", List.of());

            return new ExplorationSelfAssessment(
                    Boolean.TRUE.equals(map.get("goalAchieved")),
                    String.valueOf(map.getOrDefault("summary", "")),
                    Boolean.TRUE.equals(map.get("worthStoring")),
                    String.valueOf(map.getOrDefault("knowledgeSnippet", null)),
                    caps,
                    String.valueOf(map.getOrDefault("followUpSuggestion", ""))
            );

        } catch (Exception e) {
            System.err.println("❌ 自我评估失败: " + e.getMessage());
            return new ExplorationSelfAssessment(false, "评估异常", false, null, List.of(), null);
        }
    }

    /** 保存知识片段（暂存到 Episode payload，后续版本可抽到 knowledge-snippets collection） */
    private void saveKnowledgeSnippet(ExplorationSelfAssessment assessment, String episodeId) {
        KnowledgeSnippet snippet = KnowledgeSnippet.create(
                UUID.randomUUID().toString(),
                assessment.summary(),
                assessment.knowledgeSnippet(),
                "autonomous_exploration",
                episodeId);
        System.out.println("📝 已生成知识片段: " + snippet.title());
        // TODO: 后续实现 KnowledgeSnippetService 持久化到 Qdrant + Neo4j
    }
}
