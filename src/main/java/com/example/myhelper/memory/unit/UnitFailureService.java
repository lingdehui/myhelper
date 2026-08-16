package com.example.myhelper.memory.unit;

import com.example.myhelper.common.AiResponseUtils;
import com.example.myhelper.config.SystemEnvironmentService;
import com.example.myhelper.memory.graph.FailureCauseNode;
import com.example.myhelper.memory.graph.FailureCauseRepository;
import com.example.myhelper.memory.graph.UnitNode;
import com.example.myhelper.memory.graph.UnitRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Unit 失败处理服务（文档 15 v1.7 §5 / §6）。
 *
 * <p>把失败经验迁移到图：失败原因独立为 FailureCause 节点（复用），
 * 关联到失败 Unit；PLAN 原因累计失败达阈值 → 归档 Unit；ENVIRONMENT 只写 notes 不惩罚。</p>
 */
@Service
public class UnitFailureService {

    private static final Logger log = LoggerFactory.getLogger(UnitFailureService.class);

    private final FailureCauseRepository failureCauseRepository;
    private final UnitRepository unitRepository;
    private final UnitStore unitStore;
    private final SystemEnvironmentService envService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${myhelper.memory.failure-threshold:3}")
    private int failureThreshold;

    public UnitFailureService(FailureCauseRepository failureCauseRepository,
                              UnitRepository unitRepository,
                              UnitStore unitStore,
                              SystemEnvironmentService envService) {
        this.failureCauseRepository = failureCauseRepository;
        this.unitRepository = unitRepository;
        this.unitStore = unitStore;
        this.envService = envService;
    }

    /**
     * 记录一次失败。
     *
     * @param unitId        失败所在的 Unit id（可为 null，仅记录独立 FailureCause）
     * @param failureLesson 失败教训（AI 归因）
     * @param isPlanIssue   是否计划问题（true=惩罚 / false=环境问题不惩罚）
     * @param inputArgs     失败时入参快照（可空）
     */
    public String recordFailure(String unitId, String failureLesson, boolean isPlanIssue, String inputArgs) {
        String causeId = upsertFailureCause(failureLesson, isPlanIssue, inputArgs);

        if (unitId == null) {
            return causeId;
        }

        Optional<UnitNode> opt = unitRepository.findByUnitId(unitId);
        if (opt.isEmpty()) {
            return causeId;
        }
        UnitNode node = opt.get();

        // 关联 FailureCause 引用
        List<String> causes = parseList(node.getFailureCausesJson());
        if (!causes.contains(causeId)) {
            causes.add(causeId);
        }
        node.setFailureCausesJson(toJson(causes));

        boolean mcpTool = "MCP_TOOL".equalsIgnoreCase(node.getUnitKind());

        // ENVIRONMENT：只写 notes，不惩罚
        if (!isPlanIssue) {
            List<String> notes = parseList(node.getNotesJson());
            if (failureLesson != null && !failureLesson.isBlank()) {
                notes.add("环境失败: " + failureLesson);
            }
            node.setNotesJson(toJson(notes));
            unitRepository.save(node);
            log.info("ℹ️ Unit 环境失败（不惩罚，id={}...）", shortId(unitId));
            return causeId;
        }

        // MCP 工具保护：失败只记录，不计数、不归档、不禁用、无 FALLBACK（§6）
        if (mcpTool) {
            unitRepository.save(node);
            log.info("🛡️ MCP 工具失败仅记录（不惩罚，id={}...）", shortId(unitId));
            return causeId;
        }

        int failure = node.getFailureCount() + 1;
        node.setFailureCount(failure);
        node.setStability(Unit.calcStability(node.getSuccessCount(), failure));
        boolean archive = failure >= failureThreshold;
        if (archive) {
            node.setStatus("ARCHIVED");
        }
        unitRepository.save(node);
        log.info("⚠️ Unit 计划失败+1（id={}...，failure={}/{}{}）",
                shortId(unitId), failure, failureThreshold, archive ? "，已归档" : "");

        // 达阈值：归档（逻辑删除）后移除语义检索入口，并给所有父级建 DISABLES 边（§6）
        if (archive) {
            unitStore.unindex(unitId);
            for (String parentId : unitRepository.findParentsOf(unitId)) {
                unitStore.linkDisables(parentId, unitId,
                        AiResponseUtils.truncate(failureLesson, 200), failure, null);
            }
        }
        return causeId;
    }

    // ========================================================================
    // 失败原因检索（指向 FailureCause 图，替代旧独立 FailurePattern 文本库）
    // ========================================================================

    /**
     * 检索失败原因（文档 15 v1.7 §5 / §10）。
     *
     * <p>FailureCause 只在 Neo4j 图中（Qdrant 只索引 Unit.matchText，§10），
     * 因此这里从图里拉取并按时间倒序取最近 {@code topK} 条，由调用方（AI）判断相关性，
     * 避免硬编码关键词命中率低的问题。</p>
     */
    public List<FailureCause> searchFailureCauses(String query, int topK) {
        List<FailureCause> recent = getRecentFailureCauses(topK);
        if (recent.isEmpty()) {
            log.info("🔍 失败原因检索 '{}' → 无结果", query);
        } else {
            log.info("🔍 失败原因检索 '{}' → {} 条", query, recent.size());
        }
        return recent;
    }

    /** 拉取最近 N 条失败原因（按发生时间倒序，供探索/规划上下文注入）。 */
    public List<FailureCause> getRecentFailureCauses(int limit) {
        List<FailureCauseNode> all = new ArrayList<>();
        failureCauseRepository.findAll().forEach(all::add);
        all.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
        return all.stream().limit(Math.max(1, Math.min(limit, 30)))
                .map(this::toFailureCause)
                .collect(java.util.stream.Collectors.toList());
    }

    /** FailureCauseNode → FailureCause record。 */
    private FailureCause toFailureCause(FailureCauseNode node) {
        FailureCause.FailureCategory category = parseCategory(node.getCategory());
        return new FailureCause(
                node.getCauseId(),
                node.getNetwork(),
                node.getEnvironment(),
                node.getReason(),
                node.getInputArgs(),
                node.getAnalysis(),
                category,
                node.getTimestamp(),
                parseSuggestedUnitIds(node.getSuggestedUnitIdsJson()));
    }

    private FailureCause.FailureCategory parseCategory(String category) {
        if (category == null) return FailureCause.FailureCategory.ENVIRONMENT;
        try {
            return FailureCause.FailureCategory.valueOf(category);
        } catch (IllegalArgumentException e) {
            return FailureCause.FailureCategory.ENVIRONMENT;
        }
    }

    private List<String> parseSuggestedUnitIds(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<String> v = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return v == null ? new ArrayList<>() : v;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** FailureCause 复用：按归一化 reason 查库，有则引用，无则新建。 */
    private String upsertFailureCause(String failureLesson, boolean isPlanIssue, String inputArgs) {
        String reason = failureLesson == null || failureLesson.isBlank()
                ? "未知失败" : AiResponseUtils.truncate(failureLesson, 100);

        Optional<FailureCauseNode> existing = failureCauseRepository.findByReason(reason);
        if (existing.isPresent()) {
            return existing.get().getCauseId();
        }

        String causeId = UUID.randomUUID().toString();
        String category = isPlanIssue ? "PLAN" : "ENVIRONMENT";
        FailureCauseNode node = new FailureCauseNode(
                causeId, snapshotNetwork(), envService.getEnvironmentKey(),
                reason, inputArgs, reason, category, System.currentTimeMillis());
        // 补全字段，避免 Neo4j 属性缺失导致 UnknownPropertyWarning 刷屏
        node.setInputArgs(inputArgs == null ? "" : inputArgs);
        node.setSuggestedUnitIdsJson("[]");
        failureCauseRepository.save(node);
        return causeId;
    }

    /** §5：网络快照（本机 hostname，采集失败时返回 null）。 */
    private String snapshotNetwork() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return null;
        }
    }

    // ===== JSON 辅助 =====

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<String> v = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return v == null ? new ArrayList<>() : v;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String shortId(String id) {
        return id == null ? "null" : id.substring(0, Math.min(8, id.length())) + "...";
    }
}
