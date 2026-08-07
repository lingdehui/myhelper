package com.example.desktopbrain.novel;

import org.springframework.data.neo4j.core.schema.RelationshipId;
import org.springframework.data.neo4j.core.schema.RelationshipProperties;
import org.springframework.data.neo4j.core.schema.TargetNode;

/**
 * 小说人物关系（Neo4j Relationship）。
 * 存为独立的 Relationship entity 方便 Cypher 查询。
 */
@RelationshipProperties
public class NovelRelationship {

    @RelationshipId
    @org.springframework.data.neo4j.core.schema.GeneratedValue
    private Long id;

    private String type;          // 暗恋/恋爱/夫妻/敌对/师徒/父子/朋友
    private String description;   // 关系描述
    private String status;        // ACTIVE/ENDING/BROKEN
    private Long since;           // 关系开始时间（在哪一章建立）

    @TargetNode
    private NovelCharacterNode target;

    public NovelRelationship() {}

    public NovelRelationship(NovelCharacterNode target, String type, String description) {
        this.target = target;
        this.type = type;
        this.description = description;
        this.status = "ACTIVE";
    }

    public Long getId() { return id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getSince() { return since; }
    public void setSince(Long since) { this.since = since; }
    public NovelCharacterNode getTarget() { return target; }
    public void setTarget(NovelCharacterNode target) { this.target = target; }
}
