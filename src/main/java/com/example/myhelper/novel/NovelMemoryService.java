package com.example.myhelper.novel;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 小说统一内存层：封装 Neo4j（人物/章节/情节）+ Qdrant（章节向量检索）。
 * 所有方法按 novelName 隔离，不可见已有 Device/UserPreference 数据。
 */
@Service
public class NovelMemoryService {

    private final NovelCharacterRepository characterRepo;
    private final NovelChapterRepository chapterRepo;
    private final NovelPlotThreadRepository plotThreadRepo;
    private final NovelVectorMemoryService vectorMemory;

    public NovelMemoryService(NovelCharacterRepository characterRepo,
                               NovelChapterRepository chapterRepo,
                               NovelPlotThreadRepository plotThreadRepo,
                               NovelVectorMemoryService vectorMemory) {
        this.characterRepo = characterRepo;
        this.chapterRepo = chapterRepo;
        this.plotThreadRepo = plotThreadRepo;
        this.vectorMemory = vectorMemory;
    }

    // ========== 人物管理 ==========

    public NovelCharacterNode addCharacter(String novelName, String name, String role, String personality,
                                            String appearance, String background) {
        NovelCharacterNode c = new NovelCharacterNode(novelName, name, role, personality);
        c.setAppearance(appearance);
        c.setBackground(background);
        return characterRepo.save(c);
    }

    public void addRelationship(String novelName, String charA, String charB, String type, String description) {
        var a = characterRepo.findByNovelNameAndName(novelName, charA);
        var b = characterRepo.findByNovelNameAndName(novelName, charB);
        if (a.isPresent() && b.isPresent()) {
            NovelCharacterNode nodeA = a.get();
            nodeA.getRelationships().add(new NovelRelationship(b.get(), type, description));
            characterRepo.save(nodeA);
        }
    }

    public void updateCharacter(String novelName, String name, String personality, String appearance, String background) {
        characterRepo.findByNovelNameAndName(novelName, name).ifPresent(c -> {
            if (personality != null) c.setPersonality(personality);
            if (appearance != null) c.setAppearance(appearance);
            if (background != null) c.setBackground(background);
            characterRepo.save(c);
        });
    }

    /** 变更人物状态（持久化到 Neo4j） */
    public boolean updateCharacterStatus(String novelName, String name, String newStatus) {
        var opt = characterRepo.findByNovelNameAndName(novelName, name);
        if (opt.isPresent()) {
            NovelCharacterNode c = opt.get();
            c.setStatus(newStatus);
            characterRepo.save(c);
            return true;
        }
        return false;
    }

    public void updateRelationship(String novelName, String charA, String charB, String newType, String newDescription) {
        var a = characterRepo.findByNovelNameAndName(novelName, charA);
        if (a.isPresent()) {
            NovelCharacterNode nodeA = a.get();
            for (NovelRelationship rel : nodeA.getRelationships()) {
                if (rel.getTarget().getName().equals(charB)) {
                    if (newType != null) rel.setType(newType);
                    if (newDescription != null) rel.setDescription(newDescription);
                    break;
                }
            }
            characterRepo.save(nodeA);
        }
    }

    public List<NovelCharacterNode> getCharacters(String novelName) {
        return characterRepo.findByNovelName(novelName);
    }

    public String getCharactersAsPrompt(String novelName) {
        List<NovelCharacterNode> chars = characterRepo.findByNovelName(novelName);
        if (chars.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【人物设定】\n");
        for (NovelCharacterNode c : chars) {
            sb.append("- ").append(c.getName()).append("（").append(c.getRole()).append("）: ")
              .append(c.getPersonality());
            if (c.getAppearance() != null) sb.append("，外貌: ").append(c.getAppearance());
            if (c.getBackground() != null) sb.append("，背景: ").append(c.getBackground());
            sb.append("，状态: ").append(c.getStatus()).append("\n");
        }
        return sb.toString();
    }

    // ========== 章节管理 ==========

    public NovelChapterNode addChapter(String novelName, int chapterNumber, String title, String content) {
        NovelChapterNode ch = new NovelChapterNode(novelName, chapterNumber, title, content);
        return chapterRepo.save(ch);
    }

    public void setChapterSummary(String novelName, int chapterNumber, String summary,
                                   String characters, String plotThreads) {
        chapterRepo.findByNovelNameAndNumber(novelName, chapterNumber).ifPresent(ch -> {
            ch.setSummary(summary);
            ch.setKeyEvents(summary.length() > 100 ? summary.substring(0, 100) : summary);
            chapterRepo.save(ch);
        });
        vectorMemory.saveChapterSummary(novelName, chapterNumber, summary, characters, plotThreads);
    }

    public long getChapterCount(String novelName) {
        return chapterRepo.countByNovelName(novelName);
    }

    public Optional<NovelChapterNode> getLatestChapter(String novelName) {
        return chapterRepo.findLatestChapter(novelName);
    }

    public Optional<NovelChapterNode> getChapter(String novelName, int chapterNumber) {
        return chapterRepo.findByNovelNameAndNumber(novelName, chapterNumber);
    }

    public void updateChapter(String novelName, int chapterNumber, String content) {
        chapterRepo.findByNovelNameAndNumber(novelName, chapterNumber).ifPresent(ch -> {
            ch.setContent(content);
            ch.setWordCount(content != null ? content.length() : 0);
            ch.setUpdatedAt(System.currentTimeMillis());
            chapterRepo.save(ch);
        });
    }

    public String getRecentSummariesAsPrompt(String novelName, int count) {
        List<NovelChapterNode> chapters = chapterRepo.findByNovelName(novelName);
        int total = chapters.size();
        if (total == 0) return "";
        int start = Math.max(0, total - count);
        StringBuilder sb = new StringBuilder("【前文摘要】\n");
        for (int i = start; i < total; i++) {
            NovelChapterNode ch = chapters.get(i);
            sb.append("第").append(ch.getChapterNumber()).append("章 ")
              .append(ch.getTitle()).append(": ");
            if (ch.getSummary() != null && !ch.getSummary().isEmpty()) {
                sb.append(ch.getSummary());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public List<NovelChapterNode> getAllChapters(String novelName) {
        return chapterRepo.findByNovelName(novelName);
    }

    // ========== 情节线管理 ==========

    public NovelPlotThreadNode addPlotThread(String novelName, String threadName, String type,
                                              String description, int plantedChapter) {
        NovelPlotThreadNode pt = new NovelPlotThreadNode(novelName, threadName, type, description, plantedChapter);
        return plotThreadRepo.save(pt);
    }

    public void resolvePlotThread(String novelName, String threadName, int resolvedChapter) {
        NovelPlotThreadNode pt = plotThreadRepo.findByName(novelName, threadName);
        if (pt != null) {
            pt.setStatus("RESOLVED");
            pt.setResolvedChapter(resolvedChapter);
            plotThreadRepo.save(pt);
        }
    }

    public List<NovelPlotThreadNode> getUnresolvedThreads(String novelName) {
        return plotThreadRepo.findUnresolvedThreads(novelName);
    }

    public String getUnresolvedThreadsAsPrompt(String novelName) {
        List<NovelPlotThreadNode> threads = plotThreadRepo.findUnresolvedThreads(novelName);
        if (threads.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【未回收伏笔（写作时需注意）】\n");
        for (NovelPlotThreadNode t : threads) {
            sb.append("- [").append(t.getStatus()).append("] ").append(t.getThreadName())
              .append(": ").append(t.getDescription())
              .append("（埋于第").append(t.getPlantedChapter()).append("章）\n");
        }
        return sb.toString();
    }

    // ========== 状态总览 ==========

    public String getNovelStateAsPrompt(String novelName) {
        long chapterCount = chapterRepo.countByNovelName(novelName);
        long characterCount = characterRepo.findByNovelName(novelName).size();
        long unresolvedThreads = plotThreadRepo.findUnresolvedThreads(novelName).size();
        return String.format("【小说状态】%s | 已写 %d 章 | %d 个人物 | %d 条未回收伏笔",
                novelName, chapterCount, characterCount, unresolvedThreads);
    }

    // ========== 向量检索：从 Qdrant 找最相关的已有章节上下文 ==========

    public String searchRelevantContext(String novelName, String query) {
        List<Map<String, Object>> results = vectorMemory.searchRelevantContext(novelName, query, 3);
        if (results.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【相关章节上下文】\n");
        for (Map<String, Object> r : results) {
            sb.append("- 第").append(r.get("chapterNumber")).append("章: ")
              .append(r.get("summary")).append("\n");
        }
        return sb.toString();
    }

    // ========== 一键写新章上下文 ==========

    /**
     * 组装写下一章所需的完整上下文：人物设定 + 最近章节摘要 + 未回收伏笔 + 总状态。
     * 这是 AI 写每章前最核心的一步。
     */
    public String buildWritingContext(String novelName) {
        StringBuilder ctx = new StringBuilder();

        // 1. 总状态
        long chapterCount = chapterRepo.countByNovelName(novelName);
        int nextChapter = (int) chapterCount + 1;
        ctx.append(String.format("【写作任务】现在写《%s》第%d章\n\n", novelName, nextChapter));

        // 2. 人物设定
        String characters = getCharactersAsPrompt(novelName);
        if (!characters.isEmpty()) ctx.append(characters).append("\n");

        // 3. 最近N章摘要
        String summaries = getRecentSummariesAsPrompt(novelName, 5);
        if (!summaries.isEmpty()) ctx.append(summaries).append("\n");

        // 4. 未回收伏笔（提醒）
        String threads = getUnresolvedThreadsAsPrompt(novelName);
        if (!threads.isEmpty()) ctx.append(threads).append("\n");

        ctx.append(String.format("【进度】已写 %d 章，请续写第 %d 章。字数建议 2000-4000 字，保持风格一致。",
                chapterCount, nextChapter));
        return ctx.toString();
    }
}
