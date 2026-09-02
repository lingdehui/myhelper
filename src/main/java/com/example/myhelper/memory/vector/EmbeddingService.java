package com.example.myhelper.memory.vector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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

    /**
     * 最近嵌入结果的上限。设为 {@code 0} 可立即关闭缓存并恢复逐次请求的旧行为。
     *
     * <p>缓存键同时包含模型名和完整文本；模型切换后不会复用旧模型的向量。</p>
     */
    @Value("${embedding.cache-size:256}")
    private int cacheSize;

    private WebClient ollamaClient;
    /** 相同输入合并为一次在途请求，避免同一 Turn 的并行分支重复请求嵌入模型。 */
    private final ConcurrentMap<EmbeddingCacheKey, CompletableFuture<List<Float>>> embeddingCache =
            new ConcurrentHashMap<>();

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

    /**
     * 生成单条嵌入向量，并复用同一模型对相同文本的已完成结果。
     *
     * <p>嵌入模型在固定模型版本下是确定性的，因此复用只减少网络与模型计算，
     * 不改变向量检索、相似度阈值或后续规划逻辑。异常结果会立刻移出缓存；
     * 等待共享请求失败的调用者会自行再试一次原始请求，避免缓存放大瞬时故障。</p>
     */
    public List<Float> embed(String text) {
        if (cacheSize <= 0) return requestEmbedding(text);

        EmbeddingCacheKey key = new EmbeddingCacheKey(model == null ? "" : model, text == null ? "" : text);
        CompletableFuture<List<Float>> created = new CompletableFuture<>();
        CompletableFuture<List<Float>> cached = embeddingCache.putIfAbsent(key, created);

        if (cached == null) {
            try {
                List<Float> vector = List.copyOf(requestEmbedding(text));
                created.complete(vector);
                trimCompletedCache();
                return vector;
            } catch (RuntimeException e) {
                embeddingCache.remove(key, created);
                created.completeExceptionally(e);
                throw e;
            }
        }

        try {
            return cached.join();
        } catch (CompletionException e) {
            // 异常不会缓存；为当前调用保留一次独立重试机会，等价于旧的独立请求行为。
            embeddingCache.remove(key, cached);
            return requestEmbedding(text);
        }
    }

    @SuppressWarnings("unchecked")
    public List<List<Float>> embedBatch(List<String> texts) {
        List<List<Float>> result = new ArrayList<>();
        for (String text : texts) {
            result.add(embed(text));
        }
        return result;
    }

    /** 清空已完成嵌入缓存；可供模型切换、运维诊断或测试时显式调用。 */
    public void clearCache() {
        embeddingCache.clear();
    }

    /** 实际调用 Ollama 的无缓存实现。 */
    @SuppressWarnings("unchecked")
    private List<Float> requestEmbedding(String text) {
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "prompt", text == null ? "" : text
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
                for (Double value : raw) {
                    vector.add(value.floatValue());
                }
                return vector;
            }
            throw new EmbeddingException("Ollama 嵌入API返回空: " + text, null);
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("Ollama 嵌入失败: " + e.getMessage(), e);
        }
    }

    /** 仅移除已完成条目，避免淘汰正在被其他 Turn 等待的共享请求。 */
    private void trimCompletedCache() {
        int overflow = embeddingCache.size() - cacheSize;
        if (overflow <= 0) return;

        for (Map.Entry<EmbeddingCacheKey, CompletableFuture<List<Float>>> entry : embeddingCache.entrySet()) {
            if (overflow <= 0) break;
            if (entry.getValue().isDone() && embeddingCache.remove(entry.getKey(), entry.getValue())) {
                overflow--;
            }
        }
    }

    /** 缓存键必须包含模型名，避免不同嵌入模型的向量混用。 */
    private record EmbeddingCacheKey(String model, String text) { }
}
