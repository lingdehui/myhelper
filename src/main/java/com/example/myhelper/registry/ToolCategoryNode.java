package com.example.myhelper.registry;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

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

    public ToolCategoryNode() {}

    public ToolCategoryNode(String name, String displayName, String description, Integer priority) {
        this.name = name;
        this.displayName = displayName;
        this.description = description;
        this.priority = priority;
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
}
