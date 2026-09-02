package com.example.myhelper.novel;

import com.example.myhelper.config.ModelRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动续写的后端编排器。
 *
 * <p>模型只负责计划、草稿、修订和摘要；是否提交由此服务依据结构化质量门禁决定。
 * 因而草稿、失败重写和模型异常都不会进入正式章节记忆。</p>
 */
@Service
public class NovelWritingPipeline {

    private static final Logger log = LoggerFactory.getLogger(NovelWritingPipeline.class);
    private static final int MIN_USABLE_DRAFT_LENGTH = 300;

    private final ModelRouter modelRouter;
    private final NovelMemoryService memory;
    private final NovelQualityChecker qualityChecker;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 质量不过时允许的定向修订次数；超过后停下，避免无止境消耗。 */
    @Value("${novel.quality.max-revisions:2}")
    private int maxRevisions = 2;

    public NovelWritingPipeline(ModelRouter modelRouter, NovelMemoryService memory,
                                NovelQualityChecker qualityChecker) {
        this.modelRouter = modelRouter;
        this.memory = memory;
        this.qualityChecker = qualityChecker;
    }

    /** 写一章并且只有通过质量门禁时才提交。 */
    public WritingResult writeAndCommit(String novelName, int chapterNumber) {
        String resolvedName = memory.resolveNovelName(novelName);
        if (resolvedName == null) return WritingResult.failed(chapterNumber, "无法确定小说名称。", null);
        int targetChapter = chapterNumber > 0 ? chapterNumber : (int) memory.getChapterCount(resolvedName) + 1;

        NovelChapterPlan plan = createPlan(resolvedName, targetChapter);
        String context = memory.buildWritingContext(resolvedName, targetChapter);
        String draft = writeDraft(resolvedName, plan, context, null, null);
        if (!isUsableDraft(draft)) {
            return WritingResult.failed(targetChapter, "模型没有返回可用正文，本章未保存。", null);
        }

        NovelQualityAssessment assessment = null;
        int attempts = 0;
        while (true) {
            assessment = qualityChecker.assess(resolvedName, targetChapter, draft);
            if (assessment.approved()) break;
            if (assessment.decision() == NovelQualityAssessment.Decision.REVIEW_UNAVAILABLE) {
                return WritingResult.failed(targetChapter, "质量审阅不可用，为防止低质量正文进入记忆，本章未保存。", assessment);
            }
            if (attempts >= maxRevisions) {
                return WritingResult.failed(targetChapter,
                        "已完成 " + attempts + " 次定向修订仍未通过质量门禁，本章未保存。", assessment);
            }
            attempts++;
            draft = writeDraft(resolvedName, plan, context, draft, assessment.revisionBrief());
            if (!isUsableDraft(draft)) {
                return WritingResult.failed(targetChapter, "定向修订没有返回可用正文，本章未保存。", assessment);
            }
        }

        ChapterState state = extractState(plan, draft);
        memory.commitApprovedChapter(resolvedName, plan, draft, state.summary(), state.characters(),
                state.plotThreads(), assessment.score());
        log.info("小说章节质量提交成功: 《{}》第{}章，{} 分，修订 {} 次", resolvedName, targetChapter,
                assessment.score(), attempts);
        return WritingResult.published(targetChapter, plan.title(), draft.length(), attempts, assessment);
    }

    private NovelChapterPlan createPlan(String novelName, int chapterNumber) {
        String context = memory.buildWritingContext(novelName, chapterNumber);
        String prompt = "你是小说章节策划编辑。根据以下上下文，为第" + chapterNumber + "章制定一份可执行计划。"
                + "不得编造与上下文冲突的新设定，不得提前揭露反泄露清单内容。"
                + "严格输出 JSON，不要 markdown："
                + "{\"title\":\"章节标题\",\"objective\":\"本章必须完成的推进\","
                + "\"conflict\":\"具体阻力\",\"revealOrChange\":\"信息、关系或局势的变化\","
                + "\"emotion\":\"情绪推进\",\"cast\":[\"人物\"],\"plotThreads\":[\"情节线\"],"
                + "\"endHook\":\"章末未解决行动/选择/信息\"}\n" + context;
        JsonNode root = parseJson(callModel(prompt));
        if (root == null) return new NovelChapterPlan(chapterNumber, "第" + chapterNumber + "章", "推进当前细纲",
                "让既有矛盾形成具体阻力", "让事件产生可见后果", "符合当前人物处境", List.of(), List.of(), "留下下一章必须回应的问题");
        return new NovelChapterPlan(chapterNumber, root.path("title").asText(""), root.path("objective").asText(""),
                root.path("conflict").asText(""), root.path("revealOrChange").asText(""),
                root.path("emotion").asText(""), stringList(root.get("cast")), stringList(root.get("plotThreads")),
                root.path("endHook").asText(""));
    }

    private String writeDraft(String novelName, NovelChapterPlan plan, String context, String previousDraft,
                              String revisionBrief) {
        StringBuilder prompt = new StringBuilder("你是这部小说的正文作者。严格依据【写作上下文】和【本章计划】写完整正文。"
                + "保持人物、时间线、秘密和因果一致；用事件、动作和对话呈现信息，不解释你的写作。"
                + "只输出正文，不要标题、提纲、注释、markdown 或质量说明。\n")
                .append(context).append("\n").append(plan.asPrompt());
        if (previousDraft != null && revisionBrief != null) {
            prompt.append("【上一版草稿】\n").append(previousDraft).append("\n【必须修复的问题】\n")
                    .append(revisionBrief)
                    .append("\n重写时必须保持本章计划的目标、人物和关键变化，不得为了回避问题删掉剧情推进。\n");
        }
        return cleanProse(callModel(prompt.toString()));
    }

    /** 质量合格后再生成供图谱和向量检索使用的摘要状态；解析失败时用章节计划保底。 */
    private ChapterState extractState(NovelChapterPlan plan, String content) {
        String prompt = "从以下小说正文提取记忆，不评价、不补写。严格输出 JSON："
                + "{\"summary\":\"不超过200字、含结果的摘要\",\"characters\":[\"实际出场人物\"],"
                + "\"plotThreads\":[\"实际涉及或推进的情节线\"]}\n" + plan.asPrompt() + "\n正文：\n" + content;
        JsonNode root = parseJson(callModel(prompt));
        if (root == null || root.path("summary").asText().isBlank()) {
            String fallbackSummary = truncate(plan.objective() + "；" + plan.conflict() + "；" + plan.revealOrChange(), 200);
            return new ChapterState(fallbackSummary, String.join(",", plan.cast()), String.join(",", plan.plotThreads()));
        }
        return new ChapterState(truncate(root.path("summary").asText(), 200),
                String.join(",", stringList(root.get("characters"))),
                String.join(",", stringList(root.get("plotThreads"))));
    }

    private String callModel(String prompt) {
        try {
            String content = modelRouter.chat().prompt().user(prompt).call().content();
            return content == null ? "" : content.trim();
        } catch (Exception e) {
            log.warn("小说写作模型调用失败: {}", e.getMessage());
            return "";
        }
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.replaceAll("(?m)^\\s*```(?:json)?\\s*$", "").trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return objectMapper.readTree(cleaned.substring(start, end + 1));
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText("").trim();
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }

    private boolean isUsableDraft(String draft) {
        return draft != null && draft.strip().length() >= MIN_USABLE_DRAFT_LENGTH;
    }

    private String cleanProse(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?m)^\\s*```(?:text|markdown)?\\s*$", "").trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }

    private record ChapterState(String summary, String characters, String plotThreads) { }

    /** 调用方只需判断 published；未发布时正文不会存在于正式章节记忆中。 */
    public record WritingResult(int chapterNumber, String title, int wordCount, int revisions,
                                boolean published, String message, NovelQualityAssessment assessment) {
        static WritingResult published(int chapterNumber, String title, int wordCount, int revisions,
                                       NovelQualityAssessment assessment) {
            return new WritingResult(chapterNumber, title, wordCount, revisions, true,
                    "已通过质量门禁并提交", assessment);
        }

        static WritingResult failed(int chapterNumber, String message, NovelQualityAssessment assessment) {
            return new WritingResult(chapterNumber, "", 0, 0, false, message, assessment);
        }

        public String asUserMessage(String novelName) {
            StringBuilder output = new StringBuilder();
            if (published) {
                output.append("✅ 《").append(novelName).append("》第").append(chapterNumber).append("章《")
                        .append(title).append("》已通过质量门禁并保存（").append(wordCount).append("字，")
                        .append("定向修订 ").append(revisions).append(" 次）。\n");
            } else {
                output.append("⚠️ 《").append(novelName).append("》第").append(chapterNumber).append("章未保存：")
                        .append(message).append("\n");
            }
            if (assessment != null) output.append(assessment.asReport(novelName, chapterNumber));
            return output.toString();
        }
    }
}
