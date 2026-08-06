package com.example.desktopbrain.memory.vector.episode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 反思服务（ExpeL/MUSE 的 Reflect 环节）。
 *
 * <p>用户逻辑中加入的关键缺失项：Plan→Execute→<b>Reflect</b>→Memorize 闭环。
 * 不只是"成功就存/失败就+1"，而是每次执行后让 AI 反思提取经验教训：</p>
 *
 * <ul>
 *   <li><b>成功反思</b>：AI 总结一句关键成功经验（successLesson），
 *       下次类似请求复用计划时作为 hint 提示 AI "上次这样做成功了"</li>
 *   <li><b>失败反思</b>：AI 分析失败原因 + 归因（计划问题 vs 环境问题），
 *       提取一句失败教训（failureLesson），下次命中时作为警示</li>
 * </ul>
 *
 * <p>归因结果直接决定失败处理路径：
 * 计划问题→失败数+1（惩罚计划）；环境问题→分段继续（不惩罚计划）。</p>
 *
 * <p>反思是同步的（~300ms 轻量 AI 调用），因为：
 * 成功反思结果要随 episode 一起存；失败反思结果要决定归因路径。</p>
 */
@Service
public class ReflectService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public ReflectService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem("你是经验反思总结器，用简洁的一句话总结任务执行的经验教训。")
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 失败分析结果。
     *
     * @param lesson     失败教训（30字内，下次作为警示）
     * @param isPlanIssue 是否是计划逻辑问题（true=惩罚计划 / false=环境问题不惩罚）
     */
    public record FailureAnalysis(String lesson, boolean isPlanIssue) {}

    /**
     * 成功反思：AI 总结关键成功经验。
     *
     * @param userInput  用户原话
     * @param toolCalls  执行轨迹
     * @param aiResponse AI 最终回复
     * @return 成功经验（30字内）；AI 调用失败时返回 null
     */
    public String reflectSuccess(String userInput, List<ToolCallLog> toolCalls, String aiResponse) {
        String stepsSummary = formatSteps(toolCalls);
        String prompt = """
                请用一句话总结这次任务的关键成功经验（30字内），用于未来类似任务的复用提示。

                用户请求: %s
                执行步骤:
                %s
                AI回复: %s

                只返回经验总结本身，不要前缀、不要解释、不要引号。
                示例: 先打开微信再搜索联系人，最后输入消息发送
                """.formatted(userInput, stepsSummary, truncate(aiResponse, 200));

        try {
            String result = chatClient.prompt().user(prompt).call().content();
            return truncate(result, 100);
        } catch (Exception e) {
            System.err.println("⚠️ 成功反思失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 失败反思 + 归因：AI 分析失败原因，判断是计划问题还是环境问题。
     *
     * @param userInput    用户原话
     * @param toolCalls    执行轨迹（可能部分执行）
     * @param errorMessage 失败错误信息
     * @return 失败分析（教训 + 是否计划问题）；AI 调用失败时返回默认值（环境问题，不惩罚计划）
     */
    public FailureAnalysis reflectFailure(String userInput, List<ToolCallLog> toolCalls, String errorMessage) {
        String stepsSummary = formatSteps(toolCalls);
        String prompt = """
                分析以下任务失败的原因，判断是计划逻辑问题还是环境问题。

                用户请求: %s
                执行步骤:
                %s
                失败原因: %s

                判断规则:
                - 计划问题: 步骤顺序错误、缺少必要中间步骤、工具选择错误、参数逻辑错误
                - 环境问题: 目标不存在、网络中断、权限不够、应用未启动、元素未加载

                返回严格的 JSON（不要 markdown 标记）:
                {"isPlanIssue": true/false, "lesson": "一句话教训（30字内）"}
                """.formatted(userInput, stepsSummary, truncate(errorMessage, 300));

        try {
            String response = chatClient.prompt().user(prompt).call().content();
            return parseFailureAnalysis(response);
        } catch (Exception e) {
            System.err.println("⚠️ 失败反思失败: " + e.getMessage());
            // 默认当作环境问题（不惩罚计划）
            return new FailureAnalysis(null, false);
        }
    }

    /**
     * 解析失败分析的 JSON。
     */
    @SuppressWarnings("unchecked")
    private FailureAnalysis parseFailureAnalysis(String response) {
        if (response == null || response.isBlank()) {
            return new FailureAnalysis(null, false);
        }
        String json = response.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
        }
        try {
            var parsed = objectMapper.readValue(json, java.util.Map.class);
            boolean isPlanIssue = Boolean.TRUE.equals(parsed.get("isPlanIssue"));
            String lesson = (String) parsed.getOrDefault("lesson", null);
            return new FailureAnalysis(truncate(lesson, 100), isPlanIssue);
        } catch (Exception e) {
            System.err.println("⚠️ 失败分析 JSON 解析失败: " + e.getMessage());
            return new FailureAnalysis(null, false);
        }
    }

    /** 格式化工具调用轨迹为简洁文本 */
    private static String formatSteps(List<ToolCallLog> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return "（无步骤记录）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCallLog step = toolCalls.get(i);
            sb.append(i + 1).append(". ")
              .append(step.toolName()).append("(").append(truncate(step.args(), 100)).append(")")
              .append(step.success() ? " ✅" : " ❌").append("\n");
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        String oneLine = s.replace("\n", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "..." : oneLine;
    }
}
