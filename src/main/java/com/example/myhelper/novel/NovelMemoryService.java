package com.example.myhelper.novel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 小说统一内存层：封装 Neo4j（大纲/人物/章节/情节）+ Qdrant（章节/大纲向量检索）。
 * 所有方法按 novelName 隔离，不可见已有 Device/UserPreference 数据。
 * 正文同时落文件（每章一个 .txt），结构化记忆（大纲/人物/关系/情节）存图谱+向量。
 */
@Service
public class NovelMemoryService {

    private static final Logger log = LoggerFactory.getLogger(NovelMemoryService.class);

    private final NovelCharacterRepository characterRepo;
    private final NovelChapterRepository chapterRepo;
    private final NovelPlotThreadRepository plotThreadRepo;
    private final NovelOutlineRepository outlineRepo;
    private final NovelVolumeRepository volumeRepo;
    private final NovelVectorMemoryService vectorMemory;

    @Value("${novel.output-dir:novels}")
    private String outputDir;

    /** 最近活跃的小说名：AI 漏传 novelName 时自动补用，避免更新空转 */
    private volatile String lastActiveNovelName;

    /**
     * 解析小说名：有值则记录为最近活跃并返回；为空则回退到最近活跃的小说。
     * 返回 null 表示无法确定（从未有活跃小说）。
     */
    public String resolveNovelName(String novelName) {
        if (novelName != null && !novelName.isBlank()) {
            this.lastActiveNovelName = novelName;
            return novelName;
        }
        return lastActiveNovelName;
    }

    public NovelMemoryService(NovelCharacterRepository characterRepo,
                               NovelChapterRepository chapterRepo,
                               NovelPlotThreadRepository plotThreadRepo,
                               NovelOutlineRepository outlineRepo,
                               NovelVolumeRepository volumeRepo,
                               NovelVectorMemoryService vectorMemory) {
        this.characterRepo = characterRepo;
        this.chapterRepo = chapterRepo;
        this.plotThreadRepo = plotThreadRepo;
        this.outlineRepo = outlineRepo;
        this.volumeRepo = volumeRepo;
        this.vectorMemory = vectorMemory;
    }

    // ========== 大纲管理 ==========

    /** 保存/更新粗大纲（世界观 + 主线 + 预设总章数），并向量化进 Qdrant。分卷细节用 setVolume。 */
    public NovelOutlineNode setOutline(String novelName, String worldview, String mainPlot, int totalChapters) {
        if (totalChapters <= 0) totalChapters = 1000;  // 未传/非法时兜底默认 1000 章
        Optional<NovelOutlineNode> existing = outlineRepo.findByNovelName(novelName);
        NovelOutlineNode o;
        if (existing.isPresent()) {
            o = existing.get();
            o.setWorldview(worldview);
            o.setMainPlot(mainPlot);
            o.setTotalChapters(totalChapters);
            o.setUpdatedAt(System.currentTimeMillis());
        } else {
            o = new NovelOutlineNode(novelName, worldview, mainPlot, totalChapters);
        }
        NovelOutlineNode saved = outlineRepo.save(o);
        vectorMemory.saveOutline(novelName, buildOutlineText(worldview, mainPlot, totalChapters));
        return saved;
    }

    public Optional<NovelOutlineNode> getOutline(String novelName) {
        return outlineRepo.findByNovelName(novelName);
    }

    private String buildOutlineText(String worldview, String mainPlot, int totalChapters) {
        return "世界观：" + (worldview == null ? "" : worldview)
                + "\n主线：" + (mainPlot == null ? "" : mainPlot)
                + "\n预设总章数：" + totalChapters;
    }

    public String getOutlineAsPrompt(String novelName) {
        return outlineRepo.findByNovelName(novelName)
                .map(o -> {
                    StringBuilder sb = new StringBuilder("【大纲】\n");
                    if (o.getWorldview() != null && !o.getWorldview().isBlank())
                        sb.append("世界观：").append(o.getWorldview()).append("\n");
                    if (o.getMainPlot() != null && !o.getMainPlot().isBlank())
                        sb.append("主线：").append(o.getMainPlot()).append("\n");
                    sb.append("预设总章数：").append(o.getTotalChapters()).append("\n");
                    return sb.toString();
                })
                .orElse("");
    }

    // ========== 分卷管理 ==========

    /** 保存/更新某卷（upsert by novelName + volumeNumber）。长篇大纲按卷切分，一次一卷。 */
    public NovelVolumeNode setVolume(String novelName, int volumeNumber, String title,
                                     int chapterStart, int chapterEnd,
                                     String mainPlot, String chapterOutlines, String foreshadowings,
                                     String secretList) {
        Optional<NovelVolumeNode> existing = volumeRepo.findByNovelNameAndVolumeNumber(novelName, volumeNumber);
        NovelVolumeNode v;
        if (existing.isPresent()) {
            v = existing.get();
            v.setNovelName(novelName);   // 更新时也写回 novelName，防止旧卷缺失该属性导致查不到
            v.setTitle(title);
            v.setChapterStart(chapterStart);
            v.setChapterEnd(chapterEnd);
            v.setMainPlot(mainPlot);
            v.setChapterOutlines(chapterOutlines);
            v.setForeshadowings(foreshadowings);
            v.setSecretList(secretList);
            v.setUpdatedAt(System.currentTimeMillis());
        } else {
            v = new NovelVolumeNode(novelName, volumeNumber, title, chapterStart, chapterEnd,
                    mainPlot, chapterOutlines, foreshadowings, secretList);
        }
        return volumeRepo.save(v);
    }

    public List<NovelVolumeNode> getVolumes(String novelName) {
        return volumeRepo.findByNovelName(novelName);
    }

    public String getVolumesAsPrompt(String novelName) {
        List<NovelVolumeNode> volumes = volumeRepo.findByNovelName(novelName);
        if (volumes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【分卷大纲】\n");
        for (NovelVolumeNode v : volumes) {
            sb.append("· 第").append(v.getVolumeNumber()).append("卷《").append(v.getTitle()).append("》")
              .append("（第").append(v.getChapterStart()).append("-").append(v.getChapterEnd()).append("章）\n");
            if (v.getMainPlot() != null && !v.getMainPlot().isBlank())
                sb.append("  主线：").append(v.getMainPlot()).append("\n");
        }
        return sb.toString();
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
        StringBuilder sb = new StringBuilder("【人物设定与关系】\n");
        for (NovelCharacterNode c : chars) {
            sb.append("- ").append(c.getName()).append("（").append(c.getRole()).append("）: ")
              .append(c.getPersonality());
            if (c.getAppearance() != null) sb.append("，外貌: ").append(c.getAppearance());
            if (c.getBackground() != null) sb.append("，背景: ").append(c.getBackground());
            sb.append("，状态: ").append(c.getStatus());
            for (NovelRelationship r : c.getRelationships()) {
                if (r.getTarget() == null) continue;
                sb.append("\n    -[").append(r.getType()).append("]-> ").append(r.getTarget().getName());
                if (r.getDescription() != null && !r.getDescription().isBlank())
                    sb.append("（").append(r.getDescription()).append("）");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ========== 章节管理 ==========

    public NovelChapterNode addChapter(String novelName, int chapterNumber, String title, String content) {
        List<NovelChapterNode> existing = chapterRepo.findByNovelNameAndNumber(novelName, chapterNumber);
        NovelChapterNode ch;
        if (existing.isEmpty()) {
            ch = new NovelChapterNode(novelName, chapterNumber, title, content);
        } else {
            // 同章号覆盖，避免重复节点导致后续查询报错
            ch = existing.get(0);
            ch.setTitle(title);
            ch.setContent(content);
            ch.setWordCount(content != null ? content.length() : 0);
            ch.setUpdatedAt(System.currentTimeMillis());
            for (int i = 1; i < existing.size(); i++) {
                chapterRepo.deleteById(existing.get(i).getId());
            }
        }
        NovelChapterNode saved = chapterRepo.save(ch);
        writeChapterFile(novelName, chapterNumber, title, content);
        return saved;
    }

    public void setChapterSummary(String novelName, int chapterNumber, String summary,
                                   String characters, String plotThreads) {
        findChapter(novelName, chapterNumber).ifPresent(ch -> {
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
        return findChapter(novelName, chapterNumber);
    }

    public void updateChapter(String novelName, int chapterNumber, String content) {
        findChapter(novelName, chapterNumber).ifPresent(ch -> {
            ch.setContent(content);
            ch.setWordCount(content != null ? content.length() : 0);
            ch.setUpdatedAt(System.currentTimeMillis());
            chapterRepo.save(ch);
            writeChapterFile(novelName, chapterNumber, ch.getTitle(), content);
        });
    }

    private Optional<NovelChapterNode> findChapter(String novelName, int chapterNumber) {
        return chapterRepo.findByNovelNameAndNumber(novelName, chapterNumber).stream().findFirst();
    }

    private void writeChapterFile(String novelName, int chapterNumber, String title, String content) {
        try {
            String safeName = sanitize(novelName);
            String safeTitle = sanitize(title == null ? "" : title);
            Path dir = Path.of(outputDir, safeName);
            Files.createDirectories(dir);
            String fileName = String.format("第%d章_%s.txt", chapterNumber, safeTitle);
            String body = (title != null && !title.isBlank() ? title + "\n\n" : "") + (content == null ? "" : content);
            Files.writeString(dir.resolve(fileName), body, StandardCharsets.UTF_8);
            log.info("📄 章节已写入文件: {}", dir.resolve(fileName).toAbsolutePath());
        } catch (IOException e) {
            log.error("❌ 章节写入文件失败: {} 第{}章", novelName, chapterNumber, e);
        }
    }

    private String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
    }

    /** 删除整部小说的所有数据：Neo4j 节点（大纲/分卷/章节/人物/情节线）+ Qdrant 向量 + 导出文件。 */
    public String deleteNovel(String novelName) {
        int chapterCount = (int) chapterRepo.countByNovelName(novelName);
        int characterCount = characterRepo.findByNovelName(novelName).size();

        // Neo4j：人物 DETACH DELETE 连带删 RELATED_TO 关系；其余各自 DETACH DELETE
        outlineRepo.deleteByNovelName(novelName);
        volumeRepo.deleteByNovelName(novelName);
        chapterRepo.deleteByNovelName(novelName);
        plotThreadRepo.deleteByNovelName(novelName);
        characterRepo.deleteByNovelName(novelName);

        // Qdrant 向量
        vectorMemory.deleteNovel(novelName);

        // 导出文件目录
        deleteNovelFiles(novelName);

        return "✅ 已删除小说「" + novelName + "」：章节 " + chapterCount + " 章、人物 " + characterCount
                + " 个（Neo4j 图谱 + Qdrant 向量 + 导出文件已全部清理）";
    }

    private void deleteNovelFiles(String novelName) {
        try {
            Path dir = Path.of(outputDir, sanitize(novelName));
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("⚠️ 删除文件失败: {}", p, e);
                        }
                    });
                }
            }
        } catch (IOException e) {
            log.warn("⚠️ 删除小说文件目录失败: {} {}", novelName, e.getMessage());
        }
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

    /** 目标章之前的 N 章摘要（重写旧章时用，避免错位到全书结尾） */
    private String getRecentSummariesBefore(String novelName, int targetChapter, int count) {
        List<NovelChapterNode> before = new ArrayList<>();
        for (NovelChapterNode ch : chapterRepo.findByNovelName(novelName)) {
            if (ch.getChapterNumber() < targetChapter) before.add(ch);
        }
        before.sort((a, b) -> Integer.compare(a.getChapterNumber(), b.getChapterNumber()));
        int total = before.size();
        if (total == 0) return "";
        int start = Math.max(0, total - count);
        StringBuilder sb = new StringBuilder("【前文摘要】\n");
        for (int i = start; i < total; i++) {
            NovelChapterNode ch = before.get(i);
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
     * 组装写下一章所需的上下文。按「精确注入、控 token」原则，只注入少量必要内容，不塞全书：
     * 设定（世界观/主线精简）+ 当前卷主线与当前章细纲 + 出场人物卡（2-3人）+ 前文摘要 + 上一章结尾 + 当前章相关伏笔。
     *
     * @param chapterNumber 指定目标章号（用于重写旧章）；为空时默认"已写N章 → 下一章 N+1"
     */
    public String buildWritingContext(String novelName, Integer chapterNumber) {
        novelName = resolveNovelName(novelName);
        if (novelName == null) return "❌ 无法确定小说名称";
        StringBuilder ctx = new StringBuilder();
        long chapterCount = chapterRepo.countByNovelName(novelName);
        boolean rewriting = chapterNumber != null && chapterNumber > 0;
        int targetChapter = rewriting ? chapterNumber : (int) chapterCount + 1;

        // 1. 写作任务 + 总进度
        ctx.append(String.format("【写作任务】现在%s《%s》第%d章（已写%d章）\n\n",
                rewriting ? "重写" : "写", novelName, targetChapter, chapterCount));

        // 2. 设定：世界观 + 主线（精简 ~150 字）
        String outline = getOutlineBriefAsPrompt(novelName, 150);
        if (!outline.isEmpty()) ctx.append(outline).append("\n");

        // 3. 当前卷主线 + 当前章节细纲（细纲里声明出场人物）
        String volume = getVolumeContextForChapter(novelName, targetChapter);
        if (!volume.isEmpty()) ctx.append(volume).append("\n");

        // 3.5 反泄露清单（本卷禁止提前透露的信息，防止写正文时剧透）
        String secrets = getSecretsForChapter(novelName, targetChapter);
        if (!secrets.isEmpty()) ctx.append(secrets).append("\n");

        // 4. 出场人物卡（2-3 人，按当前章细纲声明的出场人物筛）
        String characters = getCharactersForChapterAsPrompt(novelName, targetChapter);
        if (!characters.isEmpty()) ctx.append(characters).append("\n");

        // 5. 前文摘要（目标章之前 5 章，重写旧章时不会错位到全书结尾）
        String summaries = rewriting
                ? getRecentSummariesBefore(novelName, targetChapter, 5)
                : getRecentSummariesAsPrompt(novelName, 5);
        if (!summaries.isEmpty()) ctx.append(summaries).append("\n");

        // 6. 上一章结尾（按段落取整段，500-1000 字，剧情衔接）
        String tail = getPreviousChapterTail(novelName, targetChapter, 500);
        if (!tail.isEmpty()) ctx.append(tail).append("\n");

        // 7. 当前章相关伏笔提醒
        String threads = getForeshadowingsForChapter(novelName, targetChapter);
        if (!threads.isEmpty()) ctx.append(threads).append("\n");

        // 7.5 每 5 章边界强制回看：查已揭晓/未回收伏笔与搁置的强制元素（查缺补漏方案）
        if (targetChapter % 5 == 1) {
            ctx.append("【伏笔回看】现在检查：1) 最近5章已揭晓哪些伏笔 2) 哪些线索尚未回收、最后一次出现于哪章 3) 本卷强制元素（玉坠/身份线等）是否被搁置过久。据此决定本章优先处理哪条线。\n");
        }

        // 8. 输出要求（含三要三不要风格指令 + 情绪目标落实）
        ctx.append("【输出要求】正文 1500-2000 字，保持既定文风（偏白描、短句、章末留钩）。");
        ctx.append("【风格指令】三要三不要：1) 要有一句突如其来的、不符合人设但符合当前情绪的话；不要每句话都符合人设。"
                + "2) 要让对话偶尔被动作打断；不要对话工整得像剧本。"
                + "3) 要给本章留一个收尾的余味；不要每章都写'夜色沉沉'或'他望向窗外'。"
                + "避免'他感到''他意识到''他心里明白'这类概括词，改用具体动作替换。"
                + "若当前章细纲标注了情绪目标（如'疲惫但专业'），必须用具体的动作、语气、环境细节落实它。"
                + "【文笔与比喻】文风偏白描、朴素自然，像面对面讲故事，禁止做作："
                + "1) 少用形容词和四字成语堆砌，禁止华丽辞藻、排比、抒情腔（如'他深深吸了一口气，仿佛要把世间的悲欢都吸进肺里'）；能用短句说完不用长句。"
                + "2) 描写落在具体动作、对话、感官细节上，用大白话，不煽情、不文艺腔。"
                + "3) 比喻要少而新：能不比喻就不比喻，直接写动作；同一比喻绝不跨章复用，不用俗套比喻（如'心如刀绞''脸色惨白如纸'）。"
                + "4) 检查标准：读完一句如果觉得'这不像人话''太绕'，就重写得更直白。"
                + "【人称与逻辑硬约束（必须遵守）】1) 每句对话必须明确归属：写'韩言说''沈砚说'，不用'他说'开头连续两句以上。"
                + "2) 禁止出现主宾颠倒的句式：检查'A对B做某事'是否写成了'B对A做某事'。"
                + "3) 因果方向必须清晰：'因为A所以B'不能写成'因为B所以A'。"
                + "4) 生成完后在心里复核一遍：这句话如果换一个角色来说是不是也成立？如果成立，说明人称指向不明确，必须重写。");
        return ctx.toString();
    }

    /**
     * 组装校验某章正文所需的「预期上下文」：设定 + 当前卷主线与本章细纲 + 反泄露清单 + 出场人物 +
     * 前文摘要 + 上一章结尾 + 本章相关伏笔 + 风格要求。用于 verify_chapter 生成后自查。
     */
    public String buildCheckContext(String novelName, int chapterNumber) {
        StringBuilder ctx = new StringBuilder();
        ctx.append(String.format("【检查对象】《%s》第%d章\n\n", novelName, chapterNumber));

        String outline = getOutlineBriefAsPrompt(novelName, 150);
        if (!outline.isEmpty()) ctx.append(outline).append("\n");

        String volume = getVolumeContextForChapter(novelName, chapterNumber);
        if (!volume.isEmpty()) ctx.append(volume).append("\n");

        String secrets = getSecretsForChapter(novelName, chapterNumber);
        if (!secrets.isEmpty()) ctx.append(secrets).append("\n");

        String characters = getCharactersForChapterAsPrompt(novelName, chapterNumber);
        if (!characters.isEmpty()) ctx.append(characters).append("\n");

        String summaries = getRecentSummariesAsPrompt(novelName, 5);
        if (!summaries.isEmpty()) ctx.append(summaries).append("\n");

        String tail = getPreviousChapterTail(novelName, chapterNumber, 300);
        if (!tail.isEmpty()) ctx.append(tail).append("\n");

        String threads = getForeshadowingsForChapter(novelName, chapterNumber);
        if (!threads.isEmpty()) ctx.append(threads).append("\n");

        ctx.append("【风格要求】三要三不要：1) 要有一句突如其来的、不符合人设但符合当前情绪的话；不要每句话都符合人设。"
                + "2) 要让对话偶尔被动作打断；不要对话工整得像剧本。"
                + "3) 要给本章留一个收尾的余味；不要每章都写'夜色沉沉'或'他望向窗外'。"
                + "避免'他感到''他意识到''他心里明白'这类概括词，改用具体动作替换。"
                + "文笔要好、有画面感；比喻少而新，同一比喻不跨章复用，不用俗套比喻。");
        return ctx.toString();
    }

    /** 批量检查用：全局上下文（设定精简 + 风格要求），整批只发一次。 */
    public String buildGlobalCheckContext(String novelName) {
        StringBuilder ctx = new StringBuilder();
        ctx.append("【小说设定】\n");
        String outline = getOutlineBriefAsPrompt(novelName, 200);
        if (!outline.isEmpty()) ctx.append(outline).append("\n");
        ctx.append("【风格要求】三要三不要：1) 要有一句突如其来的、不符合人设但符合当前情绪的话；不要每句话都符合人设。"
                + "2) 要让对话偶尔被动作打断；不要对话工整得像剧本。"
                + "3) 要给本章留一个收尾的余味；不要每章都写'夜色沉沉'或'他望向窗外'。"
                + "避免'他感到''他意识到''他心里明白'这类概括词，改用具体动作替换。"
                + "文笔要好、有画面感；比喻少而新，同一比喻不跨章复用，不用俗套比喻。");
        return ctx.toString();
    }

    /** 批量检查用：某章的精简上下文（当前卷主线+本章细纲+反泄露清单+本章相关伏笔），按章发。 */
    public String buildCheckBrief(String novelName, int chapterNumber) {
        StringBuilder ctx = new StringBuilder();
        String volume = getVolumeContextForChapter(novelName, chapterNumber);
        if (!volume.isEmpty()) ctx.append(volume).append("\n");
        String secrets = getSecretsForChapter(novelName, chapterNumber);
        if (!secrets.isEmpty()) ctx.append(secrets).append("\n");
        String threads = getForeshadowingsForChapter(novelName, chapterNumber);
        if (!threads.isEmpty()) ctx.append(threads).append("\n");
        return ctx.toString();
    }

    /** 本卷反泄露清单（禁止提前透露的信息），每行一条。 */
    private String getSecretsForChapter(String novelName, int chapterNumber) {
        return volumeRepo.findByNovelName(novelName).stream()
                .filter(v -> chapterNumber >= v.getChapterStart() && chapterNumber <= v.getChapterEnd())
                .findFirst()
                .map(v -> {
                    if (v.getSecretList() == null || v.getSecretList().isBlank()) return "";
                    return "【反泄露清单（本卷禁止提前透露，注意避开）】\n" + truncate(v.getSecretList(), 300) + "\n";
                })
                .orElse("");
    }

    // ========== 写上下文辅助：精确注入 ==========

    /** 世界观 + 主线，精简到 maxLen 字以内。 */
    private String getOutlineBriefAsPrompt(String novelName, int maxLen) {
        return outlineRepo.findByNovelName(novelName)
                .map(o -> {
                    StringBuilder sb = new StringBuilder("【设定】");
                    if (o.getWorldview() != null && !o.getWorldview().isBlank())
                        sb.append("世界观：").append(o.getWorldview()).append("；");
                    if (o.getMainPlot() != null && !o.getMainPlot().isBlank())
                        sb.append("主线：").append(o.getMainPlot());
                    return truncate(sb.toString(), maxLen);
                })
                .orElse("");
    }

    /** 当前卷主线 + 当前章节细纲（只取当前章那一行，不含整卷分章简要）。 */
    private String getVolumeContextForChapter(String novelName, int chapterNumber) {
        return volumeRepo.findByNovelName(novelName).stream()
                .filter(v -> chapterNumber >= v.getChapterStart() && chapterNumber <= v.getChapterEnd())
                .findFirst()
                .map(v -> {
                    StringBuilder sb = new StringBuilder("【当前卷】第").append(v.getVolumeNumber())
                            .append("卷《").append(v.getTitle()).append("》");
                    if (v.getMainPlot() != null && !v.getMainPlot().isBlank())
                        sb.append("\n本卷主线：").append(truncate(v.getMainPlot(), 80));
                    String line = extractChapterOutlineLine(v.getChapterOutlines(), chapterNumber);
                    if (line != null && !line.isBlank())
                        sb.append("\n当前章细纲：").append(truncate(line, 200));
                    return sb.toString();
                })
                .orElse("");
    }

    /** 从分章简要文本中按「第N章」精确提取当前章那一行。 */
    private String extractChapterOutlineLine(String chapterOutlines, int chapterNumber) {
        if (chapterOutlines == null || chapterOutlines.isBlank()) return null;
        Pattern p = Pattern.compile("第\\s*" + chapterNumber + "\\s*章");
        for (String line : chapterOutlines.split("\\R")) {
            if (p.matcher(line).find()) return line.trim();
        }
        return null;
    }

    /** 从细纲行里解析「出场：甲、乙、丙」中的人物名。 */
    private List<String> extractCharacterNames(String line) {
        if (line == null || line.isBlank()) return List.of();
        Matcher m = Pattern.compile("出场\\s*[:：]\\s*([^）)]+)").matcher(line);
        if (!m.find()) return List.of();
        List<String> result = new ArrayList<>();
        for (String n : m.group(1).split("[、，,、\\s]+")) {
            String name = n.trim();
            if (!name.isEmpty()) result.add(name);
        }
        return result;
    }

    /** 人物卡：按当前章细纲声明的出场人物筛（最多 3 人）；没声明时回退主角。 */
    private String getCharactersForChapterAsPrompt(String novelName, int chapterNumber) {
        String outlineLine = volumeRepo.findByNovelName(novelName).stream()
                .filter(v -> chapterNumber >= v.getChapterStart() && chapterNumber <= v.getChapterEnd())
                .map(v -> extractChapterOutlineLine(v.getChapterOutlines(), chapterNumber))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
        List<String> cast = extractCharacterNames(outlineLine);

        List<NovelCharacterNode> all = characterRepo.findByNovelName(novelName);
        if (all.isEmpty()) return "";

        List<NovelCharacterNode> selected = new ArrayList<>();
        if (cast.isEmpty()) {
            for (NovelCharacterNode c : all) {
                if (c.getRole() != null && c.getRole().contains("主角")) selected.add(c);
            }
        } else {
            for (String name : cast) {
                all.stream().filter(c -> c.getName().equals(name)).findFirst().ifPresent(selected::add);
            }
        }
        if (selected.isEmpty()) {
            selected.addAll(all.subList(0, Math.min(3, all.size())));
        }
        selected = selected.subList(0, Math.min(3, selected.size()));

        StringBuilder sb = new StringBuilder("【出场人物】\n");
        for (NovelCharacterNode c : selected) {
            sb.append("- ").append(c.getName()).append("（").append(c.getRole()).append("）：")
              .append(c.getPersonality() == null ? "" : c.getPersonality());
            if (c.getAppearance() != null && !c.getAppearance().isBlank())
                sb.append("；外貌：").append(c.getAppearance());
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 上一章结尾：按段落从末尾往前取整段，累加到至少 minLen 字，不跨段落截断，避免破坏语义。 */
    private String getPreviousChapterTail(String novelName, int chapterNumber, int minLen) {
        NovelChapterNode prev = null;
        for (NovelChapterNode ch : chapterRepo.findByNovelName(novelName)) {
            if (ch.getChapterNumber() == chapterNumber - 1) { prev = ch; break; }
        }
        if (prev == null || prev.getContent() == null || prev.getContent().isBlank()) return "";

        // 按换行切段落，过滤空段（保留段落原格式）
        List<String> paragraphs = new ArrayList<>();
        for (String p : prev.getContent().split("\\R")) {
            if (!p.trim().isEmpty()) paragraphs.add(p);
        }
        if (paragraphs.isEmpty()) return "";

        // 从末尾往前累加整段，直到 >= minLen 或取完整章
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (int i = paragraphs.size() - 1; i >= 0; i--) {
            String p = paragraphs.get(i);
            total += p.length();
            sb.insert(0, p + "\n");
            if (total >= minLen) break;
        }
        return "【上一章结尾】\n" + sb.toString().trim();
    }

    /** 当前章相关伏笔：当前卷伏笔计划（截断）+ 未回收伏笔里埋设章号最接近当前的（最多 3 条）。 */
    private String getForeshadowingsForChapter(String novelName, int chapterNumber) {
        StringBuilder sb = new StringBuilder("【伏笔提醒】\n");
        volumeRepo.findByNovelName(novelName).stream()
                .filter(v -> chapterNumber >= v.getChapterStart() && chapterNumber <= v.getChapterEnd())
                .findFirst()
                .ifPresent(v -> {
                    if (v.getForeshadowings() != null && !v.getForeshadowings().isBlank())
                        sb.append(truncate(v.getForeshadowings(), 200)).append("\n");
                });
        List<NovelPlotThreadNode> unresolved = plotThreadRepo.findUnresolvedThreads(novelName);
        if (!unresolved.isEmpty()) {
            unresolved.stream()
                    .sorted(Comparator.comparingInt((NovelPlotThreadNode t) ->
                            Math.abs(t.getPlantedChapter() - chapterNumber)))
                    .limit(3)
                    .forEach(t -> sb.append("- ").append(t.getThreadName()).append("：")
                            .append(t.getDescription())
                            .append("（埋于第").append(t.getPlantedChapter()).append("章）\n"));
        }
        String result = sb.toString().trim();
        return result.equals("【伏笔提醒】") ? "" : result;
    }

    /** 截断到 maxLen 字（中文按字符算），超长加省略号。 */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "…";
    }

    // ========== 导出 ==========

    /** 导出全书正文到单个 .txt 文件，返回文件路径与统计，供用户查看质量。 */
    public String exportNovel(String novelName) {
        List<NovelChapterNode> chapters = chapterRepo.findByNovelName(novelName);
        if (chapters.isEmpty()) return "❌ 尚无章节可导出";
        StringBuilder sb = new StringBuilder();
        int totalWords = 0;
        for (NovelChapterNode ch : chapters) {
            sb.append("第").append(ch.getChapterNumber()).append("章 ")
              .append(ch.getTitle() == null ? "" : ch.getTitle()).append("\n\n");
            String content = ch.getContent() == null ? "" : ch.getContent();
            sb.append(content).append("\n\n\n");
            totalWords += content.length();
        }
        try {
            String safeName = sanitize(novelName);
            Path dir = Path.of(outputDir, safeName);
            Files.createDirectories(dir);
            Path file = dir.resolve(safeName + "_全文.txt");
            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
            return "✅ 已导出 " + chapters.size() + " 章 / " + totalWords + " 字 → " + file.toAbsolutePath();
        } catch (IOException e) {
            log.error("❌ 导出失败", e);
            return "❌ 导出失败: " + e.getMessage();
        }
    }
}
