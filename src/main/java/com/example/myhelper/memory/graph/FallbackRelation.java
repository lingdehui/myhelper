package com.example.myhelper.memory.graph;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

/**
 * 降级替代关系：Unit FALLBACK Unit（文档 15 v1.7 §4.2）。
 *
 * <p>此单元失败/被禁用时，改用目标单元（"这个步骤不行，换另一个"）。
 * 仅在被 DISABLES 时启用，且只使用一次，不递归，防循环。</p>
 */
@RelationshipProperties
public class FallbackRelation {

    @RelationshipId
    @GeneratedValue
    private Long id;

    /** 优先级（可选） */
    private Integer priority;

    @TargetNode
    private UnitNode target;

    public FallbackRelation() {}

    public FallbackRelation(Integer priority, UnitNode target) {
        this.priority = priority;
        this.target = target;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public UnitNode getTarget() { return target; }
    public void setTarget(UnitNode target) { this.target = target; }
}
