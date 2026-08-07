package com.example.desktopbrain.memory.vector.episode;

import com.example.desktopbrain.common.AiResponseUtils;
import com.example.desktopbrain.common.HaApiPaths;
import com.example.desktopbrain.common.QdrantFields;
import com.example.desktopbrain.memory.graph.FailurePatternNode;
import com.example.desktopbrain.memory.graph.FailurePatternRepository;
import com.example.desktopbrain.memory.vector.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.concurrent.CompletableFuture;

import static com.example.desktopbrain.common.HaApiPaths.APPLICATION_JSON;
import static com.example.desktopbrain.common.HaApiPaths.CONTENT_TYPE;
import static com.example.desktopbrain.common.QdrantFields.*;

/**
 * Episode 缓存服务（核心）。
 *
 * <p>基于 Qdrant REST API 实现 Episode 的持久化、向量检索、失败统计与自动归档。
 * 借鉴 ExpeL/MUSE 经验学习思想：每次成功执行的完整轨迹作为 Episode 存入 Qdrant，
 * 下次相似请求向量检索复用其方案；失败时 AI 归因（计划问题 vs 环境问题），
 * 只惩罚计划问题，环境问题不惩罚（分段继续）。</p>
 *
 * <h3>完整闭环：Plan→Execute→Reflect→Memorize</h3>
 * <ul>
 *   <li><b>成功</b>：ReflectService 总结 successLesson → 存 Episode（含计划+经验）</li>
 *   <li><b>失败</b>：ReflectService 归因+提取 failureLesson →
 *       计划问题：failureCount+1（达阈值 archive）；
 *       环境问题：不+1（分段继续，计划本身没问题）</li>
 *   <li><b>复用</b>：向量检索命中 → 返回 Episode（含 successLesson 作为 hint，
 *       failureLesson 作为警示）→ AI 判断可用性 → 按计划走</li>
 * </ul>
 *
 * <p>所有写操作异步执行，Qdrant 故障吞异常不传染主流程。</p>
 */
@Service
public class EpisodeCacheService {

    private final WebClient qdrant;
    private final EmbeddingService embeddingService;
    private final ReflectService reflectService;
    private final UnitLearner unitLearner;
    private final FailurePatternRepository failurePatternRepo;
    private final ObjectMapper objectMapper;

    @Value("${qdrant.episodes-collection:episodes}")
    private String collectionName;

    @Value("${qdrant.failure-patterns-collection:failure-patterns}")
    private String failurePatternsCollection;

    @Value("${qdrant.vector-size:768}")
    private int vectorSize;

    @Value("${qdrant.episode.similarity-threshold:0.65}")
    private double similarityThreshold;

    @Value("${qdrant.episode.stability-threshold:0.6}")
    private double stabilityThreshold;

    @Value("${qdrant.episode.failure-threshold:3}")
    private int failureThreshold;

    @Value("${qdrant.episode.top-k:3}")
    private int topK;

    /** 脚本化阈值：successCount≥此值 且 stability>0.9 时 canScript=true */
    @Value("${qdrant.episode.script-success-threshold:5}")
    private int scriptSuccessThreshold;

    public EpisodeCacheService(WebClient qdrantWebClient,
                                EmbeddingService embeddingService,
                                ReflectService reflectService,
                                UnitLearner unitLearner,
                                FailurePatternRepository failurePatternRepo) {
        this.qdrant = qdrantWebClient;
        this.embeddingService = embeddingService;
        this.reflectService = reflectService;
        this.unitLearner = unitLearner;
        this.failurePatternRepo = failurePatternRepo;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 启动时初始化 episodes collection（idempotent）。
     *
     * <p>重启恢复：清理所有遗留的 DRAFT points（前次运行未完成的执行），
     * 避免 orphan DRAFT 永久占用存储。</p>
     */
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
                String body = String.format(
                        "{\"vectors\": {\"size\": %d, \"distance\": \"Cosine\"}}", vectorSize);
                qdrant.put()
                        .uri("/collections/" + collectionName)
                        .header(CONTENT_TYPE, APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                System.out.println("📦 Qdrant 集合 '" + collectionName + "' 已创建（向量维度: " + vectorSize + "）");
            } else {
                System.out.println("📦 Qdrant 集合 '" + collectionName + "' 已存在");
                // 清理遗留的 DRAFT points（前次运行未完成的执行）
                cleanupDraftPoints();
            }
            // 同时初始化 failure-patterns collection
            initFailurePatternsCollection();
        } catch (Exception e) {
            System.err.println("⚠️ Episode collection 初始化失败（episode 系统将降级）: " + e.getMessage());
        }
    }

    /** 初始化 failure-patterns collection（idempotent） */
    private void initFailurePatternsCollection() {
        try {
            Boolean exists = qdrant.get()
                    .uri("/collections/" + failurePatternsCollection)
                    .retrieve()
                    .toBodilessEntity()
                    .map(r -> true)
                    .onErrorReturn(false)
                    .block();
            if (Boolean.FALSE.equals(exists)) {
                String body = String.format(
                        "{\"vectors\": {\"size\": %d, \"distance\": \"Cosine\"}}", vectorSize);
                qdrant.put()
                        .uri("/collections/" + failurePatternsCollection)
                        .header(CONTENT_TYPE, APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                System.out.println("📦 Qdrant 集合 '" + failurePatternsCollection + "' 已创建（向量维度: " + vectorSize + "）");
            } else {
                System.out.println("📦 Qdrant 集合 '" + failurePatternsCollection + "' 已存在");
            }
        } catch (Exception e) {
            System.err.println("⚠️ FailurePatterns collection 初始化失败: " + e.getMessage());
        }
    }

    /**
     * 清理所有 status=DRAFT 的遗留 points。
     * Qdrant 重启后，之前的 DRAFT 不再有对应的内存 episodeId，
     * 永远无法被 activate/fail，必须清理。
     */
    @SuppressWarnings("unchecked")
    private void cleanupDraftPoints() {
        try {
            // 1. scroll 获取所有 DRAFT points 的 ID
            Map<String, Object> filter = Map.of(MUST, List.of(
                    Map.of(KEY, STATUS, MATCH, Map.of(VALUE, "DRAFT"))
            ));
            Map<String, Object> scrollBody = new LinkedHashMap<>();
            scrollBody.put(FILTER, filter);
            scrollBody.put(WITH_PAYLOAD, false);
            scrollBody.put(WITH_VECTOR, false);
            scrollBody.put(LIMIT, 1000);

            Map<String, Object> response = qdrant.post()
                    .uri("/collections/" + collectionName + "/points/scroll")
                    .header(CONTENT_TYPE, APPLICATION_JSON)
                    .bodyValue(scrollBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) return;
            Map<String, Object> result = (Map<String, Object>) response.get(RESULT);
            if (result == null) return;
            List<Map<String, Object>> points = (List<Map<String, Object>>) result.getOrDefault(POINTS, List.of());
            if (points.isEmpty()) return;

            List<String> draftIds = new ArrayList<>();
            for (Map<String, Object> p : points) {
                Object id = p.get(ID);
                if (id != null) draftIds.add(String.valueOf(id));
            }

            // 2. 批量删除
            Map<String, Object> deleteBody = Map.of("points", draftIds);
            Map<String, Object> deleteResp = qdrant.post()
                    .uri("/collections/" + collectionName + "/points/delete?wait=true")
                    .header(CONTENT_TYPE, APPLICATION_JSON)
                    .bodyValue(deleteBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (deleteResp != null) {
                Map<String, Object> deleteResult = (Map<String, Object>) deleteResp.get("result");
                String status = deleteResult != null ? String.valueOf(deleteResult.getOrDefault("status", "ok")) : "ok";
                System.out.println("🧹 清理遗留 DRAFT points: " + draftIds.size() + " 个 (" + status + ")");
            }
        } catch (Exception e) {
            System.err.println("⚠️ DRAFT 清理失败（不影响正常功能）: " + e.getMessage());
        }
    }

    // ========== 查询 ==========

    /**
     * 向量检索相似 episode（带 filter: archived=false AND stability>=阈值）。
     *
     * @return 命中的最佳 episode；未命中或出错时返回 Optional.empty()
     */
    public Optional<Episode> findSimilarEpisode(String userInput) {
        try {
            List<Float> queryVector = embeddingService.embed(userInput);

            Map<String, Object> filter = Map.of(MUST, List.of(
                    Map.of(KEY, ARCHIVED, MATCH, Map.of(VALUE, false)),
                    Map.of(KEY, STABILITY, RANGE, Map.of(GTE, stabilityThreshold)),
                    Map.of(KEY, STATUS, MATCH, Map.of(VALUE, "ACTIVE"))
            ));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put(VECTOR, queryVector);
            body.put(LIMIT, topK);
            body.put(WITH_PAYLOAD, true);
            body.put(SCORE_THRESHOLD, (float) similarityThreshold);
            body.put(FILTER, filter);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = qdrant.post()
                    .uri("/collections/" + collectionName + "/points/search")
                    .header(CONTENT_TYPE, APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) return Optional.empty();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> points = (List<Map<String, Object>>) response.getOrDefault("result", List.of());
            if (points.isEmpty()) return Optional.empty();

            List<Episode> episodes = new ArrayList<>();
            for (Map<String, Object> point : points) {
                Episode ep = deserializeEpisode(point);
                if (ep != null) episodes.add(ep);
            }
            if (episodes.isEmpty()) return Optional.empty();

            episodes.sort((a, b) -> Double.compare(b.computedStability(), a.computedStability()));
            Episode best = episodes.get(0);
            System.out.println("💾 命中 Episode 缓存（稳定度: " + best.computedStability() + "）");
            return Optional.of(best);
        } catch (Exception e) {
            System.err.println("❌ Qdrant 搜索失败: " + e.getMessage());
            return Optional.empty();
        }
    }

    // ========== 写入（draft→active/failed 生命周期） ==========

    /**
     * 创建 DRAFT episode（执行前调用，决策4：立即创建）。
     *
     * <p>同步生成 episodeId + 同步写入 Qdrant（确保 draft 存在再执行）。
     * toolCalls 暂为空列表，执行中逐步收集，最终由 activateDraft/failDraft 更新。</p>
     *
     * @param userInput         用户原话
     * @param selectedToolNames 选中的工具名列表
     * @param missingDescriptions 缺失的工具描述
     * @return 新生成的 episodeId；写入失败时返回 null
     */
    public String createDraft(String userInput,
                               List<String> selectedToolNames,
                               List<String> missingDescriptions) {
        String episodeId = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        try {
            List<Float> vector = embeddingService.embed(userInput);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", episodeId);
            payload.put("userInput", userInput);
            payload.put("selectedToolNames", selectedToolNames);
            payload.put("missingDescriptions", missingDescriptions);
            payload.put("toolCalls", List.of());  // 执行中逐步收集
            payload.put("aiResponse", null);
            payload.put("successLesson", null);
            payload.put("failureLesson", null);
            payload.put("signature", Map.of());
            payload.put("unitType", Episode.UnitType.COMPOSITE.name());
            payload.put("isGeneric", false);
            payload.put("parentIds", List.of());
            payload.put("successCount", 0);
            payload.put("failureCount", 0);
            payload.put("archived", false);
            payload.put("timestamp", timestamp);
            payload.put("stability", 0.0);
            payload.put("status", Episode.EpisodeStatus.DRAFT.name());
            payload.put("canScript", false);
            payload.put("failedStepIndex", -1);

            upsertPoint(episodeId, vector, payload);
            System.out.println("📝 已创建 DRAFT Episode（id=" + episodeId.substring(0, 8) + "...）");
            return episodeId;
        } catch (Exception e) {
            System.err.println("❌ DRAFT Episode 创建失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * DRAFT → ACTIVE（执行成功时调用）。
     *
     * <p>更新 toolCalls + aiResponse + successLesson + successCount+1 + stability 重算 +
     * status=ACTIVE。successCount≥阈值且 stability>0.9 时 canScript=true。</p>
     *
     * @param episodeId      createDraft 返回的 id
     * @param toolCalls      完整工具调用轨迹
     * @param aiResponse     AI 最终回复
     * @param successLesson  成功经验（AI 反思总结，可为 null）
     */
    public void activateDraft(String episodeId, List<ToolCallLog> toolCalls,
                               String aiResponse, String successLesson) {
        if (episodeId == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                // 重试获取：createDraft 是同步写入的，但 Qdrant 写入和读取之间可能有传播延迟
                Optional<Episode> current = fetchEpisodeWithRetry(episodeId, 3, 200);
                if (current.isEmpty()) return;
                Episode ep = current.get();

                // AI 提取变量签名 + 模板化 toolCalls（用户设计：args 具体值→$varName）
                // 失败时 fallback 原样保留，不影响主流程
                ReflectService.SignatureExtraction extraction =
                        reflectService.extractSignature(ep.userInput(), toolCalls);
                List<ToolCallLog> templatedToolCalls = extraction.templatedToolCalls();
                Map<String, String> signature = extraction.signature();

                int newSuccess = ep.successCount() + 1;
                int failure = ep.failureCount();
                double newStability = Episode.calcStability(newSuccess, failure);
                boolean canScript = newSuccess >= scriptSuccessThreshold && newStability > 0.9;

                Map<String, Object> update = new LinkedHashMap<>();
                update.put("toolCalls", templatedToolCalls);
                update.put("signature", signature);
                update.put("aiResponse", AiResponseUtils.truncate(aiResponse, 500));
                update.put("successLesson", successLesson);
                update.put("successCount", newSuccess);
                update.put("stability", newStability);
                update.put("status", Episode.EpisodeStatus.ACTIVE.name());
                update.put("canScript", canScript);
                update.put("failedStepIndex", -1);

                setPayload(episodeId, update);
                System.out.println("✅ DRAFT→ACTIVE（id=" + episodeId.substring(0, 8)
                        + "..., success=" + newSuccess + ", stability=" + String.format("%.2f", newStability)
                        + (canScript ? ", 🚀可脚本化" : "")
                        + (successLesson != null ? ", 经验: " + AiResponseUtils.truncate(successLesson, 40) : "")
                        + (!signature.isEmpty() ? ", 变量: " + signature.keySet() : "") + "）");

                // 异步触发通用步骤提取（"越用越聪明"：积累 episode 后自动发现可复用 ATOMIC）
                unitLearner.learnAsync();
            } catch (Exception e) {
                System.err.println("❌ DRAFT→ACTIVE 失败: " + e.getMessage());
            }
        });
    }

    /**
     * DRAFT → FAILED（执行失败时调用，决策4：失败也保存步骤）。
     *
     * <p>保存已执行的 toolCalls + failureLesson + failedStepIndex + status=FAILED。
     * FAILED episode 不被检索复用，但保留供后续分析（如跨任务洞见提取）。</p>
     *
     * @param episodeId      createDraft 返回的 id
     * @param toolCalls      已执行的工具调用轨迹（部分）
     * @param failureLesson  失败教训
     * @param failedStepIndex 失败步位置（-1=未知；≥0=第 N 步失败）
     */
    public void failDraft(String episodeId, List<ToolCallLog> toolCalls,
                           String failureLesson, int failedStepIndex) {
        if (episodeId == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> update = new LinkedHashMap<>();
                update.put("toolCalls", toolCalls);
                update.put("failureLesson", failureLesson);
                update.put("failedStepIndex", failedStepIndex);
                update.put("status", Episode.EpisodeStatus.FAILED.name());

                setPayload(episodeId, update);
                System.out.println("❌ DRAFT→FAILED（id=" + episodeId.substring(0, 8)
                        + "..., 已保存 " + (toolCalls != null ? toolCalls.size() : 0) + " 步"
                        + ", 失败步: " + failedStepIndex
                        + (failureLesson != null ? ", 教训: " + AiResponseUtils.truncate(failureLesson, 40) : "") + "）");
            } catch (Exception e) {
                System.err.println("❌ DRAFT→FAILED 失败: " + e.getMessage());
            }
        });
    }

    /**
     * 已存在 episode 失败处理（带 AI 归因）。
     *
     * <p>用户核心逻辑："按问题判断是否是计划问题→是计划问题→失败数+1→不是计划问题→分段继续"。</p>
     *
     * <p>归因由调用方通过 ReflectService.reflectFailure() 提前完成，结果传入：
     * <ul>
     *   <li>isPlanIssue=true → failureCount+1（达阈值 archive）+ 存 failureLesson</li>
     *   <li>isPlanIssue=false → 只存 failureLesson（不惩罚计划，分段继续）</li>
     * </ul>
     *
     * <p>异步执行，episodeId 为 null 时 no-op。</p>
     *
     * @param episodeId      要更新的 episode id
     * @param failureLesson  失败教训（可为 null）
     * @param isPlanIssue    是否计划逻辑问题（true=惩罚 / false=不惩罚）
     */
    public void recordFailure(String episodeId, String failureLesson, boolean isPlanIssue) {
        if (episodeId == null) return;

        CompletableFuture.runAsync(() -> {
            try {
                Optional<Episode> current = fetchEpisode(episodeId);
                if (current.isEmpty()) return;
                Episode ep = current.get();

                Map<String, Object> payloadUpdate = new LinkedHashMap<>();
                payloadUpdate.put("failureLesson", failureLesson);

                if (isPlanIssue) {
                    // 计划问题 → 失败数+1
                    int newFailure = ep.failureCount() + 1;
                    int success = ep.successCount();
                    double newStability = Episode.calcStability(success, newFailure);
                    boolean shouldArchive = newFailure >= failureThreshold || newStability < 0.3;

                    payloadUpdate.put("failureCount", newFailure);
                    payloadUpdate.put("stability", newStability);
                    // 失败后 canScript 降级（稳定度下降，不再脚本化）
                    if (newStability <= 0.9) payloadUpdate.put("canScript", false);
                    if (shouldArchive) {
                        payloadUpdate.put("archived", true);
                        payloadUpdate.put("status", Episode.EpisodeStatus.FAILED.name());
                    }

                    setPayload(episodeId, payloadUpdate);
                    System.out.println("⚠️ Episode 计划失败+1（id=" + episodeId.substring(0, 8)
                            + "..., failure=" + newFailure + ", stability=" + String.format("%.2f", newStability)
                            + (shouldArchive ? ", 已归档" : "")
                            + (failureLesson != null ? ", 教训: " + AiResponseUtils.truncate(failureLesson, 40) : "") + "）");
                } else {
                    // 环境问题 → 不惩罚计划，只存教训
                    setPayload(episodeId, payloadUpdate);
                    System.out.println("ℹ️ Episode 环境失败（不惩罚计划，id=" + episodeId.substring(0, 8)
                            + "..., 教训: " + (failureLesson != null ? AiResponseUtils.truncate(failureLesson, 40) : "无") + "）");
                }
            } catch (Exception e) {
                System.err.println("❌ Episode 失败更新失败: " + e.getMessage());
            }
        });
    }

    /**
     * 已存在 episode 成功计数 +1（不重置 failureCount，让 stability 自然衰退）。
     *
     * <p>异步执行，episodeId 为 null 时 no-op。</p>
     */
    public void incrementSuccess(String episodeId) {
        if (episodeId == null) return;

        CompletableFuture.runAsync(() -> {
            try {
                Optional<Episode> current = fetchEpisode(episodeId);
                if (current.isEmpty()) return;
                Episode ep = current.get();

                int newSuccess = ep.successCount() + 1;
                int failure = ep.failureCount();
                double newStability = Episode.calcStability(newSuccess, failure);
                boolean canScript = newSuccess >= scriptSuccessThreshold && newStability > 0.9;

                Map<String, Object> payloadUpdate = new LinkedHashMap<>();
                payloadUpdate.put("successCount", newSuccess);
                payloadUpdate.put("stability", newStability);
                if (canScript && !ep.canScript()) {
                    payloadUpdate.put("canScript", true);
                    System.out.println("🚀 Episode 升级为可脚本化（id=" + episodeId.substring(0, 8) + "...）");
                }

                setPayload(episodeId, payloadUpdate);
                System.out.println("✅ Episode 成功+1（id=" + episodeId.substring(0, 8)
                        + "..., success=" + newSuccess + ", stability=" + String.format("%.2f", newStability)
                        + (canScript ? ", 🚀可脚本化" : "") + "）");
            } catch (Exception e) {
                System.err.println("❌ Episode 成功计数更新失败: " + e.getMessage());
            }
        });
    }

    // ========== 内部工具方法 ==========

    @SuppressWarnings("unchecked")
    private Optional<Episode> fetchEpisode(String episodeId) {
        try {
            Map<String, Object> body = Map.of(
                    "ids", List.of(episodeId),
                    "with_payload", true,
                    "with_vector", false
            );
            Map<String, Object> response = qdrant.post()
                    .uri("/collections/" + collectionName + "/points")
                    .header(CONTENT_TYPE, APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (response == null) return Optional.empty();
            List<Map<String, Object>> points = (List<Map<String, Object>>) response.getOrDefault("result", List.of());
            if (points.isEmpty()) return Optional.empty();
            return Optional.ofNullable(deserializeEpisode(points.get(0)));
        } catch (Exception e) {
            System.err.println("❌ Qdrant 读取 episode 失败: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** 带重试的 fetch（应对 DRAFT→ACTIVE 的写入传播延迟） */
    private Optional<Episode> fetchEpisodeWithRetry(String episodeId, int maxRetries, int delayMs) {
        for (int i = 0; i < maxRetries; i++) {
            Optional<Episode> ep = fetchEpisode(episodeId);
            if (ep.isPresent()) return ep;
            if (i < maxRetries - 1) {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
            }
        }
        System.err.println("⚠️ fetchEpisode 重试 " + maxRetries + " 次后仍未找到: " + episodeId.substring(0, 8) + "...");
        return Optional.empty();
    }

    /** upsert 单个 point（含 vector + payload） */
    private void upsertPoint(String episodeId, List<Float> vector, Map<String, Object> payload) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", episodeId);
        point.put("vector", vector);
        point.put("payload", payload);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", List.of(point));

        qdrant.put()
                .uri("/collections/" + collectionName + "/points?wait=true")
                .header(CONTENT_TYPE, APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    private void setPayload(String episodeId, Map<String, Object> payloadUpdate) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("payload", payloadUpdate);
        body.put("points", List.of(episodeId));
        qdrant.post()
                .uri("/collections/" + collectionName + "/points/payload?wait=true")
                .header(CONTENT_TYPE, APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    /**
     * 把 Qdrant 返回的 point 反序列化为 Episode（映射全部字段含 lesson）。
     */
    @SuppressWarnings("unchecked")
    private Episode deserializeEpisode(Map<String, Object> point) {
        return Episode.fromQdrantPoint(point, objectMapper);
    }

    /**
     * 从失败计划中提取可复用的步骤链，保存为 ATOMIC 通用模板。
     *
     * <p>例如"打开番茄网站"失败（网址错了），但"打开浏览器→输入网址"步骤链可复用。
     * 每条第≥2步的连续成功链存为独立 ATOMIC episode，积累≥3次引用后自动变通用。</p>
     *
     * @param userInput   原始用户输入（用于生成步骤描述）
     * @param toolCalls   完整的执行轨迹
     * @param chains      可复用步骤索引链，如 [[0,1], [3]]
     * @param parentDraftId 原始 DRAFT episode ID（用于关联）
     */
    public void saveSalvageableAtomicChains(String userInput, List<ToolCallLog> toolCalls,
                                             List<List<Integer>> chains, String parentDraftId) {
        if (chains == null || chains.isEmpty()) return;

        for (List<Integer> chain : chains) {
            if (chain.size() < 1) continue;
            try {
                // 提取这一链的步骤
                List<ToolCallLog> chainSteps = new ArrayList<>();
                StringBuilder desc = new StringBuilder();
                for (int i = 0; i < chain.size(); i++) {
                    int idx = chain.get(i);
                    if (idx < 0 || idx >= toolCalls.size()) continue;
                    ToolCallLog step = toolCalls.get(idx);
                    if (!step.success()) continue; // 只收成功步骤
                    chainSteps.add(step);
                    if (i > 0) desc.append(" → ");
                    desc.append(step.toolName());
                }
                if (chainSteps.isEmpty()) continue;

                // 单步跳过（没有组合价值）
                if (chainSteps.size() < 2) continue;

                String stepDesc = desc.toString();
                String episodeId = UUID.randomUUID().toString();

                // 用步骤描述生成向量
                List<Float> vector = embeddingService.embed(userInput + " → " + stepDesc);

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("id", episodeId);
                payload.put("userInput", stepDesc);
                payload.put("selectedToolNames", List.of());
                payload.put("missingDescriptions", List.of());
                payload.put("toolCalls", chainSteps);
                payload.put("aiResponse", null);
                payload.put("successLesson", "自动从失败任务中提取的可复用步骤链");
                payload.put("failureLesson", null);
                payload.put("signature", Map.of());
                payload.put("unitType", Episode.UnitType.ATOMIC.name());
                payload.put("isGeneric", false);
                payload.put("parentIds", parentDraftId != null ? List.of(parentDraftId) : List.of());
                payload.put("successCount", 1);
                payload.put("failureCount", 0);
                payload.put("archived", false);
                payload.put("timestamp", System.currentTimeMillis());
                payload.put("stability", Episode.calcStability(1, 0));
                payload.put("status", Episode.EpisodeStatus.ACTIVE.name());
                payload.put("canScript", false);
                payload.put("failedStepIndex", -1);

                upsertPoint(episodeId, vector, payload);
                System.out.println("🧩 已提取可复用步骤链: " + stepDesc + " (id=" + episodeId.substring(0, 8) + "...)");
            } catch (Exception e) {
                System.err.println("⚠️ 提取步骤链失败: " + e.getMessage());
            }
        }
    }

    /**
     * 获取近期成功 Episode 摘要列表（供定时规则归纳使用）。
     *
     * <p>scroll 出 status=ACTIVE 且 archived=false 且 stability>=0.5 的最近 N 条，返回摘要信息。</p>
     */
    @SuppressWarnings("unchecked")
    public List<String> getRecentSuccessfulEpisodeSummaries(int limit) {
        try {
            Map<String, Object> filter = Map.of(MUST, List.of(
                    Map.of(KEY, STATUS, MATCH, Map.of(VALUE, "ACTIVE")),
                    Map.of(KEY, ARCHIVED, MATCH, Map.of(VALUE, false)),
                    Map.of(KEY, STABILITY, RANGE, Map.of(GTE, 0.5))
            ));
            Map<String, Object> scrollBody = new LinkedHashMap<>();
            scrollBody.put(FILTER, filter);
            scrollBody.put(WITH_PAYLOAD, true);
            scrollBody.put(WITH_VECTOR, false);
            scrollBody.put(LIMIT, limit);

            Map<String, Object> response = qdrant.post()
                    .uri("/collections/" + collectionName + "/points/scroll")
                    .header(CONTENT_TYPE, APPLICATION_JSON)
                    .bodyValue(scrollBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) return List.of();
            Map<String, Object> result = (Map<String, Object>) response.get(RESULT);
            if (result == null) return List.of();
            List<Map<String, Object>> points = (List<Map<String, Object>>) result.getOrDefault(POINTS, List.of());

            List<String> summaries = new ArrayList<>();
            for (Map<String, Object> p : points) {
                Map<String, Object> payload = (Map<String, Object>) p.get("payload");
                if (payload == null) continue;
                String userInput = (String) payload.getOrDefault("userInput", "");
                String successLesson = (String) payload.getOrDefault("successLesson", "");
                int successCount = ((Number) payload.getOrDefault("successCount", 0)).intValue();
                summaries.add(String.format("任务: %s | 成功经验: %s | 成功次数: %d",
                        userInput, successLesson != null ? successLesson : "无", successCount));
            }
            return summaries;
        } catch (Exception e) {
            System.err.println("⚠️ 获取成功 Episode 摘要失败: " + e.getMessage());
            return List.of();
        }
    }

    // ========== 失败经验持久化 ==========

    /**
     * 持久化 FailurePattern 到 Qdrant。
     *
     * <p>用 pattern.type + pattern.description 拼接作为向量化的输入文本，
     * 异步写入 failure-patterns collection，Qdrant 故障吞异常不传染。</p>
     */
    public void saveFailurePattern(FailureExperienceHandler.FailurePattern pattern, List<String> toolNames) {
        if (pattern == null) return;
        CompletableFuture.runAsync(() -> {
            try {
                String text = pattern.type() + " " + pattern.description();
                List<Float> vector = embeddingService.embed(text);
                String pointId = UUID.randomUUID().toString();

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("id", pointId);
                payload.put("type", pattern.type());
                payload.put("description", pattern.description());
                payload.put("mitigation", pattern.mitigation());
                payload.put("count", pattern.count());
                payload.put("detectedAt", pattern.detectedAt());
                payload.put("toolNames", toolNames != null ? toolNames : List.of());

                upsertPointToCollection(failurePatternsCollection, pointId, vector, payload);
                System.out.println("💾 失败模式已持久化: " + pattern.type() + " (id=" + pointId.substring(0, 8) + "...)");

                // 同时写入 Neo4j 知识图谱
                try {
                    FailurePatternNode node = new FailurePatternNode(
                            pattern.type(), pattern.description(), pattern.mitigation(),
                            pattern.count(), pattern.detectedAt());
                    failurePatternRepo.save(node);
                    System.out.println("🧠 失败模式已写入 Neo4j: " + pattern.type());
                } catch (Exception neoEx) {
                    System.err.println("⚠️ FailurePattern Neo4j 写入失败: " + neoEx.getMessage());
                }
            } catch (Exception e) {
                System.err.println("❌ FailurePattern 持久化失败: " + e.getMessage());
            }
        });
    }

    /**
     * 向量检索失败模式。
     *
     * @param query 查询文本（如 AI 要执行的用户指令）
     * @param topK  返回条数
     * @return 匹配的 FailurePattern 列表（含 Qdrant score）；出错时返回空列表
     */
    @SuppressWarnings("unchecked")
    public List<FailureSearchResult> searchFailurePatterns(String query, int topK) {
        try {
            List<Float> queryVector = embeddingService.embed(query);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put(VECTOR, queryVector);
            body.put(LIMIT, topK);
            body.put(WITH_PAYLOAD, true);
            body.put(SCORE_THRESHOLD, 0.55f);

            Map<String, Object> response = qdrant.post()
                    .uri("/collections/" + failurePatternsCollection + "/points/search")
                    .header(CONTENT_TYPE, APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) return List.of();

            List<Map<String, Object>> points = (List<Map<String, Object>>) response.getOrDefault("result", List.of());
            if (points.isEmpty()) return List.of();

            List<FailureSearchResult> results = new ArrayList<>();
            for (Map<String, Object> point : points) {
                double score = ((Number) point.getOrDefault(SCORE, 0.0)).doubleValue();
                Map<String, Object> payload = (Map<String, Object>) point.get("payload");
                if (payload == null) continue;

                results.add(new FailureSearchResult(
                        (String) payload.get("type"),
                        (String) payload.get("description"),
                        (String) payload.get("mitigation"),
                        ((Number) payload.getOrDefault("count", 0)).intValue(),
                        ((Number) payload.getOrDefault("detectedAt", 0L)).longValue(),
                        (List<String>) payload.getOrDefault("toolNames", List.of()),
                        score
                ));
            }
            return results;
        } catch (Exception e) {
            System.err.println("❌ FailurePattern 搜索失败: " + e.getMessage());
            return List.of();
        }
    }

    /** upsert point 到指定 collection（不耦合 this.collectionName） */
    private void upsertPointToCollection(String collection, String pointId,
                                          List<Float> vector, Map<String, Object> payload) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", pointId);
        point.put("vector", vector);
        point.put("payload", payload);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("points", List.of(point));

        qdrant.put()
                .uri("/collections/" + collection + "/points?wait=true")
                .header(CONTENT_TYPE, APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    /**
     * 失败经验检索结果。
     */
    public record FailureSearchResult(
            String type,
            String description,
            String mitigation,
            int count,
            long detectedAt,
            List<String> toolNames,
            double score
    ) {}
}
