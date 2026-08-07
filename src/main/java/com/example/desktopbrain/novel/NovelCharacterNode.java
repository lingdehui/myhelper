package com.example.desktopbrain.novel;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * 小说人物节点。按 novelName 隔离，不污染已有的 Device/UserPreference 图。
 */
@Node("NovelCharacter")
public class NovelCharacterNode {

    @Id
    @GeneratedValue
    private Long id;

    private String novelName;     // 小说名（namespace 隔离键）
    private String name;          // 人物名
    private String role;          // 主角/配角/反派/路人
    private String personality;   // 性格描述
    private String appearance;    // 外貌描述
    private String background;    // 背景故事
    private String status;        // ALIVE/DEAD/ABSENT/INACTIVE
    private Long createdAt;

    @Relationship(type = "RELATED_TO", direction = Relationship.Direction.OUTGOING)
    private Set<NovelRelationship> relationships = new HashSet<>();

    public NovelCharacterNode() {}

    public NovelCharacterNode(String novelName, String name, String role, String personality) {
        this.novelName = novelName;
        this.name = name;
        this.role = role;
        this.personality = personality;
        this.status = "ALIVE";
        this.createdAt = System.currentTimeMillis();
    }

    // getters/setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNovelName() { return novelName; }
    public void setNovelName(String novelName) { this.novelName = novelName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getPersonality() { return personality; }
    public void setPersonality(String personality) { this.personality = personality; }
    public String getAppearance() { return appearance; }
    public void setAppearance(String appearance) { this.appearance = appearance; }
    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Set<NovelRelationship> getRelationships() { return relationships; }
    public void setRelationships(Set<NovelRelationship> relationships) { this.relationships = relationships; }
}
