package com.example.myhelper.memory.vector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 嵌入向量服务：调用 Ollama 嵌入 API 生成语义向量
 * 模型：nomic-embed-text（768维），与 Qdrant 配置一致
 *
 * <p>嵌入失败时抛 {@link EmbeddingException} 而不是返回零向量。
 * 调用方应捕获此异常并降级到不使用向量搜索的分支。零向量会导致 Qdrant 返回随机匹配。</p>
 */
@Service
public class EmbeddingService {

    @Value("${embedding.model:nomic-embed-text}")
    private String model;

    @Value("${ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;

    private WebClient ollamaClient;

    @jakarta.annotation.PostConstruct
    void initClient() {
        this.ollamaClient = WebClient.builder()
                .baseUrl(ollamaBaseUrl)
                .build();
    }

    /** 嵌入失败时抛出的异常 */
    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public List<Float> embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @SuppressWarnings("unchecked")
    public List<List<Float>> embedBatch(List<String> texts) {
        List<List<Float>> result = new ArrayList<>();
        for (String text : texts) {
            try {
                Map<String, Object> body = Map.of(
                        "model", model,
                        "prompt", text
                );
                Map<String, Object> response = ollamaClient.post()
                        .uri("/api/embeddings")
                        .header("Content-Type", "application/json")
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(Map.class)
                        .block();

                if (response != null && response.containsKey("embedding")) {
                    List<Double> raw = (List<Double>) response.get("embedding");
                    List<Float> vector = new ArrayList<>(raw.size());
                    for (Double d : raw) {
                        vector.add(d.floatValue());
                    }
                    result.add(vector);
                } else {
                    throw new EmbeddingException("Ollama 嵌入API返回空: " + text, null);
                }
            } catch (EmbeddingException e) {
                throw e;
            } catch (Exception e) {
                throw new EmbeddingException("Ollama 嵌入失败: " + e.getMessage(), e);
            }
        }
        return result;
    }
}