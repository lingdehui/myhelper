package com.example.myhelper.memory.graph;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * Neo4j 失败模式节点。
 *
 * <p>存储高频失败模式，供知识图谱查询和关联分析。
 * 与 Qdrant failure-patterns collection 并行存储（双库）。</p>
 */
@Node("FailurePattern")
public class FailurePatternNode {

    @Id @GeneratedValue
    private Long id;

    private String type;
    private String description;
    private String mitigation;
    private int count;
    private long detectedAt;

    public FailurePatternNode() {}

    public FailurePatternNode(String type, String description, String mitigation,
                               int count, long detectedAt) {
        this.type = type;
        this.description = description;
        this.mitigation = mitigation;
        this.count = count;
        this.detectedAt = detectedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getMitigation() { return mitigation; }
    public void setMitigation(String mitigation) { this.mitigation = mitigation; }

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }

    public long getDetectedAt() { return detectedAt; }
    public void setDetectedAt(long detectedAt) { this.detectedAt = detectedAt; }
}
