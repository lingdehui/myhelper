package com.example.myhelper.registry;

import com.example.myhelper.config.SystemEnvironmentService;
import com.example.myhelper.memory.vector.EmbeddingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具统一注册中心：所有工具（Java @Tool / MCP / @GeneratedTool）的唯一入口。
 *
 * <h3>双存储架构</h3>
 * <ul>
 *   <li><b>Neo4j</b>：主存储，保存工具完整元数据 + 分类关系</li>
 *   <li><b>Qdrant</b>：辅助存储，用于语义向量搜索</li>
 *   <li><b>内存</b>：L1 缓存，启动时从 Neo4j 加载，避免每次查 DB</li>
 * </ul>
 */
@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final ToolRepository toolRepo;
    private final ToolCategoryRepository categoryRepo;
    private final WebClient qdrant;
    private final EmbeddingService embeddingService;
    private final SystemEnvironmentService envService;
    private final ObjectMapper objectMapper;

    private final String collectionName;

    @Value("${qdrant.vector-size:768}")
    private int vectorSize;

    /** L1 内存缓存：toolId → ToolModel */
    private final Map<String, ToolModel> memoryCache = new ConcurrentHashMap<>();

    /** L1 内存索引：toolName → toolId（快速按名查） */
    private final Map<String, String> nameToId = new ConcurrentHashMap<>();

    /** 工具变更标记：有新工具或工具被废弃时设为 true，下次规划时触发重新分类 */
    private volatile boolean dirty = false;

    public ToolRegistry(ToolRepository toolRepo, ToolCategoryRepository categoryRepo,
                         WebClient qdrantWebClient, EmbeddingService embeddingService,
                         SystemEnvironmentService envService) {
        this.toolRepo = toolRepo;
        this.categoryRepo = categoryRepo;
        this.qdrant = qdrantWebClient;
        this.embeddingService = embeddingService;
        this.envService = envService;
        this.objectMapper = new ObjectMapper();
        this.collectionName = envService.collectionName("tool-registry");
    }

    // ==================== 初始化 ====================

    /** 确保 Qdrant 集合存在（幂等） */
    public void ensureCollection() {
        try {
            Boolean exists = qdrant.get()
                    .uri("/collections/" + collectionName)
                    .retrieve().toBodilessEntity().map(r -> true).onErrorReturn(false).block();
            if (Boolean.FALSE.equals(exists)) {
                String body = String.format(
                        "{\"vectors\": {\"size\": %d, \"distance\": \"Cosine\"}}", vectorSize);
                qdrant.put().uri("/collections/" + collectionName)
                        .header("Content-Type", "application/json")
                        .bodyValue(body).retrieve().toBodilessEntity().block();
                log.info("📦 创建工具注册表集合: {}", collectionName);
            } else {
                log.info("📦 工具注册表集合已存在: {}", collectionName);
            }
        } catch (Exception e) {
            log.error("⚠️ 工具注册表初始化失败（将降级到内存模式）: {}", e.getMessage());
        }
    }

    /** 从 Neo4j 加载所有活跃工具到内存缓存 */
    public void loadFromNeo4j() {
        try {
            List<ToolNode> nodes = toolRepo.findAllActive();
            memoryCache.clear();
            nameToId.clear();
            for (ToolNode node : nodes) {
                ToolModel model = nodeToModel(node);
                memoryCache.put(node.getId(), model);
                nameToId.put(node.getName(), node.getId());
            }
            log.info("📦 从 Neo4j 加载 {} 个工具到内存缓存", nodes.size());
        } catch (Exception e) {
            log.warn("⚠️ 从 Neo4j 加载工具失败（可能 Neo4j 未就绪）: {}", e.getMessage());
        }
    }

    // ==================== 注册（入库） ====================

    /**
     * 注册或更新一个工具。已存在则更新元数据，不存在则新增。
     * Neo4j + Qdrant 双写。
     */
    public void upsertTool(ToolModel model) {
        boolean isNew = !memoryCache.containsKey(model.id());

        // 1. Neo4j（可降级）
        ToolNode node = modelToNode(model);
        try {
            toolRepo.save(node);
        } catch (Exception e) {
            log.warn("⚠️ Neo4j 写入失败（将仅使用 Qdrant+内存）: {} → {}", model.name(), e.getMessage());
        }

        // 2. Qdrant 向量
        upsertToQdrant(model);

        // 3. 内存缓存
        memoryCache.put(model.id(), model);
        nameToId.put(model.name(), model.id());

        // 只有「新增真实工具」才需要重新分类；更新已有工具、或 planStep_ 动态工具不触发
        // （避免每次 turn 动态注册 planStep_ 工具导致分类反复重同步）
        if (isNew && !model.name().startsWith("planStep_")) {
            markDirty();
        }
    }

    /**
     * 批量注册/更新工具（一次事务）。
     */
    public void upsertAll(List<ToolModel> models) {
        List<ToolNode> nodes = new ArrayList<>();
        List<Map<String, Object>> qdrantPoints = new ArrayList<>();

        for (ToolModel m : models) {
            nodes.add(modelToNode(m));
            qdrantPoints.add(buildQdrantPoint(m));
            memoryCache.put(m.id(), m);
            nameToId.put(m.name(), m.id());
        }

        try {
            toolRepo.saveAll(nodes);
        } catch (Exception e) {
            log.warn("⚠️ Neo4j 批量入库失败（将仅使用 Qdrant+内存）: {}", e.getMessage());
        }

        if (!qdrantPoints.isEmpty()) {
            try {
                Map<String, Object> body = Map.of("points", qdrantPoints);
                qdrant.put().uri("/collections/" + collectionName + "/points?wait=true")
                        .header("Content-Type", "application/json")
                        .bodyValue(body).retrieve().toBodilessEntity().block();
            } catch (Exception e) {
                log.error("❌ Qdrant 批量入库失败: {}", e.getMessage());
            }
        }

        log.info("📦 批量注册 {} 个工具", models.size());
    }

    // ==================== 废弃 ====================

    /**
     * 标记工具为 DEPRECATED（代码中找不到对应实体，但保留记录）。
     */
    public void deprecateTool(String toolId) {
        Optional<ToolNode> opt = toolRepo.findById(toolId);
        if (opt.isPresent()) {
            ToolNode node = opt.get();
            node.setStatus("DEPRECATED");
            node.setCallable(false);
            node.setUpdatedAt(System.currentTimeMillis());
            toolRepo.save(node);

            // 从 Qdrant 删除向量
            deleteFromQdrant(toolId);

            // 从内存移除
            ToolModel removed = memoryCache.remove(toolId);
            if (removed != null) nameToId.remove(removed.name());

            log.info("🗑️ 废弃工具: {} ({})", node.getName(), toolId);
        }
    }

    // ==================== 查询 ====================

    /** 按 ID 精确查找（优先内存缓存，miss 则查 Neo4j） */
    public Optional<ToolModel> findById(String toolId) {
        ToolModel cached = memoryCache.get(toolId);
        if (cached != null) return Optional.of(cached);
        return toolRepo.findById(toolId).map(this::nodeToModel);
    }

    /** 按名称精确查找 */
    public Optional<ToolModel> findByName(String name) {
        String id = nameToId.get(name);
        if (id != null) return findById(id);
        // 缓存 miss，查 Neo4j
        return toolRepo.findAllActive().stream()
                .filter(n -> n.getName().equals(name))
                .findFirst()
                .map(this::nodeToModel);
    }

    /** 获取所有活跃工具 */
    public List<ToolModel> findAllActive() {
        if (!memoryCache.isEmpty()) return new ArrayList<>(memoryCache.values());
        return toolRepo.findAllActive().stream()
                .map(this::nodeToModel)
                .collect(Collectors.toList());
    }

    /** 按类型过滤 */
    public List<ToolModel> findByType(String type) {
        return findAllActive().stream()
                .filter(m -> m.type().equalsIgnoreCase(type))
                .collect(Collectors.toList());
    }

    // ==================== 向量搜索 ====================

    /**
     * 语义搜索工具。embed(query) → Qdrant → 取 topK → Neo4j 补全详情。
     *
     * @param query   搜索关键词
     * @param topK    返回数量
     * @param minScore 最小相似度（0-1）
     */
    public List<ToolModel> searchTools(String query, int topK, double minScore) {
        try {
            List<Float> vector = embeddingService.embed(query);

            Map<String, Object> searchBody = new LinkedHashMap<>();
            searchBody.put("vector", vector);
            searchBody.put("limit", topK);
            searchBody.put("with_payload", true);
            searchBody.put("score_threshold", (float) minScore);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = qdrant.post()
                    .uri("/collections/" + collectionName + "/points/search")
                    .header("Content-Type", "application/json")
                    .bodyValue(searchBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) return List.of();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> points =
                    (List<Map<String, Object>>) response.getOrDefault("result", List.of());
            if (points.isEmpty()) return List.of();

            List<ToolModel> results = new ArrayList<>();
            for (Map<String, Object> point : points) {
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) point.get("payload");
                String toolId = payload != null ? String.valueOf(payload.get("toolId")) : null;
                if (toolId == null || toolId.isBlank() || "null".equals(toolId)) continue;
                // 优先从内存缓存取（已有完整参数信息）
                ToolModel model = memoryCache.get(toolId);
                if (model != null) {
                    results.add(model);
                } else {
                    // miss：从 Neo4j 补充
                    toolRepo.findById(toolId).ifPresent(n -> results.add(nodeToModel(n)));
                }
            }
            log.info("🔍 工具搜索 '{}' → {} 个结果", query, results.size());
            return results;

        } catch (Exception e) {
            log.error("❌ 工具搜索失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 分类管理 ====================

    /** 为工具关联分类 */
    public void linkCategory(String toolId, String categoryName) {
        Optional<ToolNode> toolOpt = toolRepo.findById(toolId);
        Optional<ToolCategoryNode> catOpt = categoryRepo.findById(categoryName);
        if (toolOpt.isPresent() && catOpt.isPresent()) {
            ToolNode node = toolOpt.get();
            node.getCategories().add(catOpt.get());
            toolRepo.save(node);
        }
    }

    /** 按工具名关联分类（AI 分类的 tools 数组里是工具名，需先映射到 toolId） */
    public void linkCategoryByName(String toolName, String categoryName) {
        Optional<ToolModel> tool = findByName(toolName);
        if (tool.isPresent()) {
            linkCategory(tool.get().id(), categoryName);
        }
    }

    /** 确保分类节点存在（无层级，兼容旧调用），不存在则创建 */
    public void ensureCategory(String name, String displayName, String description, Integer priority) {
        ensureCategory(name, displayName, description, priority, null, null);
    }

    /** 确保分类节点存在（含层级），不存在则创建，存在则更新描述/层级 */
    public void ensureCategory(String name, String displayName, String description, Integer priority,
                               String parentId, Integer level) {
        ToolCategoryNode cat = categoryRepo.findById(name).orElse(null);
        if (cat == null) {
            cat = new ToolCategoryNode(name, displayName, description, priority, parentId, level);
            cat.setDynamic(true);
            log.info("📁 创建分类: {} (L{})", name, level);
        } else {
            if (displayName != null) cat.setDisplayName(displayName);
            if (description != null) cat.setDescription(description);
            if (priority != null) cat.setPriority(priority);
            if (parentId != null) cat.setParentId(parentId);
            if (level != null) cat.setLevel(level);
            cat.setDynamic(true);
        }
        categoryRepo.save(cat);
    }

    /** 从 Neo4j 读所有分类节点（含 BELONGS_TO 工具关系） */
    public List<ToolCategoryNode> listCategoryNodes() {
        try {
            List<ToolCategoryNode> result = new ArrayList<>();
            categoryRepo.findAll().forEach(result::add);
            return result;
        } catch (Exception e) {
            log.warn("⚠️ 从 Neo4j 读分类节点失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== 统计 ====================

    public int countActive() {
        return memoryCache.isEmpty() ? (int) toolRepo.count() : memoryCache.size();
    }

    /** 标记工具变更（新工具生成时调用），下次规划触发重新分类 */
    public void markDirty() {
        this.dirty = true;
    }

    /** 检查并清除 dirty 标记（原子操作） */
    public boolean checkAndClearDirty() {
        boolean was = dirty;
        dirty = false;
        return was;
    }

    // ==================== 内部方法 ====================

    private ToolNode modelToNode(ToolModel m) {
        try {
            String paramsJson = objectMapper.writeValueAsString(m.parameters());
            ToolNode node = new ToolNode(m.id(), m.name(), m.description(),
                    m.type(), m.source(), paramsJson, m.returnType());
            node.setStatus(m.status());
            node.setCallable(m.callable());
            return node;
        } catch (Exception e) {
            throw new RuntimeException("序列化参数失败: " + m.name(), e);
        }
    }

    private ToolModel nodeToModel(ToolNode n) {
        try {
            List<ToolModel.ParamInfo> params = List.of();
            if (n.getParametersJson() != null && !n.getParametersJson().isBlank()) {
                params = objectMapper.readValue(n.getParametersJson(),
                        new TypeReference<List<ToolModel.ParamInfo>>() {});
            }
            List<String> cats = n.getCategories().stream()
                    .map(ToolCategoryNode::getName).collect(Collectors.toList());
            return new ToolModel(n.getId(), n.getName(), n.getDescription(),
                    n.getType(), n.getSource(), params, n.getReturnType(),
                    n.getStatus(), n.isCallable(), cats, null);
        } catch (Exception e) {
            return new ToolModel(n.getId(), n.getName(), n.getDescription(),
                    n.getType(), n.getSource(), List.of(), "String",
                    n.getStatus(), n.isCallable(), List.of(), null);
        }
    }

    private Map<String, Object> buildQdrantPoint(ToolModel m) {
        // 中英文混合 embedding 文本：工具名 + 分类 + 描述（MCP 工具描述含中文检索关键词）
        StringBuilder embed = new StringBuilder(m.name());
        if (m.categories() != null && !m.categories().isEmpty()) {
            embed.append(" 分类:").append(String.join(" ", m.categories()));
        }
        if (m.description() != null && !m.description().isBlank()) {
            embed.append(" ").append(m.description());
        }
        List<Float> vector = embeddingService.embed(embed.toString());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toolId", m.id());
        payload.put("name", m.name());
        payload.put("description", m.description());
        payload.put("type", m.type());
        payload.put("status", m.status());

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", qdrantPointId(m.id()));
        point.put("vector", vector);
        point.put("payload", payload);
        return point;
    }

    /**
     * Qdrant point id 只接受 UUID 或 u64 整数，而工具 id 是 "type:source:name" 形式，
     * 需转换成稳定 UUID（同一 toolId 永远生成同一 UUID，保证幂等可更新/删除）。
     */
    private String qdrantPointId(String toolId) {
        return UUID.nameUUIDFromBytes(toolId.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private void upsertToQdrant(ToolModel m) {
        try {
            Map<String, Object> point = buildQdrantPoint(m);
            Map<String, Object> body = Map.of("points", List.of(point));
            qdrant.put().uri("/collections/" + collectionName + "/points?wait=true")
                    .header("Content-Type", "application/json")
                    .bodyValue(body).retrieve().toBodilessEntity().block();
        } catch (Exception e) {
            log.warn("⚠️ 工具 {} 写入 Qdrant 失败: {}", m.name(), e.getMessage());
        }
    }

    private void deleteFromQdrant(String toolId) {
        try {
            Map<String, Object> body = Map.of("points", List.of(qdrantPointId(toolId)));
            qdrant.post().uri("/collections/" + collectionName + "/points/delete?wait=true")
                    .header("Content-Type", "application/json")
                    .bodyValue(body).retrieve().toBodilessEntity().block();
        } catch (Exception ignored) {}
    }
}
