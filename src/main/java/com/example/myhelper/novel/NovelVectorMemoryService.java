package com.example.myhelper.novel;

import com.example.myhelper.config.SystemEnvironmentService;
import com.example.myhelper.memory.vector.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 小说向量记忆（Qdrant novel-memory 集合）。
 * 存每章的摘要向量 + 人物/情节/伏笔标记，用于后续章节检索上下文。
 */
@Service
public class NovelVectorMemoryService {

    private static final Logger log = LoggerFactory.getLogger(NovelVectorMemoryService.class);

    private final WebClient qdrant;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final SystemEnvironmentService envService;
    private final ExecutorService asyncExecutor;

    @Value("${qdrant.novel-collection:novel-memory}")
    private String baseCollectionName;
    private String collectionName;

    @Value("${qdrant.vector-size:768}")
    private int vectorSize;

    public NovelVectorMemoryService(WebClient qdrantWebClient, EmbeddingService embeddingService,
                                      SystemEnvironmentService envService,
                                      @Qualifier("asyncExecutor") ExecutorService asyncExecutor) {
        this.qdrant = qdrantWebClient;
        this.embeddingService = embeddingService;
        this.envService = envService;
        this.asyncExecutor = asyncExecutor;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void initCollection() {
        this.collectionName = envService.collectionName(baseCollectionName);
        try {
            int existingSize = fetchVectorSize();
            if (existingSize == vectorSize) {
                log.info("📖 Qdrant 集合 '{}' 已存在（{} 维）", collectionName, existingSize);
            } else {
                if (existingSize > 0) {
                    log.warn("⚠️ novel-memory 集合维度不匹配（现有 {} 维，期望 {} 维），删除重建",
                            existingSize, vectorSize);
                    qdrant.delete().uri("/collections/" + collectionName)
                            .retrieve().toBodilessEntity().block();
                }
                createCollection();
            }
        } catch (Exception e) {
            log.warn("⚠️ novel-memory 集合初始化失败", e);
        }
    }

    /** 读取现有集合的向量维度；集合不存在或读取失败返回 0。 */
    @SuppressWarnings("unchecked")
    private int fetchVectorSize() {
        try {
            Map<String, Object> info = qdrant.get()
                    .uri("/collections/" + collectionName)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (info == null || info.get("result") == null) return 0;
            Map<String, Object> result = (Map<String, Object>) info.get("result");
            Map<String, Object> config = (Map<String, Object>) result.get("config");
            Map<String, Object> params = (Map<String, Object>) config.get("params");
            Map<String, Object> vectors = (Map<String, Object>) params.get("vectors");
            Object size = vectors.get("size");
            return size instanceof Number ? ((Number) size).intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void createCollection() {
        String body = String.format("{\"vectors\": {\"size\": %d, \"distance\": \"Cosine\"}}", vectorSize);
        qdrant.put()
                .uri("/collections/" + collectionName)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
        log.info("📖 Qdrant 集合 '{}' 已创建（{} 维）", collectionName, vectorSize);
    }

    /**
     * 存入一章的摘要向量（异步，不阻塞写章节主流程）。
     */
    public void saveChapterSummary(String novelName, int chapterNumber, String summary, String characters, String plotThreads) {
        CompletableFuture.runAsync(() -> {
            try {
                List<Float> vector = embeddingService.embed(summary);
                String pointId = pointId(novelName + "-ch" + chapterNumber);
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
                log.error("❌ 保存章节摘要到 Qdrant 失败", e);
            }
        }, asyncExecutor);
    }

    /**
     * 存入大纲向量（type=outline，用于语义检索大纲片段，异步）。
     */
    public void saveOutline(String novelName, String outlineText) {
        CompletableFuture.runAsync(() -> {
            try {
                List<Float> vector = embeddingService.embed(outlineText);
                String pointId = pointId(novelName + "-outline");
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("novelName", novelName);
                payload.put("type", "outline");
                payload.put("outline", outlineText);
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
                log.error("❌ 保存大纲到 Qdrant 失败", e);
            }
        }, asyncExecutor);
    }

    /**
     * 生成确定性 UUID 作为 Qdrant point id。
     * Qdrant 只接受 UUID 或无符号整数，普通字符串 id 会 400；同 seed 重复写入会幂等覆盖同一点。
     */
    private String pointId(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();
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
            log.error("❌ novel-memory 搜索失败", e);
            return List.of();
        }
    }

    /**
     * 删除某部小说的全部向量（按 payload.novelName 过滤）。
     * Qdrant points/delete 支持 filter，无需逐个枚举 point id。
     */
    public void deleteNovel(String novelName) {
        try {
            Map<String, Object> filter = Map.of("must", List.of(
                    Map.of("key", "novelName", "match", Map.of("value", novelName))
            ));
            Map<String, Object> body = Map.of("filter", filter);
            qdrant.post()
                    .uri("/collections/" + collectionName + "/points/delete?wait=true")
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
            log.info("🗑️ 已删除小说 '{}' 的 Qdrant 向量", novelName);
        } catch (Exception e) {
            log.warn("⚠️ 删除小说 '{}' 的 Qdrant 向量失败: {}", novelName, e.getMessage());
        }
    }
}
