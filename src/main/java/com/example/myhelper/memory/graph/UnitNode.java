package com.example.myhelper.memory.graph;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.ArrayList;
import java.util.List;

/**
 * Neo4j 图谱节点：统一 Unit（文档 15 v1.7 §4.1）。
 *
 * <p>与 {@link com.example.myhelper.memory.unit.Unit} 一一对应。
 * 复杂字段（notes/params/outputSignature/context引用/failureCauses/explorationRecords）
 * 以 JSON 字符串存储，序列化/反序列化由 Service 层负责。</p>
 */
@Node("Unit")
public class UnitNode {

    @Id
    private String unitId;

    /** 类型：TOOL / PLAN_STEP / MCP_TOOL */
    private String unitKind;

    /** 匹配语言字段（embedding 只对此字段生成向量） */
    private String matchText;

    private String goal;
    private String description;

    /** List&lt;String&gt; JSON */
    private String notesJson;

    /** Map&lt;String,String&gt; JSON */
    private String paramsJson;

    /** Map&lt;String,String&gt; JSON */
    private String outputSignatureJson;

    /** unitKind 为 TOOL/MCP_TOOL 时指向的实际工具名 */
    private String toolName;

    /** DISABLED / LEARNING / ACTIVE / SUSPENDED */
    private String directExecutionStatus;
    private String requiredContextIdsJson;
    private String expectedContextIdsJson;

    private int successCount;
    private int failureCount;
    private double stability;

    /**
     * 经验质量分（0~1）：综合可靠性、样本量、新鲜度与经验完整度。
     * 用于决定 PLAN_STEP 是否可复用，并参与记忆清理排序。
     */
    private double qualityScore;

    /** List&lt;String&gt; JSON（FailureCause causeId 列表） */
    private String failureCausesJson;

    /** List&lt;ExplorationRecord&gt; JSON */
    private String explorationRecordsJson;

    /** 生命周期：ACTIVE / ARCHIVED */
    private String status;

    private Long createdAt;
    private Long updatedAt;

    /** 正向有序引用 */
    @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
    private List<ContainsRelation> contains = new ArrayList<>();

    /** 负向无序禁用 */
    @Relationship(type = "DISABLES", direction = Relationship.Direction.OUTGOING)
    private List<DisablesRelation> disables = new ArrayList<>();

    /** 降级替代 */
    @Relationship(type = "FALLBACK", direction = Relationship.Direction.OUTGOING)
    private List<FallbackRelation> fallback = new ArrayList<>();

    public UnitNode() {}

    public UnitNode(String unitId, String unitKind, String matchText,
                     String goal, String description) {
        this.unitId = unitId;
        this.unitKind = unitKind;
        this.matchText = matchText;
        this.goal = goal;
        this.description = description;
        this.status = "ACTIVE";
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    // ===== getters/setters =====

    public String getUnitId() { return unitId; }
    public void setUnitId(String unitId) { this.unitId = unitId; }

    public String getUnitKind() { return unitKind; }
    public void setUnitKind(String unitKind) { this.unitKind = unitKind; }

    public String getMatchText() { return matchText; }
    public void setMatchText(String matchText) { this.matchText = matchText; }

    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getNotesJson() { return notesJson; }
    public void setNotesJson(String notesJson) { this.notesJson = notesJson; }

    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }

    public String getOutputSignatureJson() { return outputSignatureJson; }
    public void setOutputSignatureJson(String outputSignatureJson) { this.outputSignatureJson = outputSignatureJson; }

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getDirectExecutionStatus() { return directExecutionStatus; }
    public void setDirectExecutionStatus(String directExecutionStatus) { this.directExecutionStatus = directExecutionStatus; }
    public String getRequiredContextIdsJson() { return requiredContextIdsJson; }
    public void setRequiredContextIdsJson(String requiredContextIdsJson) { this.requiredContextIdsJson = requiredContextIdsJson; }
    public String getExpectedContextIdsJson() { return expectedContextIdsJson; }
    public void setExpectedContextIdsJson(String expectedContextIdsJson) { this.expectedContextIdsJson = expectedContextIdsJson; }

    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }

    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int failureCount) { this.failureCount = failureCount; }

    public double getStability() { return stability; }
    public void setStability(double stability) { this.stability = stability; }

    public double getQualityScore() { return qualityScore; }
    public void setQualityScore(double qualityScore) { this.qualityScore = qualityScore; }

    public String getFailureCausesJson() { return failureCausesJson; }
    public void setFailureCausesJson(String failureCausesJson) { this.failureCausesJson = failureCausesJson; }

    public String getExplorationRecordsJson() { return explorationRecordsJson; }
    public void setExplorationRecordsJson(String explorationRecordsJson) { this.explorationRecordsJson = explorationRecordsJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }

    public List<ContainsRelation> getContains() { return contains; }
    public void setContains(List<ContainsRelation> contains) { this.contains = contains; }

    public List<DisablesRelation> getDisables() { return disables; }
    public void setDisables(List<DisablesRelation> disables) { this.disables = disables; }

    public List<FallbackRelation> getFallback() { return fallback; }
    public void setFallback(List<FallbackRelation> fallback) { this.fallback = fallback; }
}
