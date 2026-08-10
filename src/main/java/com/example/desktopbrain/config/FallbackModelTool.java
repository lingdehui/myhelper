package com.example.desktopbrain.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 备用模型工具 — 供模型1（本地Ollama）在遇到复杂/不确定问题时主动调用。
 *
 * <p>模型1 在推理过程中可以自行判断是否需要调用此工具获取模型2（DeepSeek API）的参考意见，
 * 然后综合两方观点给出最终回复。</p>
 *
 * <p>与 {@link ModelRouter} 的被动降级不同，此工具是 AI 主动触发：</p>
 * <ul>
 *   <li>被动降级：模型1 回复后检测到"我无法/不知道/太复杂" → 自动咨询模型2</li>
 *   <li>主动调用：模型1 推理过程中自觉问题复杂 → 主动调用此工具 → 参考后给出答案</li>
 * </ul>
 */
@Component
public class FallbackModelTool {

    private static final Logger log = LoggerFactory.getLogger(FallbackModelTool.class);

    private final ChatClient fallbackClient;
    private final String model2Name;

    public FallbackModelTool(@Qualifier("model2") OpenAiChatModel model2,
                             DesktopBrainProperties props) {
        this.fallbackClient = ChatClient.builder(model2).build();
        this.model2Name = props.deepseek() != null ? props.deepseek().model() : "未知";
    }

    @Tool(description = """
            当你遇到以下情况时调用此工具：
            - 问题太复杂，你无法确定答案
            - 需要多步推理但你难以独立完成
            - 任务计划太大需要协助
            - 你对某个领域不熟悉，需要参考意见

            调用后会获得备用模型的参考回复，你可以综合参考回复和你的判断来给出最终答案。

            注意：只有在确实无法独立解决时才调用，不要滥用。
            """)
    public String consultFallbackModel(String question) {
        log.info("🔧 [FallbackModelTool] 模型1 主动咨询备用模型 ({}): {}...",
                model2Name, question.substring(0, Math.min(80, question.length())));
        try {
            long start = System.currentTimeMillis();
            String answer = fallbackClient.prompt()
                    .user(question)
                    .call()
                    .content();
            long elapsed = System.currentTimeMillis() - start;
            log.info("✅ [FallbackModelTool] 备用模型回复 ({}ms): {}...",
                    elapsed, answer != null ? answer.substring(0, Math.min(100, answer.length())) : "(空)");
            return answer != null ? answer : "(备用模型无有效回复)";
        } catch (Exception e) {
            log.error("❌ [FallbackModelTool] 备用模型调用失败: {}", e.getMessage());
            return "备用模型暂时不可用: " + e.getMessage();
        }
    }
}
