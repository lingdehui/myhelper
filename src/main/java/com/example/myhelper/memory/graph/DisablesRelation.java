package com.example.myhelper.memory.graph;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

/**
 * 负向无序禁用关系：Unit DISABLES Unit（文档 15 v1.7 §4.2）。
 *
 * <p>失败经验固化为图上的"禁用边"，复用计划时自动跳过被禁用的目标。
 * 挂在所有层（Plan→Step、Step→子Step、Step→Tool），但不挂 MCP 工具上。</p>
 */
@RelationshipProperties
public class DisablesRelation {

    @RelationshipId
    @GeneratedValue
    private Long id;

    /** 禁用原因 */
    private String reason;

    /** 累计失败次数 */
    private int failCount;

    /** 禁用条件（触发禁用的环境/上下文描述，可为空） */
    private String condition;

    @TargetNode
    private UnitNode target;

    public DisablesRelation() {}

    public DisablesRelation(String reason, int failCount, String condition, UnitNode target) {
        this.reason = reason;
        this.failCount = failCount;
        this.condition = condition;
        this.target = target;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public int getFailCount() { return failCount; }
    public void setFailCount(int failCount) { this.failCount = failCount; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    public UnitNode getTarget() { return target; }
    public void setTarget(UnitNode target) { this.target = target; }
}
