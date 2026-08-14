package com.example.myhelper.memory.vector.episode;

import com.example.myhelper.common.AiResponseUtils;
import com.example.myhelper.config.SystemEnvironmentService;
import com.example.myhelper.memory.vector.EmbeddingService;
import com.example.myhelper.memory.vector.QdrantDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 通用步骤提取器（"越用越聪明"的关键——自动发现可复用的 ATOMIC 子步骤）。
 *
 * <h3>用户核心设计</h3>
 * <blockquote>
 * "如果说某一步很通用就可以提出来...步骤的概念其实就是小计划...取最稳定的为大"
 * </blockquote>
 *
 * <p>当系统积累足够多的 COMPOSITE episode（完整执行计划）后，UnitLearner 自动扫描
 * 发现其中重复出现的公共工具调用子序列，将其提取为 ATOMIC 通用步骤。被≥3个
 * COMPOSITE 引用的子序列自动标记 isGeneric=true，成为可被 PlanMatcher 检索复用的
 * 独立构建块。</p>
 *
 * <h3>提取算法（异步后台执行）</h3>
 * <ol>
 *   <li>从 Qdrant 获取所有 ACTIVE COMPOSITE episode</li>
 *   <li>对每组 episode 找最长公共工具名子序列（LCS，长度≥2）</li>
 *   <li>子序列出现在≥3个不同 episode → 提取为 ATOMIC</li>
 *   <li>去重：已有相同工具序列的 ATOMIC 则跳过</li>
 *   <li>新 ATOMIC：embed(步骤描述) → 写入 Qdrant episodes collection</li>
 * </ol>
 *
 * <h3>触发时机</h3>
 * <ul>
 *   <li>每次 episode activateDraft 成功后异步触发（渐进式积累）</li>
 *   <li>避免启动时全量扫描（首次数据少，浪费资源）</li>
 * </ul>
 */
@Service
public class UnitLearner {

    private static final Logger log = LoggerFactory.getLogger(UnitLearner.class);

    private final WebClient qdrant;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final SystemEnvironmentService envService;

    @Value("${qdrant.episodes-collection:episodes}")
    private String baseCollectionName;
    private String collectionName;

    /** 提取阈值：至少被 N 个不同 COMPOSITE 引用才标记为通用 */
    @Value("${qdrant.episode.generic-threshold:3}")
    private int genericThreshold;

    /** 最小子序列长度（少于2步不值得提取） */
    private static final int MIN_SUBSEQ_LEN = 2;

    public UnitLearner(WebClient qdrantWebClient, EmbeddingService embeddingService,
                        SystemEnvironmentService envService) {
        this.qdrant = qdrantWebClient;
        this.embeddingService = embeddingService;
        this.envService = envService;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        this.collectionName = envService.collectionName(baseCollectionName);
        log.info("📦 UnitLearner 集合: {}", collectionName);
    }

    /**
     * 异步触发通用步骤提取（DRAFT→ACTIVE 成功后调用）。
     *
     * <p>异步执行，失败不抛异常，不影响主流程。</p>
     */
    public void learnAsync() {
        CompletableFuture.runAsync(this::extractCommonSteps);
    }

    /**
     * 核心提取逻辑：扫描所有 ACTIVE COMPOSITE → 找公共子序列 → 创建 ATOMIC。
     */
    void extractCommonSteps() {
        try {
            // 1. 获取所有 ACTIVE COMPOSITE episode
            List<Episode> composites = fetchActiveComposites();
            if (composites.size() < genericThreshold) return;

            // 2. 收集已有的 ATOMIC 工具序列（用于去重）
            Set<String> existingAtomics = fetchExistingAtomicSequences();

            // 3. 构建工具名子序列频率统计
            //    key = toolName1|toolName2|... → set of episodeIds
            Map<String, Set<String>> subseqFreq = new LinkedHashMap<>();
            for (Episode ep : composites) {
                List<ToolCallLog> tc = ep.toolCalls();
                if (tc == null || tc.size() < MIN_SUBSEQ_LEN) continue;
                List<String> names = tc.stream().map(ToolCallLog::toolName).toList();

                // 枚举所有长度≥MIN_SUBSEQ_LEN 的连续子序列
                for (int len = MIN_SUBSEQ_LEN; len <= names.size(); len++) {
                    for (int start = 0; start + len <= names.size(); start++) {
                        String key = String.join("|", names.subList(start, start + len));
                        subseqFreq.computeIfAbsent(key, k -> new LinkedHashSet<>())
                                .add(ep.id());
                    }
                }
            }

            // 4. 过滤：出现≥threshold 次 且 不是已有 ATOMIC，并验证多样性
            int created = 0;
            for (Map.Entry<String, Set<String>> entry : subseqFreq.entrySet()) {
                if (entry.getValue().size() < genericThreshold) continue;
                if (existingAtomics.contains(entry.getKey())) continue;

                // 取第一个 episode 的对应子序列的 toolCalls 作为模板
                Episode refComposites = composites.stream()
                        .filter(e -> entry.getValue().contains(e.id()))
                        .findFirst().orElse(null);
                if (refComposites == null) continue;

                List<ToolCallLog> subCalls = extractSubCalls(refComposites.toolCalls(), entry.getKey());
                if (subCalls.isEmpty()) continue;

                // 🆕 闭环验证：检查父 episode 的多样性
                // 如果所有父 episode 都是同样的任务（相同 userInput），则子序列是重复任务模式，非真正通用步骤
                ParentDiversity div = checkParentDiversity(composites, entry.getValue());
                double initialStability;
                if (div.isDiverse) {
                    // 不同任务中反复出现 → 高置信度通用步骤
                    initialStability = 0.7;
                    log.info("  🧩 高置信度 ATOMIC: {}（{} 种不同任务）", entry.getKey(), div.diverseCount);
                } else {
                    // 相同任务重复出现 → 可能只是任务模式，低置信度
                    initialStability = 0.3;
                    log.info("  🧩 低置信度 ATOMIC: {}（仅 1 种任务，{} 次重复）→ stability={}，需实际验证", entry.getKey(), div.totalCount, initialStability);
                }

                // 创建 ATOMIC episode
                createAtomicEpisode(entry.getKey(), subCalls,
                        new ArrayList<>(entry.getValue()), initialStability);
                created++;
            }

            if (created > 0) {
                log.info("🧩 UnitLearner 提取 {} 个通用 ATOMIC 步骤", created);
            }

        } catch (Exception e) {
            log.error("⚠️ UnitLearner 提取失败: {}", e.getMessage());
        }
    }

    // ========== Qdrant 查询 ==========

    private List<Episode> fetchActiveComposites() {
        List<Episode> result = new ArrayList<>();
        try {
            String offset = null;
            while (true) {
                Map<String, Object> scrollBody = new LinkedHashMap<>();
                scrollBody.put("limit", 100);
                scrollBody.put("with_payload", true);
                scrollBody.put("with_vector", false);
                Map<String, Object> filter = Map.of("must", List.of(
                        Map.of("key", "status", "match", Map.of("value", "ACTIVE")),
                        Map.of("key", "unitType", "match", Map.of("value", "COMPOSITE")),
                        Map.of("key", "archived", "match", Map.of("value", false))
                ));
                scrollBody.put("filter", filter);
                if (offset != null) scrollBody.put("offset", offset);

                QdrantDtos.ScrollResponse response = qdrant.post()
                        .uri("/collections/" + collectionName + "/points/scroll")
                        .header("Content-Type", "application/json")
                        .bodyValue(scrollBody)
                        .retrieve()
                        .bodyToMono(QdrantDtos.ScrollResponse.class)
                        .block();

                if (response == null || response.result() == null) break;

                List<QdrantDtos.ScoredPoint> points = response.result().points();
                if (points == null || points.isEmpty()) break;

                for (QdrantDtos.ScoredPoint point : points) {
                    Episode ep = deserializeEpisode(point);
                    if (ep != null && ep.toolCalls() != null && !ep.toolCalls().isEmpty()) {
                        result.add(ep);
                    }
                }

                String nextOffset = response.result().next_page_offset();
                offset = (nextOffset != null && !nextOffset.isEmpty()) ? nextOffset : null;
                if (offset == null) break;
            }
        } catch (Exception e) {
            log.error("⚠️ UnitLearner 查询 ACTIVE episode 失败: {}", e.getMessage());
        }
        return result;
    }

    private Set<String> fetchExistingAtomicSequences() {
        Set<String> sequences = new HashSet<>();
        try {
            List<Episode> atomics = fetchAtomics();
            for (Episode ep : atomics) {
                if (ep.toolCalls() != null) {
                    List<String> names = ep.toolCalls().stream()
                            .map(ToolCallLog::toolName).toList();
                    sequences.add(String.join("|", names));
                }
            }
        } catch (Exception e) {
            log.error("⚠️ UnitLearner 查询已有 ATOMIC 失败: {}", e.getMessage());
        }
        return sequences;
    }

    private List<Episode> fetchAtomics() {
        List<Episode> result = new ArrayList<>();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("limit", 200);
            body.put("with_payload", true);
            body.put("with_vector", false);
            Map<String, Object> filter = Map.of("must", List.of(
                    Map.of("key", "unitType", "match", Map.of("value", "ATOMIC")),
                    Map.of("key", "archived", "match", Map.of("value", false))
            ));
            body.put("filter", filter);

            QdrantDtos.ScrollResponse response = qdrant.post()
                    .uri("/collections/" + collectionName + "/points/scroll")
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(QdrantDtos.ScrollResponse.class)
                    .block();

            if (response != null && response.result() != null) {
                List<QdrantDtos.ScoredPoint> points = response.result().points();
                if (points != null) {
                    for (QdrantDtos.ScoredPoint point : points) {
                        Episode ep = deserializeEpisode(point);
                        if (ep != null) result.add(ep);
                    }
                }
            }
        } catch (Exception e) {
            log.error("⚠️ UnitLearner 查询 ATOMIC 失败: {}", e.getMessage());
        }
        return result;
    }

    // ========== ATOMIC 创建 ==========

    /**
     * 父 episode 多样性检查结果。
     *
     * @param isDiverse    父 episode 是否包含多种不同任务（true=通用步骤，false=任务重复）
     * @param totalCount   父 episode 总数
     * @param diverseCount 不同任务种类数（基于 normalizeKey 去重）
     */
    private record ParentDiversity(boolean isDiverse, int totalCount, int diverseCount) {}

    /**
     * 检查引用了同一子序列的父 episode 的任务多样性。
     *
     * <p>真正的通用步骤：在多种不同任务中反复出现（如 "找联系人→发消息" 出现在
     * "给张三发微信"、"回复李四消息"、"群里通知开会" 中）。</p>
     *
     * <p>伪通用步骤（任务重复）：同一个任务重复执行导致的（如 "给张三发微信"
     * 执行了 5 次），这种子序列是任务模式，不是通用步骤。</p>
     */
    private static ParentDiversity checkParentDiversity(List<Episode> allComposites,
                                                         Set<String> parentIds) {
        // 收集父 episode 的归一化 userInput（去标点/空格/数字）
        Set<String> diverseInputs = new HashSet<>();
        int count = 0;
        for (Episode ep : allComposites) {
            if (parentIds.contains(ep.id())) {
                String normalized = AiResponseUtils.normalizeKey(ep.userInput());
                if (normalized != null && !normalized.isEmpty()) {
                    diverseInputs.add(normalized);
                }
                count++;
            }
        }
        // 不同任务种类 ≥ 2 才认为是真正跨任务的通用步骤
        return new ParentDiversity(diverseInputs.size() >= 2, count, diverseInputs.size());
    }

    /**
     * 创建一个 ATOMIC 通用步骤 episode。
     *
     * @param toolKey   工具名序列（"toolA|toolB|..."），用于生成描述
     * @param subCalls  对应的 toolCalls 子序列
     * @param parentIds 引用此步骤的 COMPOSITE episode id 列表
     * @param initialStability 初始稳定度（基于父 episode 多样性：多样性高=0.7，单任务重复=0.3）
     */
    private void createAtomicEpisode(String toolKey, List<ToolCallLog> subCalls,
                                      List<String> parentIds, double initialStability) {
        try {
            String episodeId = UUID.randomUUID().toString();
            long timestamp = System.currentTimeMillis();

            // 生成描述
            List<String> toolNames = List.of(toolKey.split("\\|"));
            String description = String.join("→", toolNames);

            // embedding：用工具名序列作为文本
            List<Float> vector = embeddingService.embed(description);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", episodeId);
            payload.put("userInput", description); // userInput 存描述
            payload.put("selectedToolNames", toolNames);
            payload.put("missingDescriptions", List.of());
            payload.put("toolCalls", subCalls);
            payload.put("aiResponse", null);
            payload.put("successLesson", "通用步骤: " + description);
            payload.put("failureLesson", null);
            payload.put("signature", Map.of());
            payload.put("unitType", Episode.UnitType.ATOMIC.name());
            payload.put("isGeneric", initialStability >= 0.6); // 只有高置信度才标记通用
            payload.put("parentIds", parentIds);
            payload.put("successCount", 0);
            payload.put("failureCount", 0);
            payload.put("archived", false);
            payload.put("timestamp", timestamp);
            payload.put("stability", initialStability); // 🆕 动态初始稳定度
            payload.put("status", Episode.EpisodeStatus.ACTIVE.name());
            payload.put("canScript", false); // ATOMIC 不单独脚本化
            payload.put("failedStepIndex", -1);

            upsertPoint(episodeId, vector, payload);
            log.info("🧩 新增 ATOMIC: {} (被 {} 个计划引用)", description, parentIds.size());
        } catch (Exception e) {
            log.error("⚠️ ATOMIC 创建失败: {}", e.getMessage());
        }
    }

    private void upsertPoint(String id, List<Float> vector, Map<String, Object> payload) {
        try {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", id);
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
            log.error("❌ ATOMIC upsert 失败: {}", e.getMessage());
        }
    }

    // ========== 工具方法 ==========

    /** 从 episode 的 toolCalls 中按工具名序列提取子 toolCalls */
    private static List<ToolCallLog> extractSubCalls(List<ToolCallLog> fullTc, String toolKey) {
        List<String> target = List.of(toolKey.split("\\|"));
        List<String> names = fullTc.stream().map(ToolCallLog::toolName).toList();

        // 找第一个匹配位置
        for (int start = 0; start + target.size() <= names.size(); start++) {
            boolean match = true;
            for (int i = 0; i < target.size(); i++) {
                if (!target.get(i).equals(names.get(start + i))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return fullTc.subList(start, start + target.size());
            }
        }
        return List.of();
    }

    private Episode deserializeEpisode(QdrantDtos.ScoredPoint point) {
        return Episode.fromQdrantPoint(point, objectMapper);
    }
}
