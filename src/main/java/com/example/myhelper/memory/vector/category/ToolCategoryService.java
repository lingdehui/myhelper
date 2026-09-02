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
 * <p>Neo4j 是分类树和工具归属关系的事实源；Qdrant 只保存分类语义向量，用于快速
 * 召回可能相关的目录。查询后的树导航仍以 Neo4j 快照为准，避免两个存储中的层级
 * 元数据发生漂移。</p>
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
     * 增量分类：只有【初始化】（分类系统为空）才做全量建树；之后只为新增工具归类。
     *
     * 归入现有分类必须同时满足：向量匹配分 ≥ MIN_MATCH_SCORE 且目标分类工具数 &lt; MAX_CATEGORY_TOOLS。
     * 不满足则强制新建分类（不给 LLM "塞进老分类"的自由，避免单分类膨胀到几百个工具）。
     *
     * @return 本次增量归类的工具数；-1 表示无新增工具
     */
    public int syncCategoriesIncremental(ToolCallback[] allTools) {
        if (allTools == null || allTools.length == 0) return 0;

        final double MIN_MATCH_SCORE = 0.55;   // 向量匹配最低分，低于此分视为"放不进现有分类"
        final int MAX_CATEGORY_TOOLS = 60;      // 单分类工具数上限，超过则不再塞入

        // 只有【初始化】（分类系统为空）才做全量建树
        if (!hasCategories()) {
            log.info("🏗️ 分类系统为空（初始化），执行首次全量建树");
            return syncCategories(allTools, true);
        }

        // 1. 已归属工具名集合（从现有分类的 toolNames 收集）
        List<CategorySummary> allCats = listAllCategories();
        Set<String> categorized = new HashSet<>();
        for (CategorySummary s : allCats) {
            if (s.toolNames() != null) categorized.addAll(s.toolNames());
        }

        // 2. 识别新增工具
        List<ToolCallback> newTools = new ArrayList<>();
        for (ToolCallback tc : allTools) {
            if (!categorized.contains(tc.getToolDefinition().name())) {
                newTools.add(tc);
            }
        }
        if (newTools.isEmpty()) {
            log.info("📦 无新增工具，跳过增量分类");
            return -1;
        }

        // 3. 向量匹配 + 硬阈值归入现有分类；放不进的强制新建
        List<ToolCallback> toNewCategory = new ArrayList<>();
        int linked = 0;
        for (ToolCallback tc : newTools) {
            String name = tc.getToolDefinition().name();
            String desc = tc.getToolDefinition().description();
            String query = name + " " + (desc != null ? desc : "");
            boolean assigned = false;
            for (CategoryMatch m : searchCategories(query, 3, MIN_MATCH_SCORE)) {
                // searchCategories 返回的 name 是 displayName，反查 @Id 用于 linkCategoryByName
                Optional<CategorySummary> target = allCats.stream()
                        .filter(c -> c.name().equals(m.name()) || c.id().equals(m.name()))
                        .findFirst();
                if (target.isPresent() && target.get().toolNames().size() < MAX_CATEGORY_TOOLS) {
                    toolRegistry.linkCategoryByName(name, target.get().id());
                    log.info("📦 增量归类: {} → {}（{} 个工具）", name, target.get().name(), target.get().toolNames().size() + 1);
                    linked++;
                    assigned = true;
                    break;
                }
            }
            if (!assigned) toNewCategory.add(tc);
        }

        // 4. 放不进的 → 强制新建分类
        if (!toNewCategory.isEmpty()) {
            createNewCategories(toNewCategory, allCats, allTools);
        }

        return linked;
    }

    /**
     * 强制新建分类：AI 只针对待归类的新工具 + 现有 L1 概览生成新分类树。
     * prompt 明确禁止把工具归入现有分类（否则 LLM 偷懒导致分类膨胀）。
     */
    private void createNewCategories(List<ToolCallback> newTools, List<CategorySummary> allCats, ToolCallback[] allTools) {
        if (!modelRouter.isCloudAvailable()) {
            log.warn("☁️ 云端 AI 未配置，跳过新建分类，{} 个新工具暂未归类", newTools.size());
            return;
        }

        StringBuilder l1 = new StringBuilder();
        for (CategorySummary c : allCats) {
            if (c.level() == 1) {
                l1.append("- id=").append(c.id())
                  .append(", name=").append(c.name())
                  .append(", desc=").append(c.description() != null ? c.description() : "")
                  .append("\n");
            }
        }

        StringBuilder toolList = new StringBuilder();
        for (ToolCallback tc : newTools) {
            toolList.append("- ").append(tc.getToolDefinition().name());
            String d = tc.getToolDefinition().description();
            if (d != null && !d.isBlank()) {
                toolList.append(": ").append(AiResponseUtils.truncateNotNull(d, 80));
            }
            toolList.append("\n");
        }

        String prompt = """
                现有 L1 大类（仅作参考，禁止把下面的新工具归入这些分类）：
                %s

                以下新工具无法归入任何现有分类，必须新建 L1 大类收纳它们：
                %s

                请为这些新工具新建 L1 大类，输出 JSON 数组（每个节点字段：id/name/desc/tools/children）：
                - id 用小写英文点分命名（如 video.edit）
                - name 用中文 2-6 字
                - desc 一句话说明该分类能做什么
                - tools 只能填上面列出的新工具名，不得填其他工具
                - 可在 children 里再分子类（L2）
                直接输出 JSON 数组，不要任何解释。
                """.formatted(l1, toolList);

        try {
            log.info("☁️ 增量新建分类：{} 个新工具 → DeepSeek 生成新分类树...", newTools.size());
            String response = modelRouter.cloudOnly().prompt().user(prompt).call().content();
            if (response == null || response.isBlank()) return;

            String json = AiResponseUtils.stripMarkdownCodeBlock(response);
            List<Map<String, Object>> tree = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});

            long version = System.currentTimeMillis();
            Set<String> existingTools = collectExistingToolNames(allTools);
            List<Map<String, Object>> points = new ArrayList<>();
            int nodeCount = flattenTree(tree, "root", 1, existingTools, points, version);

            if (points.isEmpty()) return;
            upsertBatch(Map.of("points", points));
            log.info("✅ 增量新建分类完成: {} 个新节点", nodeCount);
        } catch (Exception e) {
            log.error("❌ 增量新建分类失败: {}", e.getMessage());
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

                String id = String.valueOf(payload.get("id"));
                String name = String.valueOf(payload.get("name"));
                String desc = String.valueOf(payload.getOrDefault("description", name));
                double score = ((Number) point.getOrDefault("score", 0.0)).doubleValue();

                // parentId、level、tools 以 Neo4j 为准，不能从 Qdrant payload 推断。
                // Qdrant 点只承担“这个分类是否语义相关”的职责。
                results.add(new CategoryMatch(id, name, desc, score));
            }

            results.sort((a, b) -> Double.compare(b.score(), a.score()));
            return results;

        } catch (Exception e) {
            log.error("❌ 工具分类搜索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 从分类向量索引召回目录，再在给定的 Neo4j 分类快照中展开其子树工具。
     *
     * <p>这是工具规划的无模型目录浏览步骤：向量索引先找可能相关的目录，随后按
     * parentId 在内存快照中展开工具。它不会让模型逐层阅读目录，也不会截断目录
     * 下的工具；是否适合走快速路径由调用方按候选规模和置信度决定。</p>
     *
     * @param query          用户请求或已清洗的检索请求
     * @param topK           最多召回的分类数
     * @param minScore       分类语义相似度下限
     * @param categorySnapshot 本轮固定的分类快照，防止规划过程中分类变化
     * @return 命中的分类及其完整子树中的工具名，二者均按相关性和树顺序去重
     */
    public CategoryCandidateRecall recallCandidateTools(String query, int topK, double minScore,
                                                          List<CategorySummary> categorySnapshot) {
        if (query == null || query.isBlank() || categorySnapshot == null || categorySnapshot.isEmpty()) {
            return CategoryCandidateRecall.empty();
        }

        List<CategoryMatch> matches = searchCategories(query, topK, minScore);
        if (matches.isEmpty()) return CategoryCandidateRecall.empty();

        Map<String, CategorySummary> categoriesById = new HashMap<>();
        Map<String, List<CategorySummary>> childrenByParent = new HashMap<>();
        for (CategorySummary category : categorySnapshot) {
            categoriesById.put(category.id(), category);
            childrenByParent.computeIfAbsent(category.parentId(), ignored -> new ArrayList<>()).add(category);
        }
        childrenByParent.values().forEach(children -> children.sort(
                Comparator.comparingInt(CategorySummary::level).thenComparing(CategorySummary::name)));

        LinkedHashSet<String> selectedCategoryIds = new LinkedHashSet<>();
        LinkedHashSet<String> toolNames = new LinkedHashSet<>();
        List<CategoryMatch> resolvedMatches = new ArrayList<>();
        for (CategoryMatch match : matches) {
            CategorySummary root = categoriesById.get(match.id());
            // 兼容历史 Qdrant 点缺失 id 的情况：用显示名回查一次 Neo4j 快照。
            if (root == null) {
                root = categorySnapshot.stream()
                        .filter(category -> category.name().equals(match.name()))
                        .findFirst()
                        .orElse(null);
            }
            if (root == null) continue;

            resolvedMatches.add(match);
            collectSubtreeTools(root, childrenByParent, selectedCategoryIds, toolNames);
        }

        return new CategoryCandidateRecall(resolvedMatches, new ArrayList<>(selectedCategoryIds),
                new ArrayList<>(toolNames));
    }

    /** 深度优先展开分类子树；交叉分类的工具名通过 LinkedHashSet 去重。 */
    private void collectSubtreeTools(CategorySummary category,
                                     Map<String, List<CategorySummary>> childrenByParent,
                                     Set<String> selectedCategoryIds,
                                     Set<String> toolNames) {
        if (!selectedCategoryIds.add(category.id())) return;
        toolNames.addAll(category.toolNames());
        for (CategorySummary child : childrenByParent.getOrDefault(category.id(), List.of())) {
            collectSubtreeTools(child, childrenByParent, selectedCategoryIds, toolNames);
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

    /** Qdrant 分类语义召回结果；树结构字段由 Neo4j 分类快照补全。 */
    public record CategoryMatch(String id, String name, String description, double score) {}

    /** 无模型目录路由的结果：相关分类、展开的分类 id、以及候选工具名。 */
    public record CategoryCandidateRecall(
            List<CategoryMatch> matchedCategories,
            List<String> categoryIds,
            List<String> toolNames
    ) {
        private static CategoryCandidateRecall empty() {
            return new CategoryCandidateRecall(List.of(), List.of(), List.of());
        }

        /** 最高分类相似度；没有分类时为 0。 */
        public double bestScore() {
            return matchedCategories.stream().mapToDouble(CategoryMatch::score).max().orElse(0.0);
        }
    }

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
