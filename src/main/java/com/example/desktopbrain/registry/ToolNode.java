package com.example.desktopbrain.registry;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * Neo4j 图谱节点：工具实体。
 * 与 ToolModel 一一对应，存储工具的完整元数据。
 */
@Node("Tool")
public class ToolNode {

    @Id
    private String id;

    /** 工具调用名称 */
    private String name;

    /** 功能描述 */
    private String description;

    /** 类型：JAVA / MCP / GENERATED */
    private String type;

    /** 来源：JAVA=类名, MCP=Server名 */
    private String source;

    /** 参数列表 JSON：[{"name":"path","type":"String","required":true,...}] */
    private String parametersJson;

    /** 返回类型 */
    private String returnType;

    /** 状态：ACTIVE / DEPRECATED / DISABLED */
    private String status;

    /** 当前是否可调用 */
    private boolean callable;

    /** 创建时间 */
    private Long createdAt;

    /** 最后更新时间 */
    private Long updatedAt;

    /** 关联分类 */
    @Relationship(type = "BELONGS_TO", direction = Relationship.Direction.OUTGOING)
    private Set<ToolCategoryNode> categories = new HashSet<>();

    public ToolNode() {}

    public ToolNode(String id, String name, String description, String type,
                     String source, String parametersJson, String returnType) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.type = type;
        this.source = source;
        this.parametersJson = parametersJson;
        this.returnType = returnType;
        this.status = "ACTIVE";
        this.callable = true;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    // ===== getters/setters =====

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getParametersJson() { return parametersJson; }
    public void setParametersJson(String parametersJson) { this.parametersJson = parametersJson; }

    public String getReturnType() { return returnType; }
    public void setReturnType(String returnType) { this.returnType = returnType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public boolean isCallable() { return callable; }
    public void setCallable(boolean callable) { this.callable = callable; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }

    public Set<ToolCategoryNode> getCategories() { return categories; }
    public void setCategories(Set<ToolCategoryNode> categories) { this.categories = categories; }
}
