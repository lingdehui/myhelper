package com.example.myhelper.memory.graph;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

/**
 * 正向有序引用关系：Unit CONTAINS Unit（文档 15 v1.7 §4.2）。
 *
 * <p>表达"包含"：Plan→Step、Step→子Step、Step→Tool，按 {@code order} 顺序执行。</p>
 */
@RelationshipProperties
public class ContainsRelation {

    @RelationshipId
    @GeneratedValue
    private Long id;

    /** 执行顺序（1、2、3、4） */
    private int order;

    /** 本次子 Unit 调用参数（Base64 编码 JSON，避免查询投影分隔符冲突）。 */
    private String argumentsBase64;

    @TargetNode
    private UnitNode target;

    public ContainsRelation() {}

    public ContainsRelation(int order, UnitNode target) {
        this(order, null, target);
    }

    public ContainsRelation(int order, String argumentsBase64, UnitNode target) {
        this.order = order;
        this.argumentsBase64 = argumentsBase64;
        this.target = target;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public int getOrder() { return order; }
    public void setOrder(int order) { this.order = order; }
    public String getArgumentsBase64() { return argumentsBase64; }
    public void setArgumentsBase64(String argumentsBase64) { this.argumentsBase64 = argumentsBase64; }

    public UnitNode getTarget() { return target; }
    public void setTarget(UnitNode target) { this.target = target; }
}
