package com.example.desktopbrain.exploration;

import com.example.desktopbrain.common.PromptLoader;
import com.example.desktopbrain.config.DesktopBrainProperties;
import com.example.desktopbrain.memory.vector.episode.EpisodeCacheService;
import com.example.desktopbrain.memory.vector.episode.FailureExperienceHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.desktopbrain.config.ModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(AutonomousExplorationService.class);

    private final IdleDetectionService idleDetection;
    private final EpisodeCacheService episodeCache;
    private final FailureExperienceHandler failureHandler;
    private final ExplorationExecutor executor;
    private final PromptLoader promptLoader;
    private final DesktopBrainProperties props;
    private final ObjectMapper objectMapper;

    private final ModelRouter modelRouter;

    public AutonomousExplorationService(IdleDetectionService idleDetection,
                                         EpisodeCacheService episodeCache,
                                         FailureExperienceHandler failureHandler,
                                         ExplorationExecutor executor,
                                         PromptLoader promptLoader,
                                         DesktopBrainProperties props,
                                         ModelRouter modelRouter) {
        this.idleDetection = idleDetection;
        this.episodeCache = episodeCache;
        this.failureHandler = failureHandler;
        this.executor = executor;
        this.promptLoader = promptLoader;
        this.props = props;
        this.objectMapper = new ObjectMapper();
        this.modelRouter = modelRouter;
    }

    /**
     * 定时巡检：每 5 分钟检查一次空闲条件，自动触发探索。
     * 与手动触发（ExplorationTool）共享同一入口。
     */
    @Scheduled(fixedDelayString = "${desktopbrain.exploration.check-interval-ms:300000}",
               initialDelayString = "${desktopbrain.exploration.initial-delay-ms:60000}")
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
                log.info("🔍 开始自主探索会话...");

                // 1. 收集上下文
                String context = buildContext();

                // 2. LLM 决策
                ExplorationDecision decision = decide(context);
                if (decision == null || "SKIP".equalsIgnoreCase(decision.decision())) {
                    log.info("⏭️ 探索跳过: {}", decision != null ? decision.reason() : "决策失败");
                    return;
                }

                log.info("📚 探索目标: {} (方法: {}, 优先级: {})", decision.learningGoal(), decision.method(), decision.priority());

                // 3. 异步执行探索（不阻塞）
                executor.execute(decision);

            } catch (Exception e) {
                log.error("❌ 自主探索决策失败", e);
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

    /** 从 AI 返回的文本中提取 JSON 对象 */
    private static String extractJson(String text) {
        if (text == null || text.isBlank()) return null;
        String s = text.trim();
        // 0. 去掉可能的思考标签（如 deepseek-r1 的 <｜end▁of▁thinking｜>...）
        s = s.replaceAll("(?s)___[^_]*___", "");
        s = s.replaceAll("(?s)^\\s*", "");
        s = s.trim();
        // 1. 去掉 markdown 代码块
        s = s.replaceAll("(?s)```[a-zA-Z]*\\s*", "```"); // normalize ```json → ```
        if (s.startsWith("```")) {
            s = s.substring(3);
            int end = s.lastIndexOf("```");
            if (end > 0) s = s.substring(0, end);
        }
        s = s.trim();
        // 2. 找第一个 { 或 [ 到对应的结尾
        int start = s.indexOf('{');
        if (start < 0) start = s.indexOf('[');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        char openChar = s.charAt(start);
        char closeChar = openChar == '{' ? '}' : ']';
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) { escaped = false; continue; }
                if (c == '\\') { escaped = true; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == openChar) depth++;
            else if (c == closeChar) { depth--; if (depth == 0) return s.substring(start, i + 1); }
        }
        return null;
    }

    /** 调用小模型做探索决策 */
    private ExplorationDecision decide(String context) {
        try {
            String response = modelRouter.exploration().prompt()
                    .user(context)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                log.warn("⏭️ 探索决策: AI 返回空响应");
                return null;
            }

            String json = extractJson(response);
            if (json == null) {
                log.warn("⏭️ 探索决策: 无法提取 JSON（前 200 字符）: {}", response.substring(0, Math.min(200, response.length())));
                return null;
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
            log.error("❌ 探索决策失败: {}", e.getMessage());
            return null;
        }
    }
}
