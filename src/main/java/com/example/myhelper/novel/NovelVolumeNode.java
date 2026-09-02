package com.example.myhelper.novel;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * 小说分卷节点：把长篇大纲按"卷"切分，每卷独立存章节范围、本卷主线、分章简要和伏笔计划。
 *
 * <p>长篇（默认 1000 章）一次性生成大纲太大，按卷返回/存储；每卷对应一段连续章节，
 * 写正文时只注入当前卷的概要，避免 token 爆炸且不崩剧情。</p>
 */
@Node("NovelVolume")
public class NovelVolumeNode {

    @Id
    @GeneratedValue
    private Long id;

    private String novelName;      // 小说名（namespace 隔离键）
    private int volumeNumber;      // 卷序号，从 1 开始
    private String title;          // 卷标题，如 "第一卷：初入都市"
    private int chapterStart;      // 本卷起始章号（含）
    private int chapterEnd;        // 本卷结束章号（含）
    private String mainPlot;       // 本卷主线
    private String chapterOutlines; // 本卷分章简要（每行一章，如 "第1章：xxx"）
    private String foreshadowings; // 本卷埋设/回收的伏笔计划（每行一条，如 "第3章埋：xxx"）
    private String secretList;     // 反泄露清单：本卷禁止提前透露的信息（每行一条，如 "真实身份：第45章前绝不可提"）
    private Long createdAt;
    private Long updatedAt;

    public NovelVolumeNode() {}

    public NovelVolumeNode(String novelName, int volumeNumber, String title,
                           int chapterStart, int chapterEnd,
                           String mainPlot, String chapterOutlines, String foreshadowings) {
        this(novelName, volumeNumber, title, chapterStart, chapterEnd,
                mainPlot, chapterOutlines, foreshadowings, null);
    }

    public NovelVolumeNode(String novelName, int volumeNumber, String title,
                           int chapterStart, int chapterEnd,
                           String mainPlot, String chapterOutlines, String foreshadowings,
                           String secretList) {
        this.novelName = novelName;
        this.volumeNumber = volumeNumber;
        this.title = title;
        this.chapterStart = chapterStart;
        this.chapterEnd = chapterEnd;
        this.mainPlot = mainPlot;
        this.chapterOutlines = chapterOutlines;
        this.foreshadowings = foreshadowings;
        this.secretList = secretList;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getNovelName() { return novelName; }
    public void setNovelName(String novelName) { this.novelName = novelName; }
    public int getVolumeNumber() { return volumeNumber; }
    public void setVolumeNumber(int volumeNumber) { this.volumeNumber = volumeNumber; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public int getChapterStart() { return chapterStart; }
    public void setChapterStart(int chapterStart) { this.chapterStart = chapterStart; }
    public int getChapterEnd() { return chapterEnd; }
    public void setChapterEnd(int chapterEnd) { this.chapterEnd = chapterEnd; }
    public String getMainPlot() { return mainPlot; }
    public void setMainPlot(String mainPlot) { this.mainPlot = mainPlot; }
    public String getChapterOutlines() { return chapterOutlines; }
    public void setChapterOutlines(String chapterOutlines) { this.chapterOutlines = chapterOutlines; }
    public String getForeshadowings() { return foreshadowings; }
    public void setForeshadowings(String foreshadowings) { this.foreshadowings = foreshadowings; }
    public String getSecretList() { return secretList; }
    public void setSecretList(String secretList) { this.secretList = secretList; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
