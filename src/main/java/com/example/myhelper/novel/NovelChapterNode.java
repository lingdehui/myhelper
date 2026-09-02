package com.example.myhelper.novel;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * 小说章节节点。按 novelName 隔离。
 */
@Node("NovelChapter")
public class NovelChapterNode {

    @Id
    @GeneratedValue
    private Long id;

    private String novelName;     // 小说名
    private int chapterNumber;    // 章节序号
    private String title;         // 章节标题
    private String summary;       // 章节摘要（200字以内，喂给下一章当上下文）
    private String content;       // 章节正文
    private String status;        // DRAFT/REVIEWING/PUBLISHED
    private int wordCount;        // 字数
    private String keyEvents;     // 关键事件，逗号分隔
    private String foreshadowings; // 本章埋的伏笔，逗号分隔
    /** 写作前确认的章节计划，供续写、复盘和定向重写使用。 */
    private String chapterPlan;
    /** 最近一次通过质量门禁时的分数；手动导入的旧章节可以为空。 */
    private Integer qualityScore;
    private Long createdAt;
    private Long updatedAt;

    public NovelChapterNode() {}

    public NovelChapterNode(String novelName, int chapterNumber, String title, String content) {
        this.novelName = novelName;
        this.chapterNumber = chapterNumber;
        this.title = title;
        this.content = content;
        this.status = "DRAFT";
        this.wordCount = content != null ? content.length() : 0;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    public Long getId() { return id; }
    public String getNovelName() { return novelName; }
    public void setNovelName(String novelName) { this.novelName = novelName; }
    public int getChapterNumber() { return chapterNumber; }
    public void setChapterNumber(int chapterNumber) { this.chapterNumber = chapterNumber; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getWordCount() { return wordCount; }
    public void setWordCount(int wordCount) { this.wordCount = wordCount; }
    public String getKeyEvents() { return keyEvents; }
    public void setKeyEvents(String keyEvents) { this.keyEvents = keyEvents; }
    public String getForeshadowings() { return foreshadowings; }
    public void setForeshadowings(String foreshadowings) { this.foreshadowings = foreshadowings; }
    public String getChapterPlan() { return chapterPlan; }
    public void setChapterPlan(String chapterPlan) { this.chapterPlan = chapterPlan; }
    public Integer getQualityScore() { return qualityScore; }
    public void setQualityScore(Integer qualityScore) { this.qualityScore = qualityScore; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
