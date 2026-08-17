package com.example.myhelper.novel;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * 小说大纲节点：写正文前先定结构（世界观 + 主线 + 分章简要）。
 *
 * <p>大纲是"结构化记忆"，存 Neo4j + 向量化进 Qdrant；正文另落文件。</p>
 */
@Node("NovelOutline")
public class NovelOutlineNode {

    @Id
    @GeneratedValue
    private Long id;

    private String novelName;      // 小说名（namespace 隔离键）
    private String worldview;      // 世界观/设定
    private String mainPlot;       // 主线大纲（粗大纲）
    private int totalChapters = 1000;  // 预设总章数（默认 1000）
    private String chapterOutlines; // 整体分章简要（不分卷时用；分卷时各卷存 NovelVolume）
    private Long createdAt;
    private Long updatedAt;

    public NovelOutlineNode() {}

    public NovelOutlineNode(String novelName, String worldview, String mainPlot, int totalChapters) {
        this.novelName = novelName;
        this.worldview = worldview;
        this.mainPlot = mainPlot;
        this.totalChapters = totalChapters;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNovelName() { return novelName; }
    public void setNovelName(String novelName) { this.novelName = novelName; }
    public String getWorldview() { return worldview; }
    public void setWorldview(String worldview) { this.worldview = worldview; }
    public String getMainPlot() { return mainPlot; }
    public void setMainPlot(String mainPlot) { this.mainPlot = mainPlot; }
    public int getTotalChapters() { return totalChapters; }
    public void setTotalChapters(int totalChapters) { this.totalChapters = totalChapters; }
    public String getChapterOutlines() { return chapterOutlines; }
    public void setChapterOutlines(String chapterOutlines) { this.chapterOutlines = chapterOutlines; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
