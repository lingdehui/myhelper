package com.example.myhelper.memory.graph;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * Neo4j 图谱节点：失败原因独立对象（文档 15 v1.7 §4.1 / §5）。
 *
 * <p>与 {@link com.example.myhelper.memory.unit.FailureCause} 一一对应。
 * {@code suggestedUnitIds} 以 JSON 字符串存储，不建独立实体。
 * 本节点与 Unit 之间通过 {@code failureCausesJson} 中的 causeId 字符串引用关联，
 * 不建立独立关系边（§4.2 仅定义 CONTAINS / DISABLES / FALLBACK）。</p>
 */
@Node("FailureCause")
public class FailureCauseNode {

    @Id
    private String causeId;

    /** 网络快照 */
    private String network;

    /** 环境快照 */
    private String environment;

    /** 失败原因描述 */
    private String reason;

    /** 失败时入参快照 */
    private String inputArgs;

    /** AI 分析结论 */
    private String analysis;

    /** 归类：PLAN / ENVIRONMENT */
    private String category;

    private long timestamp;

    /** List&lt;String&gt; JSON（建议替代 Unit 的 unitId 列表，不建独立实体） */
    private String suggestedUnitIdsJson;

    public FailureCauseNode() {}

    public FailureCauseNode(String causeId, String network, String environment,
                            String reason, String inputArgs, String analysis,
                            String category, long timestamp) {
        this.causeId = causeId;
        this.network = network;
        this.environment = environment;
        this.reason = reason;
        this.inputArgs = inputArgs;
        this.analysis = analysis;
        this.category = category;
        this.timestamp = timestamp;
    }

    // ===== getters/setters =====

    public String getCauseId() { return causeId; }
    public void setCauseId(String causeId) { this.causeId = causeId; }

    public String getNetwork() { return network; }
    public void setNetwork(String network) { this.network = network; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getInputArgs() { return inputArgs; }
    public void setInputArgs(String inputArgs) { this.inputArgs = inputArgs; }

    public String getAnalysis() { return analysis; }
    public void setAnalysis(String analysis) { this.analysis = analysis; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getSuggestedUnitIdsJson() { return suggestedUnitIdsJson; }
    public void setSuggestedUnitIdsJson(String suggestedUnitIdsJson) { this.suggestedUnitIdsJson = suggestedUnitIdsJson; }
}
