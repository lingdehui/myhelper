package com.example.desktopbrain.exploration;

import com.example.desktopbrain.common.PromptLoader;
import com.example.desktopbrain.config.DesktopBrainProperties;
import com.example.desktopbrain.memory.vector.episode.EpisodeCacheService;
import com.example.desktopbrain.memory.vector.episode.FailureExperienceHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 自主探索服务：空闲时收集上下文 → LLM 决策 → 提交后台执行。
 */
@Service
public class AutonomousExplorationService {

    private final IdleDetectionService idleDetection;
    private final EpisodeCacheService episodeCache;
    private final FailureExperienceHandler failureHandler;
    private final ExplorationExecutor executor;
    private final PromptLoader promptLoader;
    private final DesktopBrainProperties props;
    private final ObjectMapper objectMapper;

    /** 探索专用 ChatClient（用小模型） */
    private final ChatClient explorationChatClient;

    public AutonomousExplorationService(IdleDetectionService idleDetection,
                                         EpisodeCacheService episodeCache,
                                         FailureExperienceHandler failureHandler,
                                         ExplorationExecutor executor,
                                         PromptLoader promptLoader,
                                         DesktopBrainProperties props,
                                         @Qualifier("ollama") ChatClient explorationChatClient) {
        this.idleDetection = idleDetection;
        this.episodeCache = episodeCache;
        this.failureHandler = failureHandler;
        this.executor = executor;
        this.promptLoader = promptLoader;
        this.props = props;
        this.objectMapper = new ObjectMapper();
        this.explorationChatClient = explorationChatClient;
    }

    /**
     * 定时巡检：每 5 分钟检查一次空闲条件，自动触发探索。
     * 与手动触发（ExplorationTool）共享同一入口。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void scheduledExplore() {
        tryExplore();
    }

    /**
     * 检查并触发探索（由定时任务或外部调用）。
     * 异步执行，不阻塞调用方。
     */
    public void tryExplore() {
        if (!idleDetection.shouldExplore()) return;
        doExplore();
    }

    /** 手动/强制触发探索，跳过空闲和免打扰检查。 */
    public void forceExplore() {
        doExplore();
    }

    private void doExplore() {
        CompletableFuture.runAsync(() -> {
            try {
                System.out.println("🔍 开始自主探索会话...");

                // 1. 收集上下文
                String context = buildContext();

                // 2. LLM 决策
                ExplorationDecision decision = decide(context);
                if (decision == null || "SKIP".equalsIgnoreCase(decision.decision())) {
                    System.out.println("⏭️ 探索跳过: " + (decision != null ? decision.reason() : "决策失败"));
                    return;
                }

                System.out.println("📚 探索目标: " + decision.learningGoal()
                        + " (方法: " + decision.method() + ", 优先级: " + decision.priority() + ")");

                // 3. 异步执行探索（不阻塞）
                executor.execute(decision);

            } catch (Exception e) {
                System.err.println("❌ 自主探索决策失败: " + e.getMessage());
            }
        });
    }

    /** 构建上下文 prompt */
    private String buildContext() {
        // 收集失败模式
        List<EpisodeCacheService.FailureSearchResult> failures =
                episodeCache.searchFailurePatterns("归纳规则", 5);
        StringBuilder failureText = new StringBuilder("无");
        if (failures != null && !failures.isEmpty()) {
            failureText.setLength(0);
            failures.forEach(f -> failureText.append("- ").append(f.description()).append("\n"));
        }

        // 知识库数量
        int knowledgeCount = 0; // TODO: 后续实现 KnowledgeSnippetService

        String template = promptLoader.getAutonomousExploration();
        return template
                .replace("{unresolved_failures}", failureText.toString())
                .replace("{unsolved_questions}", "暂无记录")
                .replace("{tool_list_with_usage}", "工具列表由系统提供")
                .replace("{knowledge_count}", String.valueOf(knowledgeCount));
    }

    /** 调用小模型做探索决策 */
    private ExplorationDecision decide(String context) {
        try {
            String response = explorationChatClient.prompt()
                    .user(context)
                    .call()
                    .content();

            if (response == null || response.isBlank()) return null;

            // 清理 markdown 代码块
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```[a-z]*", "").replace("```", "").trim();
            }

            Map<String, Object> map = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});

            String decision = String.valueOf(map.getOrDefault("decision", "SKIP"));
            if ("SKIP".equalsIgnoreCase(decision)) {
                String reason = String.valueOf(map.getOrDefault("reason", ""));
                return new ExplorationDecision("SKIP", reason, null, null, null, null, null, "LOW");
            }

            @SuppressWarnings("unchecked")
            List<String> steps = (List<String>) map.get("steps");

            return new ExplorationDecision(
                    "LEARN",
                    String.valueOf(map.getOrDefault("reason", "")),
                    String.valueOf(map.getOrDefault("learningGoal", "")),
                    String.valueOf(map.getOrDefault("method", "web_research")),
                    steps != null ? steps : List.of(),
                    String.valueOf(map.getOrDefault("expectedOutcome", "")),
                    String.valueOf(map.getOrDefault("successCriteria", "")),
                    String.valueOf(map.getOrDefault("priority", "MEDIUM"))
            );

        } catch (Exception e) {
            System.err.println("❌ 探索决策 JSON 解析失败: " + e.getMessage());
            return null;
        }
    }
}
