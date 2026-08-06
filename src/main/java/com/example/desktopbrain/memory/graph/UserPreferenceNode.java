package com.example.desktopbrain.memory.graph;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * 知识图谱节点：用户偏好与习惯
 * 记录用户对设备的使用偏好、日常习惯等
 */
@Node("UserPreference")
public class UserPreferenceNode {

    @Id
    @GeneratedValue
    private Long id;

    /** 偏好类型：temperature / brightness / routine / scene */
    private String category;

    /** 偏好键名 */
    private String key;

    /** 偏好值 */
    private String value;

    /** 触发条件（如 "时间=22:00"、"用户=回家"） */
    private String trigger;

    /** 置信度 0-1，越高越确定 */
    private double confidence;

    /** 记录时间 */
    private Long createdAt;

    public UserPreferenceNode() {}

    public UserPreferenceNode(String category, String key, String value) {
        this.category = category;
        this.key = key;
        this.value = value;
        this.confidence = 0.5;
        this.createdAt = System.currentTimeMillis();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}