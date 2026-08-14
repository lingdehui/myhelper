package com.example.myhelper.novel;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * 小说情节线节点。追踪伏笔和支线进度。
 */
@Node("NovelPlotThread")
public class NovelPlotThreadNode {

    @Id
    @GeneratedValue
    private Long id;

    private String novelName;     // 小说名
    private String threadName;    // 情节点名称（如 "张三身世之谜"）
    private String type;          // MAIN/A_SUB/B_SUB/FORESHADOW
    private String status;        // PLANTED/DEVELOPING/RESOLVED
    private String description;   // 情节描述
    private int plantedChapter;   // 伏笔埋设章节
    private int resolvedChapter;  // 伏笔回收章节（-1 未回收）
    private Long createdAt;

    public NovelPlotThreadNode() {}

    public NovelPlotThreadNode(String novelName, String threadName, String type, String description, int plantedChapter) {
        this.novelName = novelName;
        this.threadName = threadName;
        this.type = type;
        this.description = description;
        this.plantedChapter = plantedChapter;
        this.status = "PLANTED";
        this.resolvedChapter = -1;
        this.createdAt = System.currentTimeMillis();
    }

    public Long getId() { return id; }
    public String getNovelName() { return novelName; }
    public void setNovelName(String novelName) { this.novelName = novelName; }
    public String getThreadName() { return threadName; }
    public void setThreadName(String threadName) { this.threadName = threadName; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getPlantedChapter() { return plantedChapter; }
    public void setPlantedChapter(int plantedChapter) { this.plantedChapter = plantedChapter; }
    public int getResolvedChapter() { return resolvedChapter; }
    public void setResolvedChapter(int resolvedChapter) { this.resolvedChapter = resolvedChapter; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
