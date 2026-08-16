package com.example.myhelper.memory.vector.category;

import com.example.myhelper.common.AiResponseUtils;
import com.example.myhelper.common.PromptLoader;
import com.example.myhelper.config.SystemEnvironmentService;
import com.example.myhelper.memory.vector.EmbeddingService;
import com.example.myhelper.registry.ToolRegistry;
import com.example.myhelper.registry.ToolCategoryNode;
import com.example.myhelper.registry.ToolNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import com.example.myhelper.config.ModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 工具分类服务：树形分类 + Qdrant 向量存储。
 *
 * <h3>3层树形结构</h3>
 * <pre>
 * L1 大类 (8-15个) ─ 功能领域，简短描述
 *   ├─ L2 子类 (可选) ─ 细分功能，当L1下工具>15个时拆分
 *   │   └─ L3 工具 (叶子) ─ 实际工具名列表
 *   └─ L2可省略，直接挂工具（L1下工具≤15个时）
 * </pre>
 *
 * <h3>存储方式</h3>
 * 树结构展平为独立 Qdrant points，每个 point 含 parent_id + level + tools。
 * 查询时通过 getChildren() 导航，buildCategoryTree() 组装展示树。
 *
 * <h3>交叉分类</h3>
 * 同一工具可出现在多个分类中（如 ocrScreen 属于"屏幕截取"也属于"文字识别"）。
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
    private final ToolRegistry toolRegistry;

    private static final String BASE_collectionName = "tool-categories";
    private String collectionName;

    @Value("${qdrant.vector-size:768}")
    private int vectorSize;

    private volatile long lastSyncVersion = 0;
    /** 上次同步时的工具集签名（方案B防抖：工具集没变则跳过重分类） */
    private volatile String lastSyncedSignature = null;

    public ToolCategoryService(WebClient qdrantWebClient,
                                ModelRouter modelRouter,
                                EmbeddingService embeddingService,
                                PromptLoader promptLoader,
                                SystemEnvironmentService envService,
                                ToolRegistry toolRegistry) {
        this.qdrant = qdrantWebClient;
        this.modelRouter = modelRouter;
        this.embeddingService = embeddingService;
        this.envService = envService;
        this.toolRegistry = toolRegistry;
        this.objectMapper = new ObjectMapper();
        this.promptLoader = promptLoader;
    }

    @PostConstruct
    public void initCollection() {
        this.collectionName = envService.collectionName(BASE_collectionName);
        log.info("📦 ToolCategory 集合: {}", collectionName);
        ensureCollection();
    }

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
                log.info("📦 Qdrant 集合 '" + collectionName + "' 已创建");
            }
        } catch (Exception e) {
            log.error("⚠️ tool-categories 初始化失败");
        }
    }

    /**
     * 同步工具分类到 Qdrant — AI 生成 3 层树形分类。
     *
     * @param allTools 所有可用工具
     * @param force    true=强制刷新
     * @return 分类节点数
     */
    public int syncCategories(ToolCallback[] allTools, boolean force) {
        if (allTools == null || allTools.length == 0) return 0;
        ensureCollection();

        // 方案B 防抖：工具集签名与上次同步一致且分类已存在 → 跳过（含 force 场景，避免 AI 反复重分类）
        if (lastSyncedSignature != null
                && lastSyncedSignature.equals(computeToolSignature(allTools))
                && hasCategories()) {
            log.info("📦 工具集签名未变，跳过重分类（方案B防抖）");
            return -1;
        }

        if (!force && hasCategories()) {
            log.info("📦 工具分类已缓存，跳过 AI 同步");
            return -1;
        }

        try {
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

            if (!modelRouter.isCloudAvailable()) {
                log.warn("☁️ 云端 AI 未配置，跳过工具分类同步");
                return 0;
            }

            log.info("☁️ 工具分类同步: {} 个工具 → DeepSeek 生成树形分类...", allTools.length);
            String response = modelRouter.cloudOnly().prompt().user(prompt).call().content();
            if (response == null || response.isBlank()) return 0;

            String json = AiResponseUtils.stripMarkdownCodeBlock(response);
            List<Map<String, Object>> tree = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});

            // 精简合并
            if (tree.size() > 15) {
                log.info("📦 L1 分类数量 {} > 15，触发精简合并...", tree.size());
                tree = consolidateCategories(tree, toolList.toString());
            }

            long version = System.currentTimeMillis();
            Set<String> existingTools = collectExistingToolNames(allTools);
            List<Map<String, Object>> points = new ArrayList<>();
            int nodeCount = flattenTree(tree, "root", 1, existingTools, points, version);

            if (points.isEmpty()) return 0;

            Map<String, Object> upsertBody = Map.of("points", points);
            upsertBatch(upsertBody);

            this.lastSyncVersion = version;
            this.lastSyncedSignature = computeToolSignature(allTools);
            log.info("✅ 工具分类同步完成: {} 个树节点, {} 个工具", nodeCount, allTools.length);
            return nodeCount;

        } catch (Exception e) {
            log.error("❌ 工具分类同步失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 将 AI 返回的树形 JSON 展平为 Qdrant points。
     *
     * @param nodes        当前层级节点列表
     * @param parentId     父节点 id
     * @param level         当前层级 (1=L1, 2=L2, 3=L3工具层)
     * @param existingTools 所有工具名集合（验证用）
     * @param points        收集的 point 列表
     * @param version       同步版本号
     * @return 本层及子层的节点数
     */
    @SuppressWarnings("unchecked")
    private int flattenTree(List<Map<String, Object>> nodes, String parentId, int level,
                              Set<String> existingTools, List<Map<String, Object>> points, long version) {
        int count = 0;
        for (Map<String, Object> node : nodes) {
            String id = String.valueOf(node.getOrDefault("id", UUID.randomUUID().toString()));
            String name = String.valueOf(node.get("name"));
            String desc = String.valueOf(node.getOrDefault("desc", name));

            // 1. 写 Neo4j 分类节点（含 parentId + level，支持树形层级）
            toolRegistry.ensureCategory(id, name, desc, 10, parentId, level);

            // 2. 本层直接工具 → 关联分类（BELONGS_TO，支持交叉分类：同一工具可出现在多个分类）
            List<String> directTools = asStringList(node.get("tools"));
            if (directTools != null) {
                for (String t : directTools) {
                    if (existingTools.contains(t)) {
                        toolRegistry.linkCategoryByName(t, id);
                    }
                }
            }

            // 3. 递归子节点
            List<Map<String, Object>> children = asMapList(node.get("children"));
            if (children != null && !children.isEmpty()) {
                count += flattenTree(children, id, level + 1, existingTools, points, version);
            }

            // 4. 写 Qdrant 分类描述向量（只存 name+description，用于语义推荐分类；归属关系已入图谱）
            String categoryText = name + ": " + desc;
            List<Float> vector = embeddingService.embed(categoryText);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", id);
            payload.put("name", name);
            payload.put("description", desc);

            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", UUID.nameUUIDFromBytes((parentId + "/" + id).getBytes(StandardCharsets.UTF_8)));
            point.put("vector", vector);
            point.put("payload", payload);
            points.add(point);
            count++;
        }
        return count;
    }

    /**
     * 安全地把任意对象转为 Map 列表，过滤掉非 Map 元素（如 AI 返回的字符串）。
     * 防止 AI 分类 JSON 里 children 混入字符串导致 ClassCastException。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object obj) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!(obj instanceof List)) return result;
        for (Object item : (List<?>) obj) {
            if (item instanceof Map) {
                result.add((Map<String, Object>) item);
            }
        }
        return result;
    }

    /**
     * 安全地把任意对象转为字符串列表，过滤掉非 String 元素。
     */
    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object obj) {
        List<String> result = new ArrayList<>();
        if (!(obj instanceof List)) return result;
        for (Object item : (List<?>) obj) {
            if (item instanceof String) {
                result.add((String) item);
            }
        }
        return result;
    }

    /**
     * 精简合并分类树（L1 > 15 时触发）。
     */
    private List<Map<String, Object>> consolidateCategories(
            List<Map<String, Object>> tree, String toolList) {
        try {
            StringBuilder catList = new StringBuilder();
            for (Map<String, Object> node : tree) {
                catList.append("- ").append(node.get("name"))
                        .append(": ").append(node.getOrDefault("desc", ""))
                        .append(" (L1)\n");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = asMapList(node.get("children"));
                if (children != null) {
                    for (Map<String, Object> child : children) {
                        catList.append("    - ").append(child.get("name"))
                                .append(": ").append(child.getOrDefault("desc", ""))
                                .append(" → tools=").append(child.getOrDefault("tools", "[]"))
                                .append("\n");
                    }
                } else {
                    catList.append("    → tools=").append(node.getOrDefault("tools", "[]")).append("\n");
                }
            }
            String prompt = promptLoader.getCategoryConsolidation().formatted(catList, toolList);
            if (!modelRouter.isCloudAvailable()) return tree;
            String response = modelRouter.cloudOnly().prompt().user(prompt).call().content();
            if (response == null || response.isBlank()) return tree;

            String json = AiResponseUtils.stripMarkdownCodeBlock(response);
            List<Map<String, Object>> merged = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            if (merged == null || merged.isEmpty()) return tree;

            log.info("📦 精简后: {} → {} L1类", tree.size(), merged.size());
            return merged;
        } catch (Exception e) {
            log.warn("⚠️ 分类精简失败: {}", e.getMessage());
            return tree;
        }
    }

    /**
     * 构建分类树文本（供 AI prompt 展示）。
     */
    public String buildCategoryTreeText() {
        List<CategorySummary> all = listAllCategories();
        if (all.isEmpty()) return "（分类数据未就绪）";

        // 先建索引：id → summary
        Map<String, CategorySummary> index = new LinkedHashMap<>();
        for (CategorySummary s : all) index.put(s.id(), s);

        StringBuilder sb = new StringBuilder();
        int l1Idx = 0;

        // 先输出 L1
        for (CategorySummary s : all) {
            if (s.level() != 1) continue;
            l1Idx++;
            sb.append(String.format("%d. 【%s】(%d个工具) - %s\n",
                    l1Idx, s.name(), s.toolCount(), s.description()));

            // L2 子类
            int l2Idx = 0;
            for (CategorySummary s2 : all) {
                if (s2.level() != 2 || !s.id().equals(s2.parentId())) continue;
                l2Idx++;
                List<String> tools = s2.toolNames();
                if (tools == null || tools.isEmpty()) continue;
                sb.append(String.format("  %d.%d %s - %s [%d个工具] ",
                        l1Idx, l2Idx, s2.name(), s2.description(), tools.size()));
                // 工具名简写（最多显示15个）
                int show = Math.min(tools.size(), 15);
                for (int i = 0; i < show; i++) {
                    sb.append(markTool(tools.get(i)));
                    if (i < show - 1) sb.append(" ");
                }
                if (tools.size() > 15) sb.append(" ...");
                sb.append("\n");
            }

            // L1 下无 L2 → 直接在 L1 下列出工具
            if (l2Idx == 0) {
                List<String> tools = s.toolNames();
                if (tools != null && !tools.isEmpty()) {
                    sb.append("  → ");
                    int show = Math.min(tools.size(), 20);
                    for (int i = 0; i < show; i++) {
                        sb.append(markTool(tools.get(i)));
                        if (i < show - 1) sb.append(" ");
                    }
                    if (tools.size() > 20) sb.append(" ...");
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    /**
     * 从 Neo4j 图谱拉取全量分类（含 parentId + level + 工具归属）。
     * 归属关系已存图谱，Qdrant 只保留分类描述向量用于语义推荐。
     */
    public List<CategorySummary> listAllCategories() {
        try {
            List<ToolCategoryNode> nodes = toolRegistry.listCategoryNodes();
            if (nodes.isEmpty()) return List.of();

            List<CategorySummary> summaries = new ArrayList<>();
            for (ToolCategoryNode n : nodes) {
                String id = n.getName();                                    // Neo4j @Id = AI 分类 id
                String name = n.getDisplayName() != null ? n.getDisplayName() : id;
                String desc = n.getDescription() != null ? n.getDescription() : name;
                String parentId = n.getParentId() != null ? n.getParentId() : "root";
                int level = n.getLevel() != null ? n.getLevel() : 1;
                List<String> toolNames = n.getTools() == null ? List.of()
                        : n.getTools().stream().map(ToolNode::getName).sorted().toList();
                summaries.add(new CategorySummary(id, name, desc, parentId, level,
                        toolNames.size(), toolNames, List.of()));
            }

            summaries.sort(Comparator.comparingInt(CategorySummary::level)
                    .thenComparing(CategorySummary::name));
            return summaries;

        } catch (Exception e) {
            log.error("❌ 全量分类拉取失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 查直接子节点（供树形导航）。
     */
    public List<CategorySummary> getChildren(String parentId) {
        return listAllCategories().stream()
                .filter(s -> parentId.equals(s.parentId()))
                .toList();
    }

    /**
     * 根据分类名查找分类详情（模糊匹配，忽略大小写和空格）。
     */
    public Optional<CategorySummary> findByName(String name) {
        String key = name.toLowerCase().trim();
        return listAllCategories().stream()
                .filter(s -> s.name().toLowerCase().trim().equals(key)
                        || s.name().contains(name)
                        || name.contains(s.name()))
                .findFirst();
    }

    /**
     * 向量搜索最匹配的分类。
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
            for (Map<String, Object> point : points) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) point.get("payload");
                if (payload == null) continue;

                String name = String.valueOf(payload.get("name"));
                String desc = String.valueOf(payload.getOrDefault("description", name));
                String parentId = String.valueOf(payload.getOrDefault("parentId", "root"));
                int level = ((Number) payload.getOrDefault("level", 1)).intValue();
                @SuppressWarnings("unchecked")
                List<String> tools = (List<String>) payload.getOrDefault("tools", List.of());
                double score = ((Number) point.getOrDefault("score", 0.0)).doubleValue();

                results.add(new CategoryMatch(name, desc, parentId, level, tools, score));
            }

            results.sort((a, b) -> Double.compare(b.score(), a.score()));
            return results;

        } catch (Exception e) {
            log.error("❌ 工具分类搜索失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ========== 内部工具 ==========

    private boolean hasCategories() {
        try {
            return !toolRegistry.listCategoryNodes().isEmpty();
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
            log.error("❌ tool-categories 批量 upsert 失败: {}", e.getMessage());
        }
    }

    private static Set<String> collectExistingToolNames(ToolCallback[] allTools) {
        Set<String> names = new HashSet<>();
        for (ToolCallback tc : allTools) {
            names.add(tc.getToolDefinition().name());
        }
        return names;
    }

    /** 计算工具集签名：工具名去重排序后拼接（方案B防抖判断依据） */
    private String computeToolSignature(ToolCallback[] allTools) {
        Set<String> names = new TreeSet<>();
        for (ToolCallback tc : allTools) {
            names.add(tc.getToolDefinition().name());
        }
        return String.join(",", names);
    }

    /** 分类混排展示标记：planStep_ 开头为 [组合]，其余为 [工具]（§3.3）。 */
    private static String markTool(String name) {
        return (name != null && name.startsWith("planStep_")) ? "[组合] " + name : "[工具] " + name;
    }

    // ========== 内部类型 ==========

    public record CategoryMatch(
            String name,
            String description,
            String parentId,
            int level,
            List<String> tools,
            double score
    ) {}

    /**
     * 分类摘要（树节点）。
     *
     * @param id          唯一标识
     * @param name        分类名称（中文 2-6字）
     * @param description 简短描述（一句话说明能做什么）
     * @param parentId    父节点 id，L1 为 "root"
     * @param level       层级 (1=L1大类, 2=L2子类)
     * @param toolCount   该类下工具数（含所有子孙）
     * @param toolNames   直接挂在该类下的工具名（叶子节点）
     * @param childIds    子分类 id 列表（非叶子节点）
     */
    public record CategorySummary(
            String id,
            String name,
            String description,
            String parentId,
            int level,
            int toolCount,
            List<String> toolNames,
            List<String> childIds
    ) {}
}
