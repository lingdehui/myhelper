package com.example.desktopbrain.memory.vector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 嵌入向量服务：调用 Ollama 嵌入 API 生成语义向量
 * 模型：nomic-embed-text（768维），与 Qdrant 配置一致
 */
@Service
public class EmbeddingService {

    private final WebClient ollamaClient;

    @Value("${embedding.model:nomic-embed-text}")
    private String model;

    public EmbeddingService() {
        this.ollamaClient = WebClient.builder()
                .baseUrl("http://localhost:11434")
                .build();
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
                    System.err.println("⚠️ Ollama 嵌入API返回空: " + text);
                    result.add(zeroVector());
                }
            } catch (Exception e) {
                System.err.println("❌ Ollama 嵌入失败: " + e.getMessage());
                result.add(zeroVector());
            }
        }
        return result;
    }

    private List<Float> zeroVector() {
        List<Float> v = new ArrayList<>(768);
        for (int i = 0; i < 768; i++) v.add(0.0f);
        return v;
    }
}