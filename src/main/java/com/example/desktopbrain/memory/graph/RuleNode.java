package com.example.desktopbrain.memory.graph;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

import java.util.List;

/**
 * Neo4j 通用规则节点。
 *
 * <p>从大量 Episode/FailurePattern 中跨案例归纳出的抽象规则，
 * 注入到 AI 规划 System Prompt 中，让 AI 在规划阶段就能主动避坑。</p>
 *
 * <p>与 FailurePattern 的区别：
 * FailurePattern 是"某类操作最近频繁失败"的聚合事实，
 * Rule 是跨案例泛化的"应该怎么做/不应该怎么做"的通用准则。</p>
 */
@Node("Rule")
public class RuleNode {

    @Id @GeneratedValue
    private Long id;

    /** 规则摘要（一行人类可读描述） */
    private String summary;

    /** 置信度 0.0-1.0 */
    private double confidence;

    /** 来源类型：from_failure_pattern / from_success_episode */
    private String source;

    /** 关联的工具名列表（逗号分隔） */
    private String relatedTools;

    /** 创建时间戳 */
    private long created;

    /** 是否启用（可手动禁用低质量规则） */
    private boolean enabled;

    public RuleNode() {}

    public RuleNode(String summary, double confidence, String source,
                     List<String> relatedTools, long created) {
        this.summary = summary;
        this.confidence = confidence;
        this.source = source;
        this.relatedTools = relatedTools != null ? String.join(",", relatedTools) : "";
        this.created = created;
        this.enabled = true;
    }

    // getters / setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getRelatedTools() { return relatedTools; }
    public void setRelatedTools(String relatedTools) { this.relatedTools = relatedTools; }

    public long getCreated() { return created; }
    public void setCreated(long created) { this.created = created; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
