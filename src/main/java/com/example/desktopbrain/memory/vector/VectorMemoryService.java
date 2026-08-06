package com.example.desktopbrain.memory.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 向量记忆服务：存储和检索对话历史、上下文记忆
 * 对应架构中的"记忆与人格层 - 向量数据库"
 * 使用 Qdrant REST API
 */
@Service
public class VectorMemoryService {

    private final WebClient qdrant;
    private final String collectionName;
    private final ObjectMapper objectMapper;
    private final AtomicLong idCounter = new AtomicLong(System.currentTimeMillis());

    public VectorMemoryService(WebClient qdrantWebClient,
                                @Qualifier("qdrantCollectionName") String collectionName) {
        this.qdrant = qdrantWebClient;
        this.collectionName = collectionName;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 搜索结果
     */
    public record SearchResult(String text, float score, Map<String, Object> metadata) {
        @Override
        public String toString() {
            return String.format("[%.2f] %s", score, text);
        }
    }

    /**
     * 存储记忆：文本 + 向量 + 元数据
     */
    @SuppressWarnings("unchecked")
    public void store(String text, List<Float> vector, Map<String, String> metadata) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("text", text);
            if (metadata != null) {
                payload.putAll(metadata);
            }

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", idCounter.incrementAndGet());
            point.put("vector", vector);
            point.put("payload", payload);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("points", List.of(point));

            qdrant.put()
                    .uri("/collections/" + collectionName + "/points?wait=true")
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            System.err.println("❌ Qdrant 存储失败: " + e.getMessage());
        }
    }

    /**
     * 语义搜索：返回最相关的记忆
     */
    @SuppressWarnings("unchecked")
    public List<SearchResult> search(List<Float> queryVector, int limit) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", queryVector);
            body.put("limit", limit);
            body.put("with_payload", true);

            Map<String, Object> response = qdrant.post()
                    .uri("/collections/" + collectionName + "/points/search")
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            List<SearchResult> results = new ArrayList<>();
            if (response != null) {
                List<Map<String, Object>> points = (List<Map<String, Object>>) response.getOrDefault("result", List.of());
                for (Map<String, Object> point : points) {
                    Map<String, Object> payload = (Map<String, Object>) point.getOrDefault("payload", Map.of());
                    String text = (String) payload.getOrDefault("text", "");
                    float score = ((Number) point.getOrDefault("score", 0.0f)).floatValue();

                    Map<String, Object> meta = new HashMap<>(payload);
                    meta.remove("text");
                    results.add(new SearchResult(text, score, meta));
                }
            }
            return results;
        } catch (Exception e) {
            System.err.println("❌ Qdrant 搜索失败: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取集合中记忆数量
     */
    @SuppressWarnings("unchecked")
    public long count() {
        try {
            Map<String, Object> response = qdrant.get()
                    .uri("/collections/" + collectionName)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (response != null) {
                Map<String, Object> result = (Map<String, Object>) response.get("result");
                if (result != null) {
                    return ((Number) result.getOrDefault("points_count", 0)).longValue();
                }
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }
}