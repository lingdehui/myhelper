package com.example.desktopbrain.memory.vector.episode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 计划匹配器：AI 判断 episode 是否适用于当前输入 + 提取变量值。
 *
 * <p>用户核心逻辑："下次再匹配类似的→直接让AI判断这个成功计划是否可用→
 * 可用就按计划走→不可用新拟计划"。</p>
 *
 * <p>命中 Episode 后，不直接复用，而是先调 AI 判断：
 * <ol>
 *   <li>这个计划（步骤+变量签名）是否适用于当前用户输入？</li>
 *   <li>如果适用，从用户输入中提取变量值（如 contact=张三, message=明天开会）</li>
 * </ol>
 * 适用 → PlanExecutor 按计划执行（变量替换）；
 * 不适用 → 走 AI 新规划路径。</p>
 */
@Service
public class PlanMatcher {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public PlanMatcher(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem("你是计划匹配判断器，判断已有计划是否适用于当前用户请求，并提取变量。")
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 匹配结果。
     *
     * @param applicable 计划是否适用于当前输入
     * @param variables  提取的变量值（applicable=true 时有效，key=变量名, value=变量值）
     * @param reason     判断理由（用于日志和调试）
     */
    public record MatchResult(boolean applicable, Map<String, String> variables, String reason) {
        /** 不适用的快捷构造 */
        public static MatchResult notApplicable(String reason) {
            return new MatchResult(false, Collections.emptyMap(), reason);
        }
        /** 适用的快捷构造 */
        public static MatchResult applicable(Map<String, String> variables, String reason) {
            return new MatchResult(true, variables, reason);
        }
    }

    /**
     * AI 判断 episode 是否适用于当前输入，提取变量值。
     *
     * <p>ATOMIC 通用步骤：直接适用（无需变量匹配，步骤即开即用）。</p>
     *
     * @param userInput 用户原话
     * @param episode   命中的候选 episode
     * @return 匹配结果；AI 调用失败时返回 notApplicable（降级到新规划）
     */
    public MatchResult match(String userInput, Episode episode) {
        if (episode.toolCalls() == null || episode.toolCalls().isEmpty()) {
            return MatchResult.notApplicable("计划没有可执行步骤");
        }

        // ATOMIC 通用步骤：直接适用，不需要 AI 判断变量
        if (episode.unitType() == Episode.UnitType.ATOMIC && episode.isGeneric()) {
            String reason = "ATOMIC 通用步骤: " + episode.userInput();
            System.out.println("🧩 ATOMIC 通用步骤直接适用: " + reason);
            return MatchResult.applicable(Map.of(), reason);
        }

        // 构造步骤描述
        StringBuilder stepsDesc = new StringBuilder();
        for (int i = 0; i < episode.toolCalls().size(); i++) {
            ToolCallLog step = episode.toolCalls().get(i);
            stepsDesc.append(i + 1).append(". ").append(step.toolName())
                    .append("(").append(step.args()).append(")\n");
        }

        // 构造变量列表
        String varList = episode.signature() == null || episode.signature().isEmpty()
                ? "（无变量，直接复用即可）"
                : String.join(", ", episode.signature().keySet());

        String prompt = """
                判断以下已有计划是否适用于当前用户请求，并提取变量值。

                用户请求: %s
                计划用途: %s
                计划步骤:
                %s
                需要的变量: %s

                规则:
                - 如果计划适用，从用户请求中提取变量值
                - 如果计划完全不相关或无法适配，返回 applicable=false
                - 变量值应该是用户请求中明确提到的内容
                - 返回严格的 JSON 格式，不要加 markdown 代码块标记

                返回格式:
                {"applicable": true, "variables": {"变量名": "变量值"}, "reason": "简要理由"}
                或
                {"applicable": false, "variables": {}, "reason": "为什么不适用"}
                """.formatted(userInput, episode.userInput(), stepsDesc, varList);

        try {
            String response = chatClient.prompt().user(prompt).call().content();
            return parseMatchResult(response);
        } catch (Exception e) {
            System.err.println("⚠️ PlanMatcher AI 判断失败: " + e.getMessage());
            return MatchResult.notApplicable("AI 判断异常: " + e.getMessage());
        }
    }

    /**
     * 解析 AI 返回的 JSON 为 MatchResult。
     * 容错处理：去掉可能的 markdown 代码块标记，解析失败返回 notApplicable。
     */
    @SuppressWarnings("unchecked")
    private MatchResult parseMatchResult(String response) {
        if (response == null || response.isBlank()) {
            return MatchResult.notApplicable("AI 返回空");
        }

        // 去掉可能的 ```json ... ``` 标记
        String json = response.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            boolean applicable = Boolean.TRUE.equals(parsed.get("applicable"));
            String reason = (String) parsed.getOrDefault("reason", "");
            Map<String, String> variables = Collections.emptyMap();
            if (applicable && parsed.containsKey("variables")) {
                Map<String, Object> rawVars = (Map<String, Object>) parsed.get("variables");
                variables = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, Object> e : rawVars.entrySet()) {
                    variables.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            if (applicable) {
                System.out.println("✅ 计划可用（变量: " + variables + "）" + reason);
                return MatchResult.applicable(variables, reason);
            } else {
                System.out.println("❌ 计划不适用: " + reason);
                return MatchResult.notApplicable(reason);
            }
        } catch (Exception e) {
            System.err.println("⚠️ PlanMatcher JSON 解析失败: " + e.getMessage() + " | 原始: " + json);
            return MatchResult.notApplicable("JSON 解析失败");
        }
    }
}
