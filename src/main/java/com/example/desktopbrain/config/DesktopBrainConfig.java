package com.example.desktopbrain.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 双模型配置：模型1 云端 DeepSeek（优先），模型2 本地 Ollama（降级 / 探索专用）。
 *
 * <pre>
 *   {@code @Primary ChatClient} :
 *       自动故障转移：请求 → 模型1 云端 → 失败 → 模型2 本地
 *       所有对话、规划、反思等默认走此通道
 *
 *   {@code @Qualifier("ollama") ChatClient} :
 *       绕过故障转移，直达本地模型
 *       探索决策 + 步骤执行专用
 * </pre>
 */
@Configuration
@EnableConfigurationProperties(DesktopBrainProperties.class)
@EnableScheduling
public class DesktopBrainConfig {

    // ============================================================
    // 模型1: DeepSeek 云端（远程）
    // ============================================================

    private OpenAiChatModel buildDeepSeekModel(DesktopBrainProperties props) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(props.deepseek().baseUrl())
                .apiKey(props.deepseek().apiKey())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(props.deepseek().model())
                        .build())
                .build();
    }

    // ============================================================
    // 模型2: Ollama 本地
    // ============================================================

    private OpenAiChatModel buildOllamaModel(DesktopBrainProperties props) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl("http://localhost:11434/v1")
                .apiKey("ollama")
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(props.exploration().model())
                        .build())
                .build();
    }

    // ============================================================
    // @Primary: 自动故障转移 ChatClient
    // 所有对话 / 规划 / 反思走这里 → 模型1 失败自动降级模型2
    // ============================================================

    @Bean
    @Primary
    public ChatClient chatClient(DesktopBrainProperties props) {
        OpenAiChatModel model1 = buildDeepSeekModel(props);
        OpenAiChatModel model2 = buildOllamaModel(props);

        ChatModel failover = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                try {
                    return model1.call(prompt);
                } catch (Exception e) {
                    System.err.println("⚠️ 模型1 不可用，切换本地模型: " + e.getMessage());
                    return model2.call(prompt);
                }
            }
        };

        return ChatClient.builder(failover).build();
    }

    // ============================================================
    // @Qualifier("ollama"): 直达本地模型（探索专用）
    // 不经过故障转移，始终本地执行
    // ============================================================

    @Bean
    @Qualifier("ollama")
    public ChatClient ollamaChatClient(DesktopBrainProperties props) {
        return ChatClient.builder(buildOllamaModel(props)).build();
    }
}
