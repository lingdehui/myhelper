package com.example.myhelper.registry;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * Neo4j 图谱节点：工具分类。
 */
@Node("ToolCategory")
public class ToolCategoryNode {

    @Id
    private String name;

    /** 显示名称 */
    private String displayName;

    /** 分类描述 */
    private String description;

    /** 排序优先级 */
    private Integer priority;

    /** 是否为动态创建（vs 预定义） */
    private boolean dynamic;

    /** 父分类 id（null=顶级 L1） */
    private String parentId;

    /** 层级：1=L1大类, 2=L2子类 */
    private Integer level;

    /** 该分类下的工具（BELONGS_TO 反向关系，支持交叉分类：一个工具可属于多个分类） */
    @Relationship(type = "BELONGS_TO", direction = Relationship.Direction.INCOMING)
    private Set<ToolNode> tools = new HashSet<>();

    public ToolCategoryNode() {}

    public ToolCategoryNode(String name, String displayName, String description, Integer priority) {
        this.name = name;
        this.displayName = displayName;
        this.description = description;
        this.priority = priority;
    }

    public ToolCategoryNode(String name, String displayName, String description, Integer priority,
                            String parentId, Integer level) {
        this.name = name;
        this.displayName = displayName;
        this.description = description;
        this.priority = priority;
        this.parentId = parentId;
        this.level = level;
    }

    // ===== getters/setters =====

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public boolean isDynamic() { return dynamic; }
    public void setDynamic(boolean dynamic) { this.dynamic = dynamic; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }

    public Set<ToolNode> getTools() { return tools; }
    public void setTools(Set<ToolNode> tools) { this.tools = tools; }
}
