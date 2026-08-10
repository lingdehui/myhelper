package com.example.desktopbrain.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.stream.Collectors;

/**
 * 模型路由器 — 所有 AI 调用的唯一入口。
 *
 * <pre>
 *   - 模型1（主）：本地 Ollama（AutoDL RTX3090，通过SSH隧道），处理所有对话和探索
 *   - 模型2（备）：DeepSeek API，以下场景自动介入：
 *       ① 模型1 网络故障 → 模型2 直接接管
 *       ② 模型1 回复不充分（无法解决/太复杂）→ 咨询模型2 → 模型1 综合后回复
 *   - 探索与普通对话共用同一路由，不再区分模型
 * </pre>
 */
@Component
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final ChatClient model1;      // 本地 Ollama qwen3:30b（主）
    private final ChatClient failover;    // 模型1 → 网络故障/能力不足 → 模型2 DeepSeek
    private final boolean sameModel;      // 模型1和模型2是同一个对象（模型2未配置时）

    private static final String C1 = "\u001b[32m"; // 绿色=模型1 本地Ollama
    private static final String C2 = "\u001b[34m"; // 蓝色=模型2 DeepSeek API（备）
    private static final String R  = "\u001b[0m";  // 重置

    public ModelRouter(DesktopBrainProperties props,
                       @Qualifier("model1") OpenAiChatModel model1Raw,
                       @Qualifier("model2") OpenAiChatModel model2Raw) {
        String model1Name = props.exploration().model();
        String model2Name = props.deepseek() != null ? props.deepseek().model() : "(未配置)";
        String model1Url = props.exploration().baseUrl();
        String model2Url = props.deepseek() != null ? props.deepseek().baseUrl() : "(未配置)";

        log.info("{}🔧 [ModelRouter] 模型1 本地Ollama: {} | model={}{}", C1, model1Url, model1Name, R);
        log.info("{}🔧 [ModelRouter] 模型2 DeepSeek: {} | model={}{}", C2, model2Url, model2Name, R);

        this.model1 = ChatClient.builder(model1Raw).build();
        this.sameModel = (model1Raw == model2Raw);

        if (sameModel) {
            log.info("{}⚠️ [ModelRouter] 模型1与模型2为同一实例，跳过故障转移/咨询逻辑{}", C1, R);
            this.failover = ChatClient.builder(model1Raw).build();
        } else {
            // 故障转移 + 智能咨询：模型1 不充分时咨询模型2
            this.failover = ChatClient.builder(new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                // ======== Phase 1: 模型1 ========
                try {
                    log.info("{}🔄 [ModelRouter] 调用模型1 ({})...{}", C1, model1Name, R);
                    long start = System.currentTimeMillis();
                    ChatResponse resp1 = model1Raw.call(prompt);
                    long elapsed1 = System.currentTimeMillis() - start;

                    // ======== Phase 2: 检查回复质量 ========
                    if (isInadequate(resp1)) {
                        log.warn("{}🤔 [ModelRouter] 模型1 回复不充分（{}ms），咨询模型2 ({})...{}",
                                C1, elapsed1, model2Name, R);

                        long start2 = System.currentTimeMillis();
                        ChatResponse resp2 = model2Raw.call(prompt);
                        long elapsed2 = System.currentTimeMillis() - start2;
                        log.info("{}📋 [ModelRouter] 模型2 参考回复 ({}ms){}", C2, elapsed2, R);

                        // Phase 3: 模型2的回复交给模型1综合
                        String refText = extractText(resp2);
                        String originalText = prompt.getInstructions().stream()
                                .map(Message::getText)
                                .collect(Collectors.joining("\n"));
                        String synthesis = String.format(
                                "以下是备用模型（%s）对同一问题的参考意见，请综合两方观点给出最终回复。\n\n"
                                + "══════ 备用模型参考 ══════\n%s\n\n"
                                + "══════ 原始请求 ══════\n%s",
                                model2Name,
                                refText != null ? refText : "(备用模型无有效回复)",
                                originalText != null ? originalText : ""
                        );

                        log.info("{}🔄 [ModelRouter] 模型1 综合模型2参考中...{}", C1, R);
                        start = System.currentTimeMillis();
                        ChatResponse finalResp = model1Raw.call(new Prompt(new UserMessage(synthesis)));
                        log.info("{}✅ [ModelRouter] 模型1 综合回复成功 ({}ms，含模型2参考，共{}ms){}",
                                C1, System.currentTimeMillis() - start,
                                elapsed1 + elapsed2 + (System.currentTimeMillis() - start), R);
                        return finalResp;
                    }

                    log.info("{}✅ [ModelRouter] 模型1 响应成功 ({}ms){}", C1, elapsed1, R);
                    return resp1;

                // ======== 网络故障：模型2 直接接管 ========
                } catch (Exception e) {
                    if (isNetworkError(e)) {
                        log.warn("{}⚠️ [ModelRouter] 模型1 网络异常，切换模型2 ({}){}", C2, model2Name, R);
                        log.warn("   异常: {} | 原因: {}", e.getClass().getSimpleName(),
                                e.getMessage() != null ? e.getMessage().substring(0, Math.min(120, e.getMessage().length())) : "unknown");
                        log.info("{}🔄 [ModelRouter] 调用模型2 ({})...{}", C2, model2Name, R);
                        long start = System.currentTimeMillis();
                        ChatResponse resp = model2Raw.call(prompt);
                        log.info("{}✅ [ModelRouter] 模型2 响应成功 ({}ms){}", C2, System.currentTimeMillis() - start, R);
                        return resp;
                    }
                    throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
                }
            }

            // ---- 判断模型1回复是否不充分 ----
            private boolean isInadequate(ChatResponse resp) {
                String text = extractText(resp);
                if (text == null || text.isBlank()) return true;
                String lower = text.toLowerCase();
                // 中文：主动表达"做不到/不知道/太复杂"
                if (lower.contains("我无法") || lower.contains("我做不到") || lower.contains("我不知道")
                        || lower.contains("我不确定") || lower.contains("我不清楚") || lower.contains("我不明白")
                        || (lower.contains("超出") && lower.contains("能力")) || lower.contains("太复杂")
                        || lower.contains("无法处理") || lower.contains("无法完成") || lower.contains("无法回答")
                        || (lower.contains("抱歉，我") && (lower.contains("无法") || lower.contains("不能"))))
                    return true;
                // 英文：主动表达无能为力
                if (lower.contains("i cannot") || lower.contains("i'm unable") || lower.contains("i am unable")
                        || lower.contains("i don't know how") || lower.contains("too complex")
                        || lower.contains("beyond my") || lower.contains("i'm not able"))
                    return true;
                return false;
            }

            /** 从 ChatResponse 中提取文本内容 */
            private String extractText(ChatResponse resp) {
                try {
                    if (resp == null || resp.getResults() == null || resp.getResults().isEmpty()) return null;
                    var output = resp.getResults().get(0).getOutput();
                    return output != null ? output.getText() : null;
                } catch (Exception e) {
                    return null;
                }
            }

            // ---- 网络异常判断 ----
            private boolean isNetworkError(Throwable e) {
                if (e == null) return false;
                String name = e.getClass().getName();
                return name.contains("Timeout") || name.contains("Connect")
                        || name.contains("IOException") || name.contains("Socket")
                        || name.contains("ResourceAccess") || name.contains("HttpClient")
                        || name.contains("NonTransientAiException")
                        || (e.getCause() != null && isNetworkError(e.getCause()));
            }
        }).build();
        }
    }

    // ============================================================
    // 模型工厂方法（在 DesktopBrainConfig 中调用）
    // ============================================================

    /** 模型1：本地 Ollama qwen3:30b（SSH隧道 → AutoDL RTX3090） */
    static OpenAiChatModel buildModel1(DesktopBrainProperties props) {
        // Netty ReactorClientHttpRequestFactory 替代 HttpURLConnection
        ReactorClientHttpRequestFactory factory = new ReactorClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(10));  // CPU 跑 30B 模型 + 大 prompt 很慢

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(props.exploration().baseUrl())
                .apiKey("ollama")
                .completionsPath("/chat/completions")
                .restClientBuilder(RestClient.builder()
                        .requestFactory(factory)
                        .defaultHeader("Accept", "application/json")
                        .defaultHeader("Content-Type", "application/json"))
                .build();
        log.info("{}🔧 [ModelRouter] 本地Ollama模型: {}/chat/completions | model={}{}", C1, props.exploration().baseUrl(), props.exploration().model(), R);
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(props.exploration().model())
                        .build())
                .build();
    }

    /** 模型2：DeepSeek API 远程模型（故障转移 / 能力不足时求助） */
    static OpenAiChatModel buildModel2(DesktopBrainProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(120000);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(props.deepseek().baseUrl())
                .apiKey(props.deepseek().apiKey())
                .completionsPath("/chat/completions")
                .restClientBuilder(RestClient.builder().requestFactory(factory))
                .build();
        log.info("{}🔧 [ModelRouter] DeepSeek 备用: {}/chat/completions | model={}{}", C2, props.deepseek().baseUrl(), props.deepseek().model(), R);
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(props.deepseek().model())
                        .build())
                .build();
    }

    // ============================================================
    // 对外 API
    // ============================================================

    /** 普通对话/探索统一入口：本地Ollama 优先，能力不足或故障时降级 DeepSeek */
    public ChatClient normal() {
        return failover;
    }

    /** 探索模式（与普通对话共用同一路由） */
    public ChatClient exploration() {
        return failover;
    }
}
