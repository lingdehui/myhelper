package com.example.desktopbrain.memory.vector.episode;

import com.example.desktopbrain.memory.vector.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.concurrent.CompletableFuture;

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
    private final ObjectMapper objectMapper;

    @Value("${qdrant.episodes-collection:episodes}")
    private String collectionName;

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
                                UnitLearner unitLearner) {
        this.qdrant = qdrantWebClient;
        this.embeddingService = embeddingService;
        this.reflectService = reflectService;
        this.unitLearner = unitLearner;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 启动时初始化 episodes collection。
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
                        .header("Content-Type", "application/json")
                        .bodyValue(body)
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                System.out.println("📦 Qdrant 集合 '" + collectionName + "' 已创建（向量维度: " + vectorSize + "）");
            } else {
                System.out.println("📦 Qdrant 集合 '" + collectionName + "' 已存在");
            }
        } catch (Exception e) {
            System.err.println("⚠️ Episode collection 初始化失败（episode 系统将降级）: " + e.getMessage());
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

            Map<String, Object> filter = Map.of("must", List.of(
                    Map.of("key", "archived", "equals", false),
                    Map.of("key", "stability", "range", Map.of("gte", stabilityThreshold)),
                    Map.of("key", "status", "match", Map.of("value", "ACTIVE"))
            ));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", queryVector);
            body.put("limit", topK);
            body.put("with_payload", true);
            body.put("score_threshold", (float) similarityThreshold);
            body.put("filter", filter);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = qdrant.post()
                    .uri("/collections/" + collectionName + "/points/search")
                    .header("Content-Type", "application/json")
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
     * @return 新生成的 episodeId
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
        } catch (Exception e) {
            System.err.println("❌ DRAFT Episode 创建失败: " + e.getMessage());
        }
        return episodeId;
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
                Optional<Episode> current = fetchEpisode(episodeId);
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
                double newStability = (double) newSuccess / (newSuccess + failure);
                boolean canScript = newSuccess >= scriptSuccessThreshold && newStability > 0.9;

                Map<String, Object> update = new LinkedHashMap<>();
                update.put("toolCalls", templatedToolCalls);
                update.put("signature", signature);
                update.put("aiResponse", truncate(aiResponse, 500));
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
                        + (successLesson != null ? ", 经验: " + truncate(successLesson, 40) : "")
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
                        + (failureLesson != null ? ", 教训: " + truncate(failureLesson, 40) : "") + "）");
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
                    double newStability = (double) success / (success + newFailure);
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
                            + (failureLesson != null ? ", 教训: " + truncate(failureLesson, 40) : "") + "）");
                } else {
                    // 环境问题 → 不惩罚计划，只存教训
                    setPayload(episodeId, payloadUpdate);
                    System.out.println("ℹ️ Episode 环境失败（不惩罚计划，id=" + episodeId.substring(0, 8)
                            + "..., 教训: " + (failureLesson != null ? truncate(failureLesson, 40) : "无") + "）");
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
                double newStability = (double) newSuccess / (newSuccess + failure);
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
                    .header("Content-Type", "application/json")
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
                .header("Content-Type", "application/json")
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
                .header("Content-Type", "application/json")
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
        try {
            Map<String, Object> payload = (Map<String, Object>) point.get("payload");
            if (payload == null) return null;
            String id = String.valueOf(point.get("id"));
            payload.put("id", id);

            // 确保新增字段有默认值（兼容旧数据）
            payload.putIfAbsent("successLesson", null);
            payload.putIfAbsent("failureLesson", null);
            payload.putIfAbsent("signature", Map.of());
            payload.putIfAbsent("unitType", Episode.UnitType.COMPOSITE.name());
            payload.putIfAbsent("isGeneric", false);
            payload.putIfAbsent("parentIds", List.of());
            payload.putIfAbsent("status", Episode.EpisodeStatus.ACTIVE.name());
            payload.putIfAbsent("canScript", false);
            payload.putIfAbsent("failedStepIndex", -1);

            return objectMapper.convertValue(payload, Episode.class);
        } catch (Exception e) {
            System.err.println("❌ Episode 反序列化失败: " + e.getMessage());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        String oneLine = s.replace("\n", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "...[truncated]" : oneLine;
    }
}
