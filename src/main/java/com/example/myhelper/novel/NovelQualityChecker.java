package com.example.myhelper.novel;

import com.example.myhelper.config.ModelRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节质量检查器（verify_chapter 后端）：
 * <ol>
 *   <li>本地重复检查（免费、不调 LLM）：章内重复句、与最近 5 章重复的句子和高频表达</li>
 *   <li>LLM 结构校验：反泄露违规 / 伏笔冲突 / 剧情衔接 / 偏离本书写作档案 / 情绪目标落实</li>
 * </ol>
 */
@Service
public class NovelQualityChecker {

    private static final Logger log = LoggerFactory.getLogger(NovelQualityChecker.class);

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？!?；;…])");
    private static final Pattern CODE_FENCE = Pattern.compile("^\\s*```[a-zA-Z]*\\s*$|^\\s*```\\s*$", Pattern.MULTILINE);
    private static final Pattern METAPHOR = Pattern.compile("[^。！？!?；;…\\n]*[像仿佛宛如如同好像][^。！？!?；;…\\n]*");

    private final ModelRouter modelRouter;
    private final NovelMemoryService memory;
    private final NovelChapterRepository chapterRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 自动续写只有达到这个分数、且没有严重问题时才允许提交。 */
    @Value("${novel.quality.min-score:82}")
    private int minScore = 82;

    public NovelQualityChecker(ModelRouter modelRouter,
                               NovelMemoryService memory,
                               NovelChapterRepository chapterRepo) {
        this.modelRouter = modelRouter;
        this.memory = memory;
        this.chapterRepo = chapterRepo;
    }

    /** 入口：检查某一章正文，返回完整检查报告。 */
    public String verify(String novelName, int chapterNumber, String content) {
        return assess(novelName, chapterNumber, content).asReport(novelName, chapterNumber);
    }

    /**
     * 为自动写作提供可判定的质量门禁。模型不可用或返回无法解析时一律不放行，
     * 防止“检查失败但照样发布”的静默降级。
     */
    public NovelQualityAssessment assess(String novelName, int chapterNumber, String content) {
        if (content == null || content.isBlank()) {
            return NovelQualityAssessment.reviewed(0,
                    List.of(new NovelQualityAssessment.Issue(NovelQualityAssessment.Severity.CRITICAL,
                            "正文", "", "正文内容为空", "先生成完整正文，再进入质量检查。")), true, minScore);
        }
        List<NovelQualityAssessment.Issue> localIssues = localIssues(duplicateCheck(novelName, chapterNumber, content));
        return modelAssessment(novelName, chapterNumber, content, localIssues);
    }

    private NovelQualityAssessment modelAssessment(String novelName, int chapterNumber, String content,
                                                    List<NovelQualityAssessment.Issue> localIssues) {
        String context;
        try {
            context = memory.buildCheckContext(novelName, chapterNumber);
        } catch (Exception e) {
            log.warn("组装质量门禁上下文失败: {}", e.getMessage());
            context = "";
        }

        String prompt = "你是严格的网络小说责任编辑。根据【预期上下文】评审【待检查正文】，"
                + "不修改正文，只输出可执行的问题定位。必须检查：反泄露、伏笔、章节衔接、人物/因果逻辑、"
                + "文风是否符合本书写作档案、情绪和章节计划是否落实。\n"
                + "评分标准：90+ 可直接发布；82-89 可发布但允许轻微优化；低于82需重写；"
                + "任何剧透、设定/人物/因果硬冲突必须为 CRITICAL 且 decision=REWRITE。\n"
                + "严格输出 JSON（不要 markdown）：\n"
                + "{\"score\":0-100,\"decision\":\"PASS|REWRITE\",\"issues\":["
                + "{\"severity\":\"CRITICAL|WARN|INFO\",\"category\":\"反泄露|伏笔|衔接|文风|情绪|逻辑\","
                + "\"quote\":\"问题原文短句，没有则空\",\"detail\":\"具体问题\",\"suggestion\":\"可执行修改\"}]}\n"
                + "【预期上下文】\n" + context + "\n【待检查正文】\n" + content;
        try {
            String raw = modelRouter.cloudOnly().prompt().user(prompt).call().content();
            JsonNode root = parseJsonObject(raw);
            if (root == null) {
                return NovelQualityAssessment.reviewed(null, localIssues, false, minScore);
            }
            List<NovelQualityAssessment.Issue> issues = new ArrayList<>(localIssues);
            JsonNode modelIssues = root.get("issues");
            if (modelIssues != null && modelIssues.isArray()) {
                for (JsonNode issue : modelIssues) issues.add(toAssessmentIssue(issue));
            }
            Integer score = root.has("score") && root.get("score").canConvertToInt()
                    ? root.get("score").asInt() : null;
            // 质量门禁所需的核心字段缺失时不能按“没有问题”放行。
            if (score == null) {
                return NovelQualityAssessment.reviewed(null, issues, false, minScore);
            }
            NovelQualityAssessment assessment = NovelQualityAssessment.reviewed(score, issues, true, minScore);
            String declared = root.path("decision").asText("");
            // 模型明确要求重写时尊重它的结论，但 PASS 不能覆盖本地或严重问题。
            if ("REWRITE".equalsIgnoreCase(declared) && assessment.decision() == NovelQualityAssessment.Decision.PASS) {
                return new NovelQualityAssessment(assessment.score(), NovelQualityAssessment.Decision.REWRITE,
                        true, assessment.issues());
            }
            return assessment;
        } catch (Exception e) {
            log.warn("章节质量门禁调用失败: {}", e.getMessage());
            return NovelQualityAssessment.reviewed(null, localIssues, false, minScore);
        }
    }

    /** 将既有的本地重复检查结果纳入门禁，不再只是展示性文字报告。 */
    private List<NovelQualityAssessment.Issue> localIssues(String report) {
        if (report == null || report.isBlank()) return List.of();
        List<NovelQualityAssessment.Issue> issues = new ArrayList<>();
        for (String line : report.split("\\R")) {
            String text = line.replaceFirst("^[⚠️❌ℹ️\\s]+", "").trim();
            if (!text.startsWith("章内重复") && !text.startsWith("跨章重复")
                    && !text.startsWith("高频表达") && !text.startsWith("重复比喻")) continue;
            issues.add(new NovelQualityAssessment.Issue(NovelQualityAssessment.Severity.WARN,
                    "重复表达", "", text, "保留本章信息不变，改写该处表达。"));
        }
        return issues;
    }

    private JsonNode parseJsonObject(String raw) {
        String json = CODE_FENCE.matcher(raw == null ? "" : raw).replaceAll("").trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return objectMapper.readTree(json.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private NovelQualityAssessment.Issue toAssessmentIssue(JsonNode issue) {
        NovelQualityAssessment.Severity severity;
        try {
            severity = NovelQualityAssessment.Severity.valueOf(issue.path("severity").asText("INFO").toUpperCase());
        } catch (IllegalArgumentException ignored) {
            severity = NovelQualityAssessment.Severity.INFO;
        }
        return new NovelQualityAssessment.Issue(severity, issue.path("category").asText("其他"),
                issue.path("quote").asText(""), issue.path("detail").asText("未说明的问题"),
                issue.path("suggestion").asText(""));
    }

    /** 批量检查多章：一次 LLM 调用检查整批，减少调用次数。本地重复检查按整批跨章比对。 */
    public String verifyBatch(String novelName, List<Integer> chapters) {
        if (chapters == null || chapters.isEmpty()) return "";
        List<ChapterText> texts = new ArrayList<>();
        for (int n : chapters) {
            String content = memory.getChapter(novelName, n).map(ch -> ch.getContent()).orElse("");
            if (!content.isBlank()) texts.add(new ChapterText(n, content));
        }
        if (texts.isEmpty()) return "";
        StringBuilder report = new StringBuilder();
        report.append("【章节检查报告】《").append(novelName).append("》第")
                .append(texts.get(0).chapter()).append("-")
                .append(texts.get(texts.size() - 1).chapter()).append("章（合并检查）\n\n");
        report.append(duplicateCheckBatch(texts));
        report.append("\n");
        report.append(qualityCheckBatch(novelName, texts));
        return report.toString();
    }

    // ========== 1. 本地重复检查 ==========

    /** 批量版：整批章节跨章互查重复句、高频表达、重复比喻。 */
    private String duplicateCheckBatch(List<ChapterText> texts) {
        StringBuilder sb = new StringBuilder("【重复检查】\n");
        boolean found = false;

        // 1.1 跨章重复句：同一句在 >=2 章出现
        Map<String, List<Integer>> byText = new LinkedHashMap<>();
        for (ChapterText t : texts) {
            for (String s : splitSentences(t.content())) {
                byText.computeIfAbsent(s, k -> new ArrayList<>()).add(t.chapter());
            }
        }
        for (Map.Entry<String, List<Integer>> e : byText.entrySet()) {
            List<Integer> chs = e.getValue().stream().distinct().toList();
            if (chs.size() >= 2) {
                sb.append("⚠️ 跨章重复：\"").append(e.getKey()).append("\" 在第 ").append(chs).append(" 章都出现，换一种说法\n");
                found = true;
            }
        }

        // 1.2 章内重复句
        for (ChapterText t : texts) {
            Map<String, Integer> inChapter = new LinkedHashMap<>();
            for (String s : splitSentences(t.content())) inChapter.merge(s, 1, Integer::sum);
            for (Map.Entry<String, Integer> e : inChapter.entrySet()) {
                if (e.getValue() >= 2) {
                    sb.append("⚠️ 章内重复：第").append(t.chapter()).append("章 \"").append(e.getKey())
                            .append("\" 出现 ").append(e.getValue()).append(" 次\n");
                    found = true;
                }
            }
        }

        // 1.3 高频表达：整批合并统计 2-6 字短语，出现 >=4 次
        StringBuilder joined = new StringBuilder();
        for (ChapterText t : texts) joined.append(t.content().replaceAll("\\s+", ""));
        Map<String, Integer> ngram = countNgrams(joined.toString(), 2, 6);
        int phraseWarnings = 0;
        for (Map.Entry<String, Integer> e : ngram.entrySet()) {
            if (e.getValue() >= 4) {
                sb.append("⚠️ 高频表达：\"").append(e.getKey()).append("\" 整批出现 ").append(e.getValue()).append(" 次，考虑换一种描写\n");
                found = true;
                if (++phraseWarnings >= 6) {
                    sb.append("…（高频表达较多，仅列前 6 条）\n");
                    break;
                }
            }
        }

        // 1.4 重复比喻：同一比喻片段出现在 >=2 章
        Map<String, List<Integer>> metaphors = new LinkedHashMap<>();
        for (ChapterText t : texts) {
            Matcher m = METAPHOR.matcher(t.content());
            while (m.find()) {
                String frag = m.group().trim();
                if (frag.length() >= 6 && frag.length() <= 40) {
                    metaphors.computeIfAbsent(frag, k -> new ArrayList<>()).add(t.chapter());
                }
            }
        }
        for (Map.Entry<String, List<Integer>> e : metaphors.entrySet()) {
            List<Integer> chs = e.getValue().stream().distinct().toList();
            if (chs.size() >= 2) {
                sb.append("⚠️ 重复比喻：\"").append(e.getKey()).append("\" 在第 ").append(chs).append(" 章都用过，换一种表达\n");
                found = true;
            }
        }

        if (!found) sb.append("✅ 未发现重复句子或高频表达\n");
        return sb.toString();
    }

    /** 批量版：一次 LLM 调用检查整批，输出每章问题。 */
    private String qualityCheckBatch(String novelName, List<ChapterText> texts) {
        StringBuilder sb = new StringBuilder("【LLM 结构校验】\n");
        String global;
        try {
            global = memory.buildGlobalCheckContext(novelName);
        } catch (Exception e) {
            log.warn("组装批量校验全局上下文失败: {}", e.getMessage());
            global = "";
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是网络小说质检员。下面是要检查的多章正文，每章带细纲/反泄露清单/伏笔。逐章检查，只找问题，不要修改正文。\n")
                .append("检查维度：1) 反泄露（是否透露反泄露清单禁止的信息） 2) 伏笔（与伏笔计划冲突或该回收没处理） 3) 衔接（章节内及章节之间矛盾） 4) 文风（是否违反本书写作档案、出现重复或陈词滥调） 5) 情绪（细纲标注的情绪目标是否落实）\n")
                .append("6) 逻辑（必须逐项核对）：①对话归属：全文每一句对话能否明确判断是谁说的？若出现'他说'但前一句说话人不是他、或隔了两句才补主语，标记错误。"
                + "②主语与人称一致性：每句主语是否清楚；同一段'他''她'是否始终指代同一个人，中途换指代对象但未说明则标记。"
                + "③因果方向：查找'因为……所以……''……于是……''……才……'等因果结构，检查原因结果是否颠倒，如'你都不问问我要带话给谁'——'问'的主体和'带话'的主体被反置。"
                + "④主动/被动关系：检查'把'字句和'被'字句是否用反、'A把B给C'是否写成'A把C给B'。\n")
                .append("输出严格JSON（不要markdown代码块）：\n")
                .append("{\"results\":[{\"chapter\":章号,\"issues\":[{\"severity\":\"CRITICAL|WARN|INFO\",\"category\":\"反泄露|伏笔|衔接|文风|情绪|逻辑\",\"detail\":\"具体问题描述\",\"suggestion\":\"修改建议\"}]}]}\n")
                .append("某章没有问题则它的 issues 为空数组。\n\n")
                .append(global).append("\n");
        for (ChapterText t : texts) {
            prompt.append("【第").append(t.chapter()).append("章】\n");
            String brief = memory.buildCheckBrief(novelName, t.chapter());
            if (!brief.isEmpty()) prompt.append(brief).append("\n");
            prompt.append("正文：\n").append(t.content()).append("\n\n");
        }

        // 逻辑复审：通读本组所有章节，专门扫三类人称/因果错误（补救层）
        prompt.append("【逻辑复审（整组级）】通读以上所有章节，专门检查三类错误："
                + "①对话归属不清：找出所有'他说''她道'但前文未明确说话人的段落；"
                + "②人称混乱：同一段落中'他''她'指代不明或前后不一致的句子；"
                + "③因果/主宾颠倒：因果关系或主动被动关系写反的句子。"
                + "对每个错误给出原文→修正案，写入对应章节的 issues（category 填'逻辑'）。"
                + "重点关注第").append(texts.get(0).chapter()).append("到第")
                .append(texts.get(texts.size() - 1).chapter())
                .append("章，同类错误可能扎堆出现。\n");

        try {
            String raw = modelRouter.cloudOnly().prompt().user(prompt.toString()).call().content();
            String json = CODE_FENCE.matcher(raw == null ? "" : raw).replaceAll("").trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return sb.append("❌ LLM 返回无法解析，跳过结构校验\n").toString();
            }
            JsonNode root = objectMapper.readTree(json.substring(start, end + 1));
            JsonNode results = root.get("results");
            if (results == null || !results.isArray() || results.isEmpty()) {
                return sb.append("✅ 整批未发现问题\n").toString();
            }
            int critical = 0;
            boolean anyIssue = false;
            for (JsonNode r : results) {
                int ch = r.path("chapter").asInt(0);
                JsonNode issues = r.get("issues");
                if (issues == null || !issues.isArray() || issues.isEmpty()) continue;
                anyIssue = true;
                sb.append("第").append(ch).append("章：\n");
                for (JsonNode it : issues) {
                    String sev = it.path("severity").asText("INFO");
                    String cat = it.path("category").asText("其他");
                    String detail = it.path("detail").asText("未知问题");
                    String sugg = it.path("suggestion").asText("");
                    String icon = "CRITICAL".equalsIgnoreCase(sev) ? "❌" : "WARN".equalsIgnoreCase(sev) ? "⚠️" : "ℹ️";
                    sb.append(icon).append(" [").append(cat).append("|").append(sev).append("] ").append(detail);
                    if (!sugg.isBlank()) sb.append(" → 建议：").append(sugg);
                    sb.append("\n");
                    if ("CRITICAL".equalsIgnoreCase(sev)) critical++;
                }
            }
            if (!anyIssue) sb.append("✅ 整批未发现问题\n");
            if (critical > 0) sb.append("\n⚠️ 共 ").append(critical).append(" 个严重问题，建议修改后复查\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("批量 LLM 结构校验失败: {}", e.getMessage());
            return sb.append("⚠️ LLM 结构校验调用失败（").append(e.getMessage()).append("），本次跳过\n").toString();
        }
    }

    /** 待检查章节（章号 + 正文）。 */
    private record ChapterText(int chapter, String content) {}

    private String duplicateCheck(String novelName, int chapterNumber, String content) {
        StringBuilder sb = new StringBuilder("【重复检查】\n");
        List<String> current = splitSentences(content);
        boolean found = false;

        // 1.1 章内重复：同一句出现 >=2 次
        Map<String, Integer> inChapter = new LinkedHashMap<>();
        for (String s : current) inChapter.merge(s, 1, Integer::sum);
        for (Map.Entry<String, Integer> e : inChapter.entrySet()) {
            if (e.getValue() >= 2) {
                sb.append("⚠️ 章内重复：\"").append(e.getKey()).append("\" 本章出现了 ").append(e.getValue()).append(" 次\n");
                found = true;
            }
        }

        // 1.2 跨章重复：最近 5 章（排除目标章号）里出现过相同句子
        List<String> history = recentChapters(novelName, chapterNumber, 5);
        Map<String, Integer> historySentenceCount = new HashMap<>();
        for (String h : history) {
            for (String s : splitSentences(h)) historySentenceCount.merge(s, 1, Integer::sum);
        }
        List<String> cross = new ArrayList<>();
        for (String s : current) {
            if (historySentenceCount.containsKey(s)) cross.add(s);
        }
        if (!cross.isEmpty()) {
            for (String s : cross) {
                int times = historySentenceCount.get(s);
                sb.append("⚠️ 跨章重复：\"").append(s).append("\" 最近 5 章已出现 ").append(times).append(" 次，换一种说法\n");
                found = true;
            }
        }

        // 1.3 高频表达：2-6 字短语在最近 5 章出现 >=4 次且本章也用到（抓"一个形容总用"）
        Map<String, Integer> historyNgram = countNgrams(String.join("", history), 2, 6);
        Map<String, Integer> currentNgram = countNgrams(content.replaceAll("\\s+", ""), 2, 6);
        int phraseWarnings = 0;
        for (Map.Entry<String, Integer> e : currentNgram.entrySet()) {
            Integer hist = historyNgram.get(e.getKey());
            if (hist != null && hist >= 4) {
                sb.append("⚠️ 高频表达：\"").append(e.getKey()).append("\" 最近 5 章出现 ").append(hist).append(" 次，考虑换一种描写\n");
                found = true;
                if (++phraseWarnings >= 6) {
                    sb.append("…（高频表达较多，仅列前 6 条）\n");
                    break;
                }
            }
        }

        // 1.4 重复比喻：含'像/仿佛/宛如/如同/好像'的描写片段与最近 5 章完全相同（同一比喻跨章复用）
        Set<String> historyMetaphors = new HashSet<>();
        for (String h : history) {
            Matcher m = METAPHOR.matcher(h);
            while (m.find()) {
                String frag = m.group().trim();
                if (frag.length() >= 6 && frag.length() <= 40) historyMetaphors.add(frag);
            }
        }
        Set<String> reported = new HashSet<>();
        Matcher cm = METAPHOR.matcher(content);
        while (cm.find()) {
            String frag = cm.group().trim();
            if (frag.length() >= 6 && frag.length() <= 40 && historyMetaphors.contains(frag) && !reported.contains(frag)) {
                sb.append("⚠️ 重复比喻：\"").append(frag).append("\" 最近 5 章已用过，换一种表达\n");
                reported.add(frag);
                found = true;
            }
        }

        if (!found) sb.append("✅ 未发现重复句子或高频表达\n");
        return sb.toString();
    }

    /** 按中文标点切句，过滤过短（<8 字）的碎片。 */
    private List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        for (String s : SENTENCE_SPLIT.split(text)) {
            String t = s.trim();
            if (t.length() >= 8) out.add(t);
        }
        return out;
    }

    /** 取目标章之前的最近 N 章正文（按章号倒序收集，排除目标章号）。 */
    private List<String> recentChapters(String novelName, int chapterNumber, int n) {
        List<String> out = new ArrayList<>();
        for (NovelChapterNode ch : chapterRepo.findByNovelName(novelName)) {
            if (ch.getChapterNumber() >= chapterNumber) continue;
            out.add(ch.getContent() == null ? "" : ch.getContent());
        }
        // 只保留最近 n 章
        return out.size() <= n ? out : out.subList(out.size() - n, out.size());
    }

    /** 统计文本中长度 min~max 的连续字符 n-gram 频率。 */
    private Map<String, Integer> countNgrams(String text, int min, int max) {
        Map<String, Integer> freq = new HashMap<>();
        if (text.isEmpty()) return freq;
        for (int n = min; n <= max; n++) {
            for (int i = 0; i + n <= text.length(); i++) {
                freq.merge(text.substring(i, i + n), 1, Integer::sum);
            }
        }
        return freq;
    }

    // ========== 2. LLM 结构校验 ==========

    private String qualityCheck(String novelName, int chapterNumber, String content) {
        StringBuilder sb = new StringBuilder("【LLM 结构校验】\n");
        String ctx;
        try {
            ctx = memory.buildCheckContext(novelName, chapterNumber);
        } catch (Exception e) {
            log.warn("组装校验上下文失败: {}", e.getMessage());
            ctx = "";
        }

        String prompt = "你是网络小说质检员。对照【预期上下文】检查【待检查正文】，只找问题，不要修改正文。\n"
                + "按以下 6 类找问题：\n"
                + "1) 反泄露：正文是否提前透露了反泄露清单里禁止透露的信息\n"
                + "2) 伏笔：与伏笔计划冲突，或明显该回收/该埋的伏笔没处理\n"
                + "3) 衔接：与上一章结尾、当前章细纲、前文摘要矛盾\n"
                + "4) 文风：违反本书写作档案，或出现与最近章节重复的表达、陈词滥调、与作品定位不符的叙述方式\n"
                + "5) 情绪：本章细纲标注的情绪目标是否落实\n"
                + "6) 逻辑（必须逐项核对）：①对话归属：每一句对话能否明确判断是谁说的？'他说'前一句说话人不是他、或隔两句才补主语则标记。"
                + "②主语与人称一致性：同一段'他''她'是否始终指代同一人，中途换指代对象未说明则标记。"
                + "③因果方向：'因为……所以……''……于是……''……才……'等因果结构是否颠倒了原因和结果。"
                + "④主动/被动：'把'字句'被'字句是否用反、'A把B给C'是否写成'A把C给B'。\n"
                + "输出严格JSON（不要markdown代码块）：\n"
                + "{\"issues\":[{\"severity\":\"CRITICAL|WARN|INFO\",\"category\":\"反泄露|伏笔|衔接|文风|情绪|逻辑\",\"detail\":\"具体问题描述\",\"suggestion\":\"修改建议\"}]}\n"
                + "没有问题就输出 {\"issues\":[]}\n"
                + "【预期上下文】\n" + ctx + "\n"
                + "【待检查正文】\n" + content;

        try {
            String raw = modelRouter.cloudOnly().prompt().user(prompt).call().content();
            String json = CODE_FENCE.matcher(raw == null ? "" : raw).replaceAll("").trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return sb.append("❌ LLM 返回无法解析，跳过结构校验\n").toString();
            }
            JsonNode root = objectMapper.readTree(json.substring(start, end + 1));
            JsonNode issues = root.get("issues");
            if (issues == null || !issues.isArray() || issues.isEmpty()) {
                return sb.append("✅ 未发现问题\n").toString();
            }
            int critical = 0;
            for (JsonNode it : issues) {
                String sev = it.path("severity").asText("INFO");
                String cat = it.path("category").asText("其他");
                String detail = it.path("detail").asText("未知问题");
                String sugg = it.path("suggestion").asText("");
                String icon = "CRITICAL".equalsIgnoreCase(sev) ? "❌" : "WARN".equalsIgnoreCase(sev) ? "⚠️" : "ℹ️";
                sb.append(icon).append(" [").append(cat).append("|").append(sev).append("] ").append(detail);
                if (!sugg.isBlank()) sb.append(" → 建议：").append(sugg);
                sb.append("\n");
                if ("CRITICAL".equalsIgnoreCase(sev)) critical++;
            }
            if (critical > 0) sb.append("\n⚠️ 有 ").append(critical).append(" 个严重问题，建议修改后重新检查\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("LLM 结构校验失败: {}", e.getMessage());
            return sb.append("⚠️ LLM 结构校验调用失败（").append(e.getMessage()).append("），本次跳过\n").toString();
        }
    }
}
