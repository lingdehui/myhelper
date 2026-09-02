package com.example.myhelper.config;

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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 模型路由器 — <b>项目中所有 AI 调用的唯一入口，不可绕过。</b>
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li><b>统一入口</b>：所有需要 AI 模型的模块（规划、执行、反思、分类、工具生成…）
 *       只需调用 {@link #chat()} 或 {@link #chat(Mode)}，不再关心模型来源。</li>
 *   <li><b>配置驱动</b>：读取 {@code application.yml} 的 {@code normal-model} 和
 *       {@code exploration-model} 开关，决定用模型1还是模型2。</li>
 *   <li><b>故障转移</b>：模型1 不可用时（网络故障/能力不足）自动降级到模型2。</li>
 *   <li><b>智能咨询</b>：模型1 回复不充分时，调用模型2 获取参考意见，交给模型1 综合后回复。</li>
 *   <li><b>禁止绕过</b>：除了 {@code FallbackModelTool}（模型1 主动调工具咨询模型2 的架构设计），
 *       项目中任何其他代码不得直接使用 {@code ChatClient} / {@code OpenAiChatModel}。</li>
 * </ul>
 *
 * <h2>两种模式</h2>
 * <table border="1">
 *   <tr><th>模式</th><th>配置键</th><th>说明</th></tr>
 *   <tr><td>{@link Mode#NORMAL NORMAL}</td><td>{@code normal-model}</td>
 *       <td>普通对话/规划/反思/分类/工具生成。<br>
 *           model2 → 直连 DeepSeek；model1 → 本地优先 failover 到 DeepSeek</td></tr>
 *   <tr><td>{@link Mode#EXPLORATION EXPLORATION}</td><td>{@code exploration-model}</td>
 *       <td>自主探索引擎专用。<br>
 *           model2 → 全程 DeepSeek；model1 → 本地优先 failover</td></tr>
 * </table>
 *
 * <h2>架构图</h2>
 * <pre>
 *                  ┌────────────────────────────────────────────┐
 *                  │              ModelRouter                    │
 *                  │  chat() → NORMAL  │ chat(EXPLORATION)      │
 *                  │       ↓                  ↓                  │
 *                  │  normal-model      exploration-model       │
 *                  │       ↓                  ↓                  │
 *                  │  model1 ──failover──→ model2               │
 *                  │  (本地 Ollama)      (DeepSeek API)          │
 *                  └────────────────────────────────────────────┘
 *                          ↑                    ↑
 *          ┌───────────────┴────────────────────┴───────────────┐
 *          │  ToolPlanner  ReflectService  TurnProcessor  ...   │
 *          │  (只传 Mode，不读配置，不关心底层模型)                  │
 *          └────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 *   // 默认普通模式
 *   modelRouter.chat().prompt().user("问题").call().content();
 *
 *   // 探索模式
 *   modelRouter.chat(Mode.EXPLORATION).prompt().user("问题").call().content();
 *
 *   // 纯云端（分类同步等必须走 DeepSeek 的场景）
 *   //   → 已内置在 chat() 中，model2 时自动返回 cloudOnly，外部无需区分
 * }</pre>
 *
 * <h2>反模式（禁止）</h2>
 * <pre>{@code
 *   // 禁止：直接读配置判断模型
 *   String model = props.normalModel();               // ✗
 *   if ("model2".equals(model)) ...                   // ✗
 *
 *   // 禁止：直接创建/注入 ChatClient
 *   ChatClient client = ChatClient.builder(model).build(); // ✗
 *
 *   // 禁止：直接注入 OpenAiChatModel
 *   @Qualifier("model2") OpenAiChatModel model2;      // ✗ (除 ModelRouter 自身和 FallbackModelTool)
 *
 *   // 正确：只走 ModelRouter
 *   modelRouter.chat() / modelRouter.chat(mode)       // ✓
 * }</pre>
 *
 * @see Mode
 * @see MyHelperProperties#normalModel()
 * @see MyHelperProperties.Exploration#explorationModel()
 */
@Component
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final ChatClient model1;      // 本地 Ollama qwen3:30b（主）
    private final ChatClient failover;    // 模型1 → 网络故障/能力不足 → 模型2 DeepSeek
    private final ChatClient model2Direct;// 直接走模型2（分类等不需要本地的场景）
    private final OpenAiChatModel model1Raw;
    private final OpenAiChatModel model2Raw;
    private final boolean sameModel;      // 模型1和模型2是同一个对象（模型2未配置时）
    private final MyHelperProperties props; // 配置引用

    private static final String C1 = "\u001b[32m"; // 绿色=模型1 本地Ollama
    private static final String C2 = "\u001b[34m"; // 蓝色=模型2 DeepSeek API（备）
    private static final String R  = "\u001b[0m";  // 重置

    public ModelRouter(MyHelperProperties props,
                       @Qualifier("model1") OpenAiChatModel model1Raw,
                       @Qualifier("model2") OpenAiChatModel model2Raw) {
        String model1Name = props.exploration().model();
        String model2Name = props.deepseek() != null ? props.deepseek().model() : "(未配置)";
        String model1Url = props.exploration().baseUrl();
        String model2Url = props.deepseek() != null ? props.deepseek().baseUrl() : "(未配置)";

        log.info("{}🔧 [ModelRouter] 本地Ollama模型: {} | model={}{}", C1, model1Url, model1Name, R);
        log.info("{}🔧 [ModelRouter] DeepSeek 备用: {} | model={}{}", C2, model2Url, model2Name, R);

        this.model1 = ChatClient.builder(model1Raw).build();
        this.model1Raw = model1Raw;
        this.model2Raw = model2Raw;
        this.sameModel = (model1Raw == model2Raw);
        this.props = props;

        // 模型2 直连（不经过 failover 逻辑，用于分类等纯云端场景）
        this.model2Direct = sameModel ? null : ChatClient.builder(model2Raw).build();

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
                    if (ModelRouter.this.isInadequate(resp1)) {
                        log.warn("{}🤔 [ModelRouter] 模型1 回复不充分（{}ms），咨询模型2 ({})...{}",
                                C1, elapsed1, model2Name, R);

                        long start2 = System.currentTimeMillis();
                        ChatResponse resp2 = model2Raw.call(prompt);
                        long elapsed2 = System.currentTimeMillis() - start2;
                        log.info("{}📋 [ModelRouter] 模型2 参考回复 ({}ms){}", C2, elapsed2, R);

                        // Phase 3: 模型2的回复交给模型1综合
                        String refText = ModelRouter.this.extractText(resp2);
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
                    if (ModelRouter.this.isNetworkError(e)) {
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

        }).build();
        }
    }

    // ---- 判断回复是否不充分 ----
    private boolean isInadequate(ChatResponse resp) {
        String text = extractText(resp);
        if (text == null || text.isBlank()) return true;
        String lower = text.toLowerCase();
        if (lower.contains("我无法") || lower.contains("我做不到") || lower.contains("我不知道")
                || lower.contains("我不确定") || lower.contains("我不清楚") || lower.contains("我不明白")
                || (lower.contains("超出") && lower.contains("能力")) || lower.contains("太复杂")
                || lower.contains("无法处理") || lower.contains("无法完成") || lower.contains("无法回答")
                || (lower.contains("抱歉，我") && (lower.contains("无法") || lower.contains("不能"))))
            return true;
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

    // ---- HTTP 请求日志（记录方法/URI/状态/耗时 + token 用量） ----
    private static ClientHttpRequestInterceptor httpBodyLoggingInterceptor() {
        return (request, body, execution) -> {
            long start = System.currentTimeMillis();
            log.info("🌐 [HTTP] → {} {}", request.getMethod(), request.getURI());
            ClientHttpResponse response = execution.execute(request, body);
            long elapsed = System.currentTimeMillis() - start;
            ClientHttpResponse buffered = new BufferedResponse(response);
            log.info("🌐 [HTTP] ← {} {} ({}ms){}",
                    response.getStatusCode(), response.getStatusText(), elapsed,
                    extractUsage(buffered));
            return buffered;
        };
    }

    /** 从响应体解析 usage 字段（DeepSeek/Ollama 均返回 prompt/completion/total_tokens）。 */
    private static String extractUsage(ClientHttpResponse response) {
        try (InputStream in = response.getBody()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (json == null || json.isBlank()) return "";
            Matcher m = Pattern.compile("\"usage\"\\s*:\\s*\\{([^}]*)\\}").matcher(json);
            if (!m.find()) return "";
            String usage = m.group(1);
            long prompt = extractLong(usage, "prompt_tokens");
            long completion = extractLong(usage, "completion_tokens");
            long total = extractLong(usage, "total_tokens");
            return String.format(" | tokens: in=%d out=%d total=%d", prompt, completion, total);
        } catch (Exception e) {
            return "";
        }
    }

    private static long extractLong(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1L;
    }

    /** 缓存响应体的 ClientHttpResponse 包装器：先读一次 body 记录日志，再返回可重复读取的 body。 */
    private static final class BufferedResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        BufferedResponse(ClientHttpResponse delegate) throws IOException {
            this.delegate = delegate;
            try (InputStream in = delegate.getBody()) {
                this.body = in.readAllBytes();
            }
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    // ============================================================
    // 模型工厂方法（在 MyHelperConfig 中调用）
    // ============================================================

    /** 模型1：本地 Ollama qwen3:30b（SSH隧道 → AutoDL RTX3090） */
    static OpenAiChatModel buildModel1(MyHelperProperties props) {
        // Netty ReactorClientHttpRequestFactory 替代 HttpURLConnection
        ReactorClientHttpRequestFactory factory = new ReactorClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofMinutes(10));  // CPU 跑 30B 模型 + 大 prompt 很慢

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(props.exploration().baseUrl())
                .apiKey("ollama")
                .completionsPath("/chat/completions")
                .restClientBuilder(RestClient.builder()
                        .requestFactory(new BufferingClientHttpRequestFactory(factory))
                        .requestInterceptor(httpBodyLoggingInterceptor())
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
    static OpenAiChatModel buildModel2(MyHelperProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000);
        factory.setReadTimeout(120000);

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(props.deepseek().baseUrl())
                .apiKey(props.deepseek().apiKey())
                .completionsPath("/chat/completions")
                .restClientBuilder(RestClient.builder()
                        .requestFactory(new BufferingClientHttpRequestFactory(factory))
                        .requestInterceptor(httpBodyLoggingInterceptor()))
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
    // 对外 API — 统一入口，外部只需要传模式，不读配置
    // ============================================================

    public enum Mode { NORMAL, EXPLORATION }

    /** 默认普通模式 */
    public ChatClient chat() {
        return chat(Mode.NORMAL);
    }

    /** 根据模式 + 配置开关返回对应模型 */
    public ChatClient chat(Mode mode) {
        String modelConfig = (mode == Mode.EXPLORATION)
                ? props.exploration().explorationModel()
                : props.normalModel();
        if (modelConfig == null) modelConfig = "model1";
        if ("model2".equalsIgnoreCase(modelConfig) && isCloudAvailable()) {
            return cloudOnly();
        }
        return failover;
    }

    /** 云端 AI 是否可用（DeepSeek 已配置） */
    public boolean isCloudAvailable() {
        return !sameModel;
    }

    /** 强制走云端 DeepSeek，用于分类等不需要本地模型的场景 */
    public ChatClient cloudOnly() {
        if (model2Direct == null) {
            throw new IllegalStateException("云端 AI 未配置，不能直接调用 cloudOnly()");
        }
        return model2Direct;
    }
}
