package com.example.desktopbrain.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 双模型原始 Bean 配置。ChatClient 的组装和路由由 {@link ModelRouter} 统一管理。
 *
 * <pre>
 *   {@code @Qualifier("model1")} OpenAiChatModel → DeepSeek 云端（主）
 *   {@code @Qualifier("model2")} OpenAiChatModel → DeepSeek 云端（备）
 * </pre>
 */
@Configuration
@EnableConfigurationProperties(DesktopBrainProperties.class)
@EnableScheduling
public class DesktopBrainConfig {

    private static final Logger log = LoggerFactory.getLogger(DesktopBrainConfig.class);

    private final DesktopBrainProperties props;

    public DesktopBrainConfig(DesktopBrainProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void testDeepSeekConnection() {
        if (props.deepseek() == null) {
            log.info("🧪 [连接测试] 模型1 未配置（已注释），跳过");
            return;
        }
        String url = props.deepseek().baseUrl() + "/chat/completions";
        log.info("🧪 [连接测试] URL: {}", url);
        log.info("🧪 [连接测试] model: {}", props.deepseek().model());
        log.info("🧪 [连接测试] apiKey: {}...", props.deepseek().apiKey().substring(0, 10));
        try {
            HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + props.deepseek().apiKey());
            conn.setDoOutput(true);

            String body = "{\"model\":\"" + props.deepseek().model() + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":10}";
            conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            conn.getOutputStream().close();

            int code = conn.getResponseCode();
            log.info("🧪 [连接测试] HTTP状态码: {}", code);

            if (code >= 200 && code < 300) {
                byte[] respBytes = conn.getInputStream().readAllBytes();
                String resp = new String(respBytes, StandardCharsets.UTF_8);
                log.info("🧪 [连接测试] 响应(前300字): {}", resp.substring(0, Math.min(300, resp.length())));
            } else {
                byte[] errBytes = conn.getErrorStream().readAllBytes();
                String err = new String(errBytes, StandardCharsets.UTF_8);
                log.warn("🧪 [连接测试] 错误响应: {}", err);
            }
            conn.disconnect();
        } catch (Exception e) {
            log.error("🧪 [连接测试] 失败: {} - {}", e.getClass().getName(), e.getMessage());
        }
    }

    @Bean
    public LinkedBlockingQueue<String> speechQueue() {
        return new LinkedBlockingQueue<>();
    }

    @Bean
    @Qualifier("model1")
    public OpenAiChatModel model1() {
        if (props.deepseek() == null) {
            // 模型1 已注释 → 用本地 Ollama（与模型2 相同）
            return model2();
        }
        return ModelRouter.buildModel1(props);
    }

    @Bean
    @Qualifier("model2")
    public OpenAiChatModel model2() {
        return ModelRouter.buildModel2(props);
    }
}
