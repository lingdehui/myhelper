package com.example.myhelper.memory.unit;

import com.example.myhelper.config.SystemEnvironmentService;
import com.example.myhelper.memory.graph.ContainsRelation;
import com.example.myhelper.memory.graph.DisablesRelation;
import com.example.myhelper.memory.graph.FallbackRelation;
import com.example.myhelper.memory.graph.UnitNode;
import com.example.myhelper.memory.graph.UnitRepository;
import com.example.myhelper.memory.vector.EmbeddingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Unit 存储服务：Neo4j（主）+ Qdrant（检索入口）双写，最终一致性（文档 15 v1.7 §10）。
 *
 * <p>写顺序：先写 Neo4j，再写 Qdrant。Qdrant 写失败 → 重试 N 次，仍失败则记录日志，
 * 不强制回滚 Neo4j。后台补偿任务定期扫描 Neo4j 中未被 Qdrant 索引的 Unit，补写。</p>
 *
 * <p>Qdrant point id 与 {@code unitId} 一致，命中后回 Neo4j 展开 CONTAINS 树。</p>
 */
@Service
public class UnitStore {

    private static final Logger log = LoggerFactory.getLogger(UnitStore.class);

    private final UnitRepository unitRepository;
    private final UnitConverter converter;
    private final WebClient qdrant;
    private final EmbeddingService embeddingService;
    private final SystemEnvironmentService envService;
    private final ExperienceQualityService experienceQualityService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${qdrant.unit-registry-collection:unit-registry}")
    private String baseCollectionName;

    @Value("${qdrant.vector-size:768}")
    private int vectorSize;

    @Value("${qdrant.unit.similarity-threshold:0.6}")
    private double similarityThreshold;

    @Value("${qdrant.unit.write-retry:3}")
    private int writeRetry;

    private String collectionName;

    public UnitStore(UnitRepository unitRepository,
                     UnitConverter converter,
                     WebClient qdrantWebClient,
                     EmbeddingService embeddingService,
                     SystemEnvironmentService envService,
                     ExperienceQualityService experienceQualityService) {
        this.unitRepository = unitRepository;
        this.converter = converter;
        this.qdrant = qdrantWebClient;
        this.embeddingService = embeddingService;
        this.envService = envService;
        this.experienceQualityService = experienceQualityService;
    }

    @PostConstruct
    public void initCollection() {
        this.collectionName = envService.collectionName(baseCollectionName);
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
                log.info("📦 Unit 检索集合已创建: {}", collectionName);
            } else {
                log.info("📦 Unit 检索集合已存在: {}", collectionName);
            }
        } catch (Exception e) {
            log.warn("⚠️ Unit 检索集合初始化失败（语义检索将降级）: {}", e.getMessage());
        }
    }

    // ========================================================================
    // 写入（最终一致性）
    // ========================================================================

    /** 保存 Unit（Neo4j 主存储 + Qdrant 检索入口）。 */
    public Unit save(Unit unit) {
        UnitNode node = converter.toNode(unit);
        return converter.toUnit(saveNode(node));
    }

    /** 保存 UnitNode（Neo4j 主存储 + Qdrant 检索入口）。 */
    public UnitNode saveNode(UnitNode node) {
        refreshExperienceMetrics(node, true);
        // 1. 先写 Neo4j（主存储，结构完整性）
        UnitNode saved = unitRepository.save(node);

        // 2. 再写 Qdrant（检索入口，最终一致性，失败重试不回滚）
        indexToQdrant(saved, writeRetry);
        return saved;
    }

    /** 保存一次经验状态变更（不重建 Qdrant 向量索引）。 */
    public UnitNode saveExperienceUpdate(UnitNode node) {
        refreshExperienceMetrics(node, true);
        return unitRepository.save(node);
    }

    private void refreshExperienceMetrics(UnitNode node, boolean touchUpdatedAt) {
        long now = System.currentTimeMillis();
        if (node.getCreatedAt() == null) node.setCreatedAt(now);
        if (touchUpdatedAt) node.setUpdatedAt(now);
        node.setStability(Unit.calcStability(node.getSuccessCount(), node.getFailureCount()));
        node.setQualityScore(experienceQualityService.assess(node).score());
    }

    /** 按 unitId 删除（Neo4j + Qdrant）。MCP_TOOL 不可删，调用方负责把关。 */
    public void delete(String unitId) {
        try {
            unitRepository.deleteById(unitId);
        } catch (Exception e) {
            log.warn("⚠️ Unit Neo4j 删除失败: {} → {}", unitId, e.getMessage());
        }
        deleteFromQdrant(unitId);
    }

    /** 归档（逻辑删除）时移除语义检索索引，保留 Neo4j 节点用于结构/FALLBACK（§6）。 */
    public void unindex(String unitId) {
        deleteFromQdrant(unitId);
    }

    // ========================================================================
    // 关系维护
    // ========================================================================

    /** 建立有序正向引用：parent CONTAINS child（order 从 1 开始）。 */
    public void linkContains(String parentId, String childId, int order) {
        linkContains(parentId, childId, order, null);
    }

    public void linkContains(String parentId, String childId, int order, String argumentsJson) {
        Optional<UnitNode> parentOpt = unitRepository.findByUnitId(parentId);
        Optional<UnitNode> childOpt = unitRepository.findByUnitId(childId);
        if (parentOpt.isPresent() && childOpt.isPresent()) {
            UnitNode parent = parentOpt.get();
            String encoded = argumentsJson == null ? null : java.util.Base64.getEncoder()
                    .encodeToString(argumentsJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            parent.getContains().add(new ContainsRelation(order, encoded, childOpt.get()));
            unitRepository.save(parent);
        }
    }

    /** 建立负向禁用：source DISABLES target。 */
    public void linkDisables(String sourceId, String targetId, String reason, int failCount, String condition) {
        Optional<UnitNode> sourceOpt = unitRepository.findByUnitId(sourceId);
        Optional<UnitNode> targetOpt = unitRepository.findByUnitId(targetId);
        if (sourceOpt.isPresent() && targetOpt.isPresent()) {
            UnitNode source = sourceOpt.get();
            source.getDisables().add(new DisablesRelation(reason, failCount, condition, targetOpt.get()));
            unitRepository.save(source);
        }
    }

    /** 建立降级替代：source FALLBACK target。 */
    public void linkFallback(String sourceId, String targetId, Integer priority) {
        Optional<UnitNode> sourceOpt = unitRepository.findByUnitId(sourceId);
        Optional<UnitNode> targetOpt = unitRepository.findByUnitId(targetId);
        if (sourceOpt.isPresent() && targetOpt.isPresent()) {
            UnitNode source = sourceOpt.get();
            source.getFallback().add(new FallbackRelation(priority, targetOpt.get()));
            unitRepository.save(source);
        }
    }

    // ========================================================================
    // 查询
    // ========================================================================

    public Optional<Unit> findById(String unitId) {
        return unitRepository.findByUnitId(unitId).map(node -> {
            node.setQualityScore(experienceQualityService.assess(node).score());
            return converter.toUnit(node);
        });
    }

    public boolean configureDirectExecution(String unitId, Unit.DirectExecutionStatus status,
                                            List<String> requiredContextIds, List<String> expectedContextIds) {
        Optional<UnitNode> found = unitRepository.findByUnitId(unitId);
        if (found.isEmpty()) return false;
        UnitNode node = found.get();
        node.setDirectExecutionStatus((status == null ? Unit.DirectExecutionStatus.LEARNING : status).name());
        node.setRequiredContextIdsJson(toJson(requiredContextIds == null ? List.of() : requiredContextIds));
        node.setExpectedContextIdsJson(toJson(expectedContextIds == null ? List.of() : expectedContextIds));
        saveExperienceUpdate(node);
        return true;
    }

    /** 统计 ACTIVE 状态的 Unit 数量（探索优化阈值用，替代旧 Episode.countActiveEpisodes）。 */
    public int countActiveUnits() {
        int count = 0;
        for (UnitNode node : unitRepository.findAll()) {
            if ("ACTIVE".equalsIgnoreCase(node.getStatus())) count++;
        }
        return count;
    }

    // ========================================================================
    // 探索 / 规则归纳查询（替代旧 Episode 查询）
    // ========================================================================

    /** 已学主题（成功 PLAN_STEP Unit）：goal + lesson + toolNames + unitId。 */
    public record LearnedUnit(String goal, String lesson, List<String> toolNames, String unitId) {}

    /** 尝试过的主题（所有 Unit）：goal + status + unitId。 */
    public record AttemptedUnit(String goal, String status, String unitId) {}

    /** 近期成功 Unit 摘要（规则归纳用）：任务 + 成功经验 + 成功次数。 */
    public List<String> getRecentSuccessfulSummaries(int limit) {
        List<String> summaries = new ArrayList<>();
        for (UnitNode node : listByStatus("ACTIVE")) {
            if (!"PLAN_STEP".equalsIgnoreCase(node.getUnitKind())) continue;
            if (node.getSuccessCount() < 1) continue;
            String goal = firstNonBlank(node.getGoal(), node.getMatchText());
            if (goal == null) continue;
            String lesson = firstNote(node.getNotesJson());
            summaries.add(String.format("任务: %s | 成功经验: %s | 成功次数: %d",
                    goal, lesson != null ? lesson : "无", node.getSuccessCount()));
            if (summaries.size() >= limit) break;
        }
        return summaries;
    }

    /** 近期已学主题（探索上下文用）：成功 PLAN_STEP Unit。 */
    public List<LearnedUnit> getRecentLearnedUnits(int limit) {
        List<LearnedUnit> result = new ArrayList<>();
        for (UnitNode node : listByStatus("ACTIVE")) {
            if (!"PLAN_STEP".equalsIgnoreCase(node.getUnitKind())) continue;
            String goal = firstNonBlank(node.getGoal(), node.getMatchText());
            if (goal == null) continue;
            result.add(new LearnedUnit(goal, firstNote(node.getNotesJson()),
                    getToolNamesOf(node.getUnitId()), node.getUnitId()));
            if (result.size() >= limit) break;
        }
        return result;
    }

    /** 近期尝试过的主题（所有 Unit，含失败/归档）。 */
    public List<AttemptedUnit> getRecentlyAttemptedUnits(int limit) {
        List<UnitNode> nodes = new ArrayList<>();
        unitRepository.findAll().forEach(nodes::add);
        nodes.sort((a, b) -> Long.compare(
                b.getUpdatedAt() == null ? 0L : b.getUpdatedAt(),
                a.getUpdatedAt() == null ? 0L : a.getUpdatedAt()));
        List<AttemptedUnit> result = new ArrayList<>();
        for (UnitNode node : nodes) {
            String goal = firstNonBlank(node.getGoal(), node.getMatchText());
            if (goal == null) continue;
            result.add(new AttemptedUnit(goal, node.getStatus(), node.getUnitId()));
            if (result.size() >= limit) break;
        }
        return result;
    }

    /** 展开 Unit 的 CONTAINS 子 TOOL 工具名（用于能力覆盖判断）。 */
    public List<String> getToolNamesOf(String unitId) {
        List<String> childIds = unitRepository.findChildUnitIdsOrdered(unitId);
        if (childIds == null) return List.of();
        List<String> toolNames = new ArrayList<>();
        for (String childId : childIds) {
            unitRepository.findByUnitId(childId).ifPresent(child -> {
                if (child.getToolName() != null && !child.getToolName().isBlank()) {
                    toolNames.add(child.getToolName());
                }
            });
        }
        return toolNames;
    }

    private List<UnitNode> listByStatus(String status) {
        List<UnitNode> nodes = new ArrayList<>();
        unitRepository.findAll().forEach(node -> {
            if (status.equalsIgnoreCase(node.getStatus())) nodes.add(node);
        });
        nodes.sort((a, b) -> Long.compare(
                b.getUpdatedAt() == null ? 0L : b.getUpdatedAt(),
                a.getUpdatedAt() == null ? 0L : a.getUpdatedAt()));
        return nodes;
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return null;
    }

    private String firstNote(String notesJson) {
        List<String> notes = parseListString(notesJson);
        return notes.isEmpty() ? null : notes.get(0);
    }

    /** 成功计数 +1 且抵消一次历史失败（奖惩对称，下限0；差异更新：命中相同目标 Unit 时不重复新建，只增量）。 */
    public void incrementSuccess(String unitId) {
        unitRepository.findByUnitId(unitId).ifPresent(node -> {
            int success = node.getSuccessCount() + 1;
            // 奖惩对称：成功抵消一次历史失败（仅计划问题计入的失败），"改过自新"的 Unit 不被旧账永久压住稳定性
            int failure = Math.max(0, node.getFailureCount() - 1);
            node.setSuccessCount(success);
            node.setFailureCount(failure);
            node.setStability(Unit.calcStability(success, failure));
            refreshExperienceMetrics(node, true);
            // Unit 树经过足够成功样本验证后，允许在 ContextUnit 条件满足时直接执行。
            if (success >= 5 && node.getStability() > 0.9
                    && node.getQualityScore() >= experienceQualityService.directExecutionScore()
                    && (node.getParamsJson() == null || node.getParamsJson().isBlank()
                        || "{}".equals(node.getParamsJson().trim()))
                    && !Unit.DirectExecutionStatus.ACTIVE.name().equals(node.getDirectExecutionStatus())) {
                node.setDirectExecutionStatus(Unit.DirectExecutionStatus.ACTIVE.name());
                log.info("🚀 Unit 已验证为可直接执行: {}", node.getUnitId());
            }
            unitRepository.save(node);
        });
    }

    /** 追加一条 notes（差异更新：整体相同、某步不同时写差异点）。 */
    public void appendNote(String unitId, String note) {
        if (note == null || note.isBlank()) return;
        unitRepository.findByUnitId(unitId).ifPresent(node -> {
            List<String> notes = parseListString(node.getNotesJson());
            if (!notes.contains(note)) notes.add(note);
            node.setNotesJson(toJson(notes));
            saveExperienceUpdate(node);
        });
    }

    /** 按 toolName 复用已注册的 TOOL Unit（新工具注册前先查重）。 */
    public Optional<Unit> findByToolName(String toolName) {
        return unitRepository.findByToolName(toolName).map(converter::toUnit);
    }

    /** 合并工具的输出变量选择器；已有人工声明优先，不覆盖。 */
    public void mergeOutputSignature(String unitId, Map<String, String> discovered) {
        if (discovered == null || discovered.isEmpty()) return;
        unitRepository.findByUnitId(unitId).ifPresent(node -> {
            Map<String, String> merged = new LinkedHashMap<>(parseStringMap(node.getOutputSignatureJson()));
            discovered.forEach(merged::putIfAbsent);
            node.setOutputSignatureJson(toJson(merged));
            saveExperienceUpdate(node);
        });
    }

    /** 可被新轨迹折叠复用的计划子图；steps 为递归展开后的原子工具调用模板。 */
    public record PlanFragment(String unitId, List<FragmentStep> steps) {}
    public record FragmentStep(String toolName, String argumentsJson) {}

    /**
     * 读取已有 PLAN_STEP 的叶子调用序列，供沉淀阶段识别公共连续子图。
     * 只返回至少含两个原子步骤的结构，单个工具继续直接复用 TOOL Unit。
     */
    public List<PlanFragment> findReusablePlanFragments() {
        List<PlanFragment> fragments = new ArrayList<>();
        for (UnitNode node : unitRepository.findActiveByUnitKind(UnitKind.PLAN_STEP.name())) {
            List<FragmentStep> steps = new ArrayList<>();
            if (flattenLeafInvocations(node.getUnitId(), steps, new HashSet<>(), Map.of(), 0)
                    && steps.size() >= 2) {
                fragments.add(new PlanFragment(node.getUnitId(), List.copyOf(steps)));
            }
        }
        fragments.sort((a, b) -> Integer.compare(b.steps().size(), a.steps().size()));
        return fragments;
    }

    private boolean flattenLeafInvocations(String unitId, List<FragmentStep> out,
                                           Set<String> path, Map<String, String> scope, int depth) {
        if (depth > 10 || !path.add(unitId)) return false;
        try {
            List<String> rows = unitRepository.findChildInvocationRows(unitId);
            if (rows == null || rows.isEmpty()) return false;
            for (String row : rows) {
                String[] parts = row.split("\\|", -1);
                if (parts.length < 2) return false;
                UnitNode child = unitRepository.findByUnitId(parts[1]).orElse(null);
                if (child == null || !"ACTIVE".equalsIgnoreCase(child.getStatus())) return false;
                String args = resolveFragmentArguments(
                        decodeRelationArguments(parts.length > 2 ? parts[2] : ""), scope);
                if (child.getToolName() != null && !child.getToolName().isBlank()) {
                    out.add(new FragmentStep(child.getToolName(), args));
                } else {
                    Map<String, String> childScope = new LinkedHashMap<>(scope);
                    childScope.putAll(parseStringMap(args));
                    if (!flattenLeafInvocations(child.getUnitId(), out, path, childScope, depth + 1)) {
                        return false;
                    }
                }
            }
            return true;
        } finally {
            path.remove(unitId);
        }
    }

    private String decodeRelationArguments(String encoded) {
        if (encoded == null || encoded.isBlank()) return "{}";
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "{}";
        }
    }

    private String resolveFragmentArguments(String argumentsJson, Map<String, String> scope) {
        if (scope == null || scope.isEmpty()) return argumentsJson;
        try {
            Map<String, Object> values = objectMapper.readValue(argumentsJson,
                    new TypeReference<Map<String, Object>>() {});
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                if (entry.getValue() instanceof String value) {
                    for (Map.Entry<String, String> binding : scope.entrySet()) {
                        value = value.replace("$" + binding.getKey(), binding.getValue());
                    }
                    entry.setValue(value);
                }
            }
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            return argumentsJson;
        }
    }

    private Map<String, String> parseStringMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, String> values = objectMapper.readValue(json,
                    new TypeReference<Map<String, String>>() {});
            return values == null ? Map.of() : values;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * 追加探索记录（文档 15 v1.7 §9）。
     *
     * <p>{@code declareType} 必须显式声明（VALIDATE/OPTIMIZE），不允许默认值；
     * 每次声明执行后追加一条记录，仅作追溯，不单独触发删除/禁用。</p>
     */
    public void appendExplorationRecord(String unitId, ExplorationRecord.DeclareType declareType, String result) {
        if (declareType == null) {
            log.warn("⚠️ 探索记录缺少 declareType（§9 要求显式声明 VALIDATE/OPTIMIZE），忽略");
            return;
        }
        unitRepository.findByUnitId(unitId).ifPresent(node -> {
            List<ExplorationRecord> records = parseExplorationRecords(node.getExplorationRecordsJson());
            int validate = (int) records.stream()
                    .filter(r -> r.declareType() == ExplorationRecord.DeclareType.VALIDATE).count();
            int optimize = (int) records.stream()
                    .filter(r -> r.declareType() == ExplorationRecord.DeclareType.OPTIMIZE).count();
            records.add(new ExplorationRecord(
                    UUID.randomUUID().toString(),
                    declareType,
                    declareType == ExplorationRecord.DeclareType.VALIDATE ? validate + 1 : validate,
                    declareType == ExplorationRecord.DeclareType.OPTIMIZE ? optimize + 1 : optimize,
                    declareType == ExplorationRecord.DeclareType.VALIDATE ? result : null,
                    declareType == ExplorationRecord.DeclareType.OPTIMIZE ? result : null,
                    System.currentTimeMillis()));
            node.setExplorationRecordsJson(toJson(records));
            saveExperienceUpdate(node);
        });
    }

    /**
     * 语义检索：embed(query) → Qdrant（只取 ACTIVE）→ 回 Neo4j 补全 Unit。
     *
     * <p>状态过滤必须在向量库侧完成：只在回 Neo4j 后过滤会让已归档经验占用 topK，
     * 从而遮蔽可复用经验。该过滤也兼容历史上尚未删除的 ARCHIVED 向量点。</p>
     */
    public List<Unit> findSimilar(String query, int topK) {
        try {
            List<Float> vector = embeddingService.embed(query);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", vector);
            body.put("limit", topK);
            body.put("with_payload", true);
            body.put("score_threshold", (float) similarityThreshold);
            body.put("filter", Map.of("must", List.of(
                    Map.of("key", "status", "match", Map.of("value", "ACTIVE")))));

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

            List<Unit> results = new ArrayList<>();
            for (Map<String, Object> point : points) {
                String unitId = String.valueOf(point.get("id"));
                unitRepository.findByUnitId(unitId).ifPresent(node -> {
                    // 兼容历史数据：即使尚未跑过每日刷新，也在读路径实时计算质量。
                    node.setQualityScore(experienceQualityService.assess(node).score());
                    results.add(converter.toUnit(node));
                });
            }
            log.info("🔍 Unit 语义检索 '{}' → {} 个结果", query, results.size());
            return results;
        } catch (Exception e) {
            log.warn("⚠️ Unit 语义检索失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ========================================================================
    // JSON 序列化辅助
    // ========================================================================

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("⚠️ UnitStore 序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private List<String> parseListString(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<String> v = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return v == null ? new ArrayList<>() : v;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<ExplorationRecord> parseExplorationRecords(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<ExplorationRecord> v = objectMapper.readValue(json, new TypeReference<List<ExplorationRecord>>() {});
            return v == null ? new ArrayList<>() : v;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ========================================================================
    // Qdrant 索引 / 补偿
    // ========================================================================

    /** 把单个 ACTIVE UnitNode 写入 Qdrant（embed matchText），失败重试 N 次。 */
    private void indexToQdrant(UnitNode node, int maxRetries) {
        // ARCHIVED / DISABLED Unit 保留在 Neo4j 供审计和图关系使用，但不得重新成为召回入口。
        if (!UnitIndexingPolicy.isSearchable(node.getStatus())) {
            deleteFromQdrant(node.getUnitId());
            return;
        }
        String matchText = node.getMatchText();
        if (matchText == null || matchText.isBlank()) {
            log.warn("⚠️ Unit {} 的 matchText 为空，跳过 Qdrant 索引", node.getUnitId());
            return;
        }

        for (int i = 0; i < maxRetries; i++) {
            try {
                List<Float> vector = embeddingService.embed(matchText);

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("unitId", node.getUnitId());
                payload.put("unitKind", node.getUnitKind());
                payload.put("matchText", matchText);
                payload.put("goal", node.getGoal());
                payload.put("description", node.getDescription());
                payload.put("status", node.getStatus());

                Map<String, Object> point = new LinkedHashMap<>();
                point.put("id", node.getUnitId());
                point.put("vector", vector);
                point.put("payload", payload);

                qdrant.put()
                        .uri("/collections/" + collectionName + "/points?wait=true")
                        .header("Content-Type", "application/json")
                        .bodyValue(Map.of("points", List.of(point)))
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                return;
            } catch (Exception e) {
                if (i < maxRetries - 1) {
                    log.warn("⚠️ Unit {} 写入 Qdrant 失败（第 {} 次，重试）: {}",
                            node.getUnitId(), i + 1, e.getMessage());
                    try { Thread.sleep(200L * (i + 1)); } catch (InterruptedException ignored) {}
                } else {
                    log.error("❌ Unit {} 写入 Qdrant 失败（已重试 {} 次，不回滚 Neo4j）: {}",
                            node.getUnitId(), maxRetries, e.getMessage());
                }
            }
        }
    }

    private void deleteFromQdrant(String unitId) {
        try {
            qdrant.post()
                    .uri("/collections/" + collectionName + "/points/delete?wait=true")
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("points", List.of(unitId)))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception ignored) {}
    }

    private boolean isIndexed(String unitId) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = qdrant.post()
                    .uri("/collections/" + collectionName + "/points")
                    .header("Content-Type", "application/json")
                    .bodyValue(Map.of("ids", List.of(unitId), "with_payload", false, "with_vector", false))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (response == null) return false;
            @SuppressWarnings("unchecked")
            List<Object> points = (List<Object>) response.getOrDefault("result", List.of());
            return points != null && !points.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** 后台补偿任务：只为 ACTIVE Unit 补写缺失的 Qdrant 索引。 */
    @Scheduled(fixedDelayString = "${myhelper.memory.unit-compensate-delay-ms:300000}")
    public void compensateMissingUnits() {
        try {
            List<UnitNode> all = new ArrayList<>();
            unitRepository.findAll().forEach(all::add);
            int repaired = 0;
            for (UnitNode node : all) {
                if (UnitIndexingPolicy.isSearchable(node.getStatus()) && !isIndexed(node.getUnitId())) {
                    indexToQdrant(node, 2);
                    repaired++;
                }
            }
            if (repaired > 0) {
                log.info("🧹 Unit 补偿任务：补写 {} 个缺失的 Qdrant 索引", repaired);
            }
        } catch (Exception e) {
            log.warn("⚠️ Unit 补偿任务失败: {}", e.getMessage());
        }
    }
}
