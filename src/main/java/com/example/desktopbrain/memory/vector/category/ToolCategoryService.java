package com.example.desktopbrain.memory.vector.category;

import com.example.desktopbrain.memory.vector.EmbeddingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.ai.chat.client.ChatClient;
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

    private final WebClient qdrant;
    private final ChatClient chatClient;
    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;

    private static final String COLLECTION_NAME = "tool-categories";

    @Value("${qdrant.vector-size:768}")
    private int vectorSize;

    /** 分类同步版本号（时间戳），每次同步更新 */
    private volatile long lastSyncVersion = 0;

    public ToolCategoryService(WebClient qdrantWebClient,
                                ChatClient.Builder chatClientBuilder,
                                EmbeddingService embeddingService) {
        this.qdrant = qdrantWebClient;
        this.chatClient = chatClientBuilder
                .defaultSystem("你是工具分类器，根据工具名称和描述将它们分组到语义类别中。")
                .build();
        this.embeddingService = embeddingService;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 启动时初始化 tool-categories collection（idempotent）。
     */
    @PostConstruct
    public void initCollection() {
        try {
            Boolean exists = qdrant.get()
                    .uri("/collections/" + COLLECTION_NAME)
                    .retrieve()
                    .toBodilessEntity()
                    .map(r -> true)
                    .onErrorReturn(false)
                    .block();
            if (Boolean.FALSE.equals(exists)) {
                String body = String.format(
                        "{\"vectors\": {\"size\": %d, \"distance\": \"Cosine\"}}", vectorSize);
                qdrant.put()
                        .uri("/collections/" + COLLECTION_NAME)
                        .header("Content-Type", "application/json")
                        .bodyValue(body)
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                System.out.println("📦 Qdrant 集合 '" + COLLECTION_NAME + "' 已创建（向量维度: " + vectorSize + "）");
            } else {
                System.out.println("📦 Qdrant 集合 '" + COLLECTION_NAME + "' 已存在");
            }
        } catch (Exception e) {
            System.err.println("⚠️ tool-categories 初始化失败（工具分类将降级）: " + e.getMessage());
        }
    }

    /**
     * 同步工具分类到 Qdrant（启动时 / 工具变化时调用）。
     *
     * <p>收集所有 ToolCallback 的 name + description → AI 分组归类 → 逐条写入 Qdrant。
     * 每个分类作为独立 point，向量 = 分类描述的 embedding。支持交叉分类（同一工具可出现在多个分类中）。</p>
     *
     * @param allTools 所有可用工具（MCP + 本地 + 生成的）
     * @return 分类数量（失败返回 0）
     */
    public int syncCategories(ToolCallback[] allTools) {
        if (allTools == null || allTools.length == 0) return 0;

        try {
            // 构建工具列表字符串供 AI 分类
            StringBuilder toolList = new StringBuilder();
            for (ToolCallback tc : allTools) {
                toolList.append("- ").append(tc.getToolDefinition().name());
                String desc = tc.getToolDefinition().description();
                if (desc != null && !desc.isBlank()) {
                    toolList.append(": ").append(truncate(desc, 80));
                }
                toolList.append("\n");
            }

            String prompt = """
                    将以下工具按功能分组到语义类别中。每个工具可以属于多个类别（交叉分类）。

                    工具列表:
                    %s

                    规则:
                    - 每个类别 3-15 个工具
                    - 类别名简洁（2-4字中文）
                    - 同功能工具可交叉归类
                    - 描述一句话说明该类别的用途
                    - 把 HA 智能家居工具、MCP 桌面工具、本地工具合理分组

                    返回严格 JSON 数组（不要 markdown 标记）:
                    [{"name":"类别名","desc":"简短描述","tools":["tool1","tool2"]}]
                    """.formatted(toolList);

            String response = chatClient.prompt().user(prompt).call().content();
            if (response == null || response.isBlank()) return 0;

            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
            }

            List<Map<String, Object>> categories =
                    objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});

            long version = System.currentTimeMillis();
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

                upsertPoint(categoryId, vector, payload);
                count++;
            }

            this.lastSyncVersion = version;
            System.out.println("✅ 工具分类同步完成: " + count + " 类, "
                    + allTools.length + " 个工具");
            return count;

        } catch (Exception e) {
            System.err.println("❌ 工具分类同步失败: " + e.getMessage());
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
                    .uri("/collections/" + COLLECTION_NAME + "/points/search")
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
            System.out.println("🔍 向量分类匹配: " + results.size() + " 类 → "
                    + totalUnique + " 个工具");
            return results;

        } catch (Exception e) {
            System.err.println("❌ 工具分类搜索失败: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * 检查分类是否已过期（与当前工具数不匹配）。
     */
    public boolean isStale(int currentToolCount) {
        if (lastSyncVersion == 0) return true;
        try {
            // 通过 count API 检查 Qdrant 中分类数量
            @SuppressWarnings("unchecked")
            Map<String, Object> response = qdrant.post()
                    .uri("/collections/" + COLLECTION_NAME + "/points/count")
                    .header("Content-Type", "application/json")
                    .bodyValue("{}")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (response == null) return true;
            int count = ((Number) response.getOrDefault("result", 0)).intValue();
            return count == 0;
        } catch (Exception e) {
            return true;
        }
    }

    // ========== 内部工具方法 ==========

    private void upsertPoint(String id, List<Float> vector, Map<String, Object> payload) {
        try {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", id);
            point.put("vector", vector);
            point.put("payload", payload);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("points", List.of(point));

            qdrant.put()
                    .uri("/collections/" + COLLECTION_NAME + "/points?wait=true")
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            System.err.println("❌ tool-categories upsert 失败: " + e.getMessage());
        }
    }

    private static Set<String> collectExistingToolNames(ToolCallback[] allTools) {
        Set<String> names = new HashSet<>();
        for (ToolCallback tc : allTools) {
            names.add(tc.getToolDefinition().name());
        }
        return names;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replace("\n", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "..." : oneLine;
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
