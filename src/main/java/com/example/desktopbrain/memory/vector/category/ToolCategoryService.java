package com.example.desktopbrain.memory.vector.category;

import com.example.desktopbrain.common.AiResponseUtils;
import com.example.desktopbrain.common.PromptLoader;
import com.example.desktopbrain.config.SystemEnvironmentService;
import com.example.desktopbrain.memory.vector.EmbeddingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import com.example.desktopbrain.config.ModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

/**
 * 工具分类服务：AI 动态分类 + Qdrant 向量存储。
 *
 * <h3>代替旧版写死 10 类 Category</h3>
 * <p>旧版：{@code ToolPlanner.CATEGORIES} 是 {@code static final} 硬编码列表，
 * 10 个分类、54 个工具全手写映射，新增工具（HA、生成的 tool）永远不在分类里。</p>
 *
 * <p>新版：启动时 AI 自动扫描所有 ToolCallback 分组归类，存入 Qdrant，
 * 分类数量随工具数量动态增长。AI 规划时 embed 用户输入 → 向量搜索最相关的分类。</p>
 *
 * <h3>initCollection → syncCategories → search</h3>
 * <ol>
 *   <li>启动时初始化 {@code tool-categories} collection</li>
 *   <li>AI 扫描所有工具生成分类（每次工具变化时重新同步）</li>
 *   <li>规划阶段：embed(userInput) → 向量搜索 → 返回匹配分类及工具列表</li>
 * </ol>
 *
 * <h3>交叉分类</h3>
 * AI 可将同一工具归入多个分类（如 "ocrScreen" 属于"屏幕OCR"也属于"文字识别"）。
 * 搜索时工具列表自动去重。
 */
@Service
public class ToolCategoryService {

    private static final Logger log = LoggerFactory.getLogger(ToolCategoryService.class);

    private final WebClient qdrant;
    private final ModelRouter modelRouter;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final PromptLoader promptLoader;
    private final SystemEnvironmentService envService;

    private static final String BASE_collectionName = "tool-categories";
    private String collectionName;

    @Value("${qdrant.vector-size:768}")
    private int vectorSize;

    /** 分类同步版本号（时间戳），每次同步更新 */
    private volatile long lastSyncVersion = 0;

    public ToolCategoryService(WebClient qdrantWebClient,
                                ModelRouter modelRouter,
                                EmbeddingService embeddingService,
                                PromptLoader promptLoader,
                                SystemEnvironmentService envService) {
        this.qdrant = qdrantWebClient;
        this.modelRouter = modelRouter;
        this.embeddingService = embeddingService;
        this.envService = envService;
        this.objectMapper = new ObjectMapper();
        this.promptLoader = promptLoader;
    }

    /**
     * 启动时初始化 tool-categories collection（@PostConstruct 入口）。
     */
    @PostConstruct
    public void initCollection() {
        this.collectionName = envService.collectionName(BASE_collectionName);
        log.info("📦 ToolCategory 集合: {}", collectionName);
        ensureCollection();
    }

    /** 确保 tool-categories 集合存在，不存在则创建。幂等，可重复调用。 */
    private void ensureCollection() {
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
                log.info("📦 Qdrant 集合 '" + collectionName + "' 已创建（向量维度: " + vectorSize + "）");
            } else {
                log.info("📦 Qdrant 集合 '" + collectionName + "' 已存在");
            }
        } catch (Exception e) {
            log.error("⚠️ tool-categories 初始化失败（工具分类将降级）: " + e.getMessage());
        }
    }

    /**
     * 同步工具分类到 Qdrant（启动时 / 工具变化时调用）。
     *
     * <p>收集所有 ToolCallback 的 name + description → AI 分组归类 → 逐条写入 Qdrant。
     * 每个分类作为独立 point，向量 = 分类描述的 embedding。支持交叉分类（同一工具可出现在多个分类中）。</p>
     *
     * @param allTools 所有可用工具（MCP + 本地 + 生成的）
     * @param force true=强制刷新（新工具生成时），false=有缓存则跳过（启动时）
     * @return 分类数量（失败返回 0，跳过返回 -1）
     */
    public int syncCategories(ToolCallback[] allTools, boolean force) {
        if (allTools == null || allTools.length == 0) return 0;

        // 兜底确保集合已创建（@PostConstruct 可能因 Qdrant 未就绪而静默失败）
        ensureCollection();

        // 非强制模式：已有分类缓存则跳过
        if (!force && hasCategories()) {
            log.info("📦 工具分类已缓存，跳过 AI 同步");
            return -1;
        }

        try {
            // 构建工具列表字符串供 AI 分类
            StringBuilder toolList = new StringBuilder();
            for (ToolCallback tc : allTools) {
                toolList.append("- ").append(tc.getToolDefinition().name());
                String desc = tc.getToolDefinition().description();
                if (desc != null && !desc.isBlank()) {
                    toolList.append(": ").append(AiResponseUtils.truncateNotNull(desc, 80));
                }
                toolList.append("\n");
            }

            String prompt = promptLoader.getToolCategorySync().formatted(toolList);

            log.info("{}🔄 工具分类同步: {} 个工具 → AI 分组...{}", "\u001b[36m", allTools.length, "\u001b[0m");
            String response = modelRouter.normal().prompt().user(prompt).call().content();
            if (response == null || response.isBlank()) return 0;

            String json = AiResponseUtils.stripMarkdownCodeBlock(response);

            List<Map<String, Object>> categories =
                    objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});

            // 分类过多则精简合并
            if (categories.size() > 20) {
                log.info("📦 分类数量 {} > 20，触发精简合并...", categories.size());
                categories = consolidateCategories(categories, toolList.toString());
            }

            long version = System.currentTimeMillis();

            // 先构建所有新 points，一次性写入，成功后再删旧版本
            // （防止先删后写时中途失败导致分类为空）
            Map<String, Object> upsertBody = new LinkedHashMap<>();
            List<Map<String, Object>> points = new ArrayList<>();
            int count = 0;

            for (Map<String, Object> cat : categories) {
                String name = String.valueOf(cat.get("name"));
                String desc = String.valueOf(cat.getOrDefault("desc", name));

                @SuppressWarnings("unchecked")
                List<String> tools = (List<String>) cat.getOrDefault("tools", List.of());

                // 过滤：只保留实际存在的工具名
                Set<String> existingTools = collectExistingToolNames(allTools);
                List<String> validTools = new ArrayList<>();
                for (String t : tools) {
                    if (existingTools.contains(t)) validTools.add(t);
                }
                if (validTools.isEmpty()) continue;

                // 生成 embedding
                String categoryText = name + ": " + desc;
                List<Float> vector = embeddingService.embed(categoryText);

                String categoryId = UUID.randomUUID().toString();
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("id", categoryId);
                payload.put("name", name);
                payload.put("description", desc);
                payload.put("tools", validTools);
                payload.put("toolCount", validTools.size());
                payload.put("version", version);

                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", categoryId);
                point.put("vector", vector);
                point.put("payload", payload);
                points.add(point);
                count++;
            }

            if (points.isEmpty()) return 0;

            // 批量 upsert 所有新分类
            upsertBody.put("points", points);
            upsertBatch(upsertBody);

            // 新分类全部写成功后，再删除旧版本
            deleteAllCategoryPoints();

            this.lastSyncVersion = version;
            log.info("✅ 工具分类同步完成: " + count + " 类, "
                    + allTools.length + " 个工具");
            return count;

        } catch (Exception e) {
            log.error("❌ 工具分类同步失败: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 向量搜索最匹配的分类（AI 规划阶段调用）。
     *
     * <p>embed(userInput) → 搜索 tool-categories → 返回 top-K 分类及工具列表（去重）。</p>
     *
     * @param userInput 用户自然语言输入
     * @param topK      返回几个分类
     * @param minScore  最小相似度阈值（0-1）
     * @return 匹配结果列表；Qdrant 故障时返回空列表（调用方降级到硬编码兜底）
     */
    public List<CategoryMatch> searchCategories(String userInput, int topK, double minScore) {
        try {
            List<Float> queryVector = embeddingService.embed(userInput);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", queryVector);
            body.put("limit", topK);
            body.put("with_payload", true);
            body.put("score_threshold", (float) minScore);

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
            List<Map<String, Object>> points =
                    (List<Map<String, Object>>) response.getOrDefault("result", List.of());
            if (points.isEmpty()) return List.of();

            List<CategoryMatch> results = new ArrayList<>();
            Set<String> dedupTools = new LinkedHashSet<>();

            for (Map<String, Object> point : points) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) point.get("payload");
                if (payload == null) continue;

                String name = String.valueOf(payload.get("name"));
                String desc = String.valueOf(payload.getOrDefault("description", name));
                @SuppressWarnings("unchecked")
                List<String> tools = (List<String>) payload.getOrDefault("tools", List.of());
                double score = ((Number) point.getOrDefault("score", 0.0)).doubleValue();

                results.add(new CategoryMatch(name, desc, tools, score));
                dedupTools.addAll(tools);
            }

            results.sort((a, b) -> Double.compare(b.score(), a.score()));
            int totalUnique = dedupTools.size();
            log.info("🔍 向量分类匹配: " + results.size() + " 类 → "
                    + totalUnique + " 个工具");
            return results;

        } catch (Exception e) {
            log.error("❌ 工具分类搜索失败: " + e.getMessage());
            return List.of();
        }
    }

    // ========== 内部工具方法 ==========

    /**
     * 删除所有分类 points（同步前清理旧数据）。
     * 使用 Qdrant delete 按 filter 删除所有匹配 points。
     */
    private void deleteAllCategoryPoints() {
        try {
            Map<String, Object> deleteBody = new LinkedHashMap<>();
            deleteBody.put("filter", Map.of());
            qdrant.post()
                    .uri("/collections/" + collectionName + "/points/delete?wait=true")
                    .header("Content-Type", "application/json")
                    .bodyValue(deleteBody)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            log.error("⚠️ 清理旧分类失败: " + e.getMessage());
        }
    }

    /**
     * 分类过多时用 AI 精简合并为不超过 15 个粗粒度分类。
     */
    private List<Map<String, Object>> consolidateCategories(
            List<Map<String, Object>> categories, String toolList) {
        try {
            StringBuilder catList = new StringBuilder();
            for (Map<String, Object> cat : categories) {
                catList.append("- ").append(cat.get("name"))
                        .append(": ").append(cat.getOrDefault("desc", ""))
                        .append(" → 工具: ").append(cat.getOrDefault("tools", "[]"))
                        .append("\n");
            }
            String prompt = promptLoader.getCategoryConsolidation().formatted(catList, toolList);
            String response = modelRouter.normal().prompt().user(prompt).call().content();
            if (response == null || response.isBlank()) return categories;

            String json = AiResponseUtils.stripMarkdownCodeBlock(response);
            List<Map<String, Object>> merged =
                    objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            if (merged == null || merged.isEmpty()) return categories;

            log.info("📦 精简后: {} → {} 类", categories.size(), merged.size());
            return merged;
        } catch (Exception e) {
            log.warn("⚠️ 分类精简失败，使用原始分类: {}", e.getMessage());
            return categories;
        }
    }

    private boolean hasCategories() {
        try {
            String countJson = qdrant.post()
                    .uri("/collections/" + collectionName + "/points/count")
                    .header("Content-Type", "application/json")
                    .bodyValue("{}")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (countJson != null) {
                int count = objectMapper.readTree(countJson).path("result").path("count").asInt(0);
                return count > 0;
            }
        } catch (Exception e) {
            log.debug("检查分类缓存失败: {}", e.getMessage());
        }
        return false;
    }

    private void upsertBatch(Map<String, Object> body) {
        try {
            qdrant.put()
                    .uri("/collections/" + collectionName + "/points?wait=true")
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            log.error("❌ tool-categories 批量 upsert 失败: " + e.getMessage());
        }
    }

    private static Set<String> collectExistingToolNames(ToolCallback[] allTools) {
        Set<String> names = new HashSet<>();
        for (ToolCallback tc : allTools) {
            names.add(tc.getToolDefinition().name());
        }
        return names;
    }

    // ========== 内部类型 ==========

    /**
     * 分类匹配结果。
     *
     * @param name        分类名称
     * @param description 分类描述
     * @param tools       该分类下的工具名列表
     * @param score       向量相似度分数
     */
    public record CategoryMatch(
            String name,
            String description,
            List<String> tools,
            double score
    ) {}
}
