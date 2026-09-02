package com.example.myhelper.world;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

@Node("WorldObject")
public class WorldObjectNode {
    @Id private String objectId;
    private String objectType;
    private String name;
    private String description;
    private String parentId;
    private String componentIdsJson;
    private String attributesJson;
    private Long createdAt;
    private Long updatedAt;

    public WorldObjectNode() {}

    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }
    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public String getComponentIdsJson() { return componentIdsJson; }
    public void setComponentIdsJson(String componentIdsJson) { this.componentIdsJson = componentIdsJson; }
    public String getAttributesJson() { return attributesJson; }
    public void setAttributesJson(String attributesJson) { this.attributesJson = attributesJson; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
