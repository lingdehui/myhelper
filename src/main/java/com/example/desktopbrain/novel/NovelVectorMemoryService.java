package com.example.desktopbrain.novel;

import com.example.desktopbrain.memory.vector.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

/**
 * 小说向量记忆（Qdrant novel-memory 集合）。
 * 存每章的摘要向量 + 人物/情节/伏笔标记，用于后续章节检索上下文。
 */
@Service
public class NovelVectorMemoryService {

    private final WebClient qdrant;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    @Value("${qdrant.novel-collection:novel-memory}")
    private String collectionName;

    @Value("${qdrant.vector-size:768}")
    private int vectorSize;

    public NovelVectorMemoryService(WebClient qdrantWebClient, EmbeddingService embeddingService) {
        this.qdrant = qdrantWebClient;
        this.embeddingService = embeddingService;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void initCollection() {
        try {
            Boolean exists = qdrant.get()
                    .uri("/collections/" + collectionName)
                    .retrieve()
                    .toBodilessEntity()
                    .map(r -> true)
                    .onErrorReturn(false)
                    .block();
            if (Boolean.FALSE.equals(exists)) {
                String body = String.format("{\"vectors\": {\"size\": %d, \"distance\": \"Cosine\"}}", vectorSize);
                qdrant.put()
                        .uri("/collections/" + collectionName)
                        .header("Content-Type", "application/json")
                        .bodyValue(body)
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                System.out.println("📖 Qdrant 集合 '" + collectionName + "' 已创建");
            } else {
                System.out.println("📖 Qdrant 集合 '" + collectionName + "' 已存在");
            }
        } catch (Exception e) {
            System.err.println("⚠️ novel-memory 集合初始化失败: " + e.getMessage());
        }
    }

    /**
     * 存入一章的摘要向量。
     */
    public void saveChapterSummary(String novelName, int chapterNumber, String summary, String characters, String plotThreads) {
        try {
            List<Float> vector = embeddingService.embed(summary);
            String pointId = novelName + "-ch" + chapterNumber;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("novelName", novelName);
            payload.put("type", "chapter");
            payload.put("chapterNumber", chapterNumber);
            payload.put("summary", summary);
            payload.put("characters", characters);
            payload.put("plotThreads", plotThreads);
            payload.put("timestamp", System.currentTimeMillis());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("points", List.of(Map.of("id", pointId, "vector", vector, "payload", payload)));

            qdrant.put()
                    .uri("/collections/" + collectionName + "/points?wait=true")
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            System.err.println("❌ 保存章节摘要到 Qdrant 失败: " + e.getMessage());
        }
    }

    /**
     * 语义搜索：找与当前上下文最相关的已有章节。
     */
    public List<Map<String, Object>> searchRelevantContext(String novelName, String query, int limit) {
        try {
            List<Float> vector = embeddingService.embed(query);
            Map<String, Object> filter = Map.of("must", List.of(
                    Map.of("key", "novelName", "match", Map.of("value", novelName))
            ));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", vector);
            body.put("limit", limit);
            body.put("with_payload", true);
            body.put("filter", filter);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = qdrant.post()
                    .uri("/collections/" + collectionName + "/points/search")
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) return List.of();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> points = (List<Map<String, Object>>) response.getOrDefault("result", List.of());

            List<Map<String, Object>> results = new ArrayList<>();
            for (Map<String, Object> p : points) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) p.get("payload");
                if (payload != null) results.add(payload);
            }
            return results;
        } catch (Exception e) {
            System.err.println("❌ novel-memory 搜索失败: " + e.getMessage());
            return List.of();
        }
    }
}
