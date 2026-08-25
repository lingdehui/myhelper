package com.example.myhelper.novel;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 小说创作工具集。
 *
 * <p>所有方法以 novelName 作为 namespace 隔离键，数据存在 Neo4j NovelCharacter/NovelChapter/NovelPlotThread
 * 和 Qdrant novel-memory 集合中，与主项目的 Device/UserPreference/Episodes 数据完全隔离。
 * 只有 AI 显式调用这些 @Tool 方法时，小说数据空间才会被访问。</p>
 *
 * <p>使用流程（大纲先行）：</p>
 * <ol>
 *   <li>set_outline(...) - 写正文前先定大纲（世界观/主线/分章简要），存图谱+向量</li>
 *   <li>add_character(...) + add_character_relationship(...) - 大纲阶段就定好人物与人物关系</li>
 *   <li>build_writing_context(...) - 写每章前取上下文（大纲+人物关系+前文摘要+伏笔）</li>
 *   <li>add_chapter(...) - 写正文（自动落文件，每章一个 .txt）</li>
 *   <li>set_chapter_summary(...) - 每章摘要存图谱+向量</li>
 * </ol>
 */
@Component
public class NovelToolService {

    private final NovelMemoryService memory;
    private final NovelQualityChecker qualityChecker;
    private final NovelChapterCheckService chapterCheckService;

    public NovelToolService(NovelMemoryService memory,
                            NovelQualityChecker qualityChecker,
                            NovelChapterCheckService chapterCheckService) {
        this.memory = memory;
        this.qualityChecker = qualityChecker;
        this.chapterCheckService = chapterCheckService;
    }

    // ========== 状态查询 ==========

    @Tool(description = "查询小说的整体状态：已写多少章、多少人物、未回收伏笔数。新手引导第一步。")
    public String getNovelState(
            @ToolParam(description = "小说名称") String novelName) {
        return memory.getNovelStateAsPrompt(novelName);
    }

    @Tool(description = "删除一部小说的全部数据（大纲/分卷/章节/人物/情节线 + 向量 + 已导出文件）。" +
            "用于彻底重写或清理旧稿，删除后不可恢复。")
    public String deleteNovel(
            @ToolParam(description = "小说名称") String novelName) {
        return memory.deleteNovel(novelName);
    }

    // ========== 大纲管理 ==========

    @Tool(description = "保存/更新小说粗大纲。写正文前必须先完成这一步。" +
            "worldview 为世界观设定，mainPlot 为主线大纲，totalChapters 为预设总章数（默认 1000）。" +
            "粗大纲存入图谱和向量库。分卷细节请用 set_volume 逐卷补充。")
    public String setOutline(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "世界观/设定") String worldview,
            @ToolParam(description = "主线大纲") String mainPlot,
            @ToolParam(description = "预设总章数，默认 1000") int totalChapters) {
        memory.setOutline(novelName, worldview, mainPlot, totalChapters);
        return "✅ 粗大纲已保存（世界观 + 主线 + 总章数 " + totalChapters + "），已同步到图谱和向量库";
    }

    @Tool(description = "查看小说粗大纲（世界观/主线/预设总章数）。写新章节前可用 build_writing_context 一次取全。")
    public String getOutline(
            @ToolParam(description = "小说名称") String novelName) {
        String outline = memory.getOutlineAsPrompt(novelName);
        return outline.isEmpty() ? "❌ 尚未设置大纲，请先调用 set_outline" : outline;
    }

    // ========== 分卷管理 ==========

    @Tool(description = "保存/更新某一卷的大纲。长篇（默认1000章）不要一次写完全部大纲，按卷切分、一次一卷。" +
            "volumeNumber 卷序号从1开始，chapterStart/chapterEnd 为本章起止章号（含）。" +
            "mainPlot 本卷主线，chapterOutlines 本卷分章简要（每行一章），" +
            "foreshadowings 本卷伏笔计划（每行一条，如 '第3章埋：xxx，第20章回收'）。伏笔要提前规划好，不要等写每章时临时想。" +
            "secrets 本卷反泄露清单（每行一条，如 '沈砚身份：第45章前绝不可提'），写正文时注入防止提前剧透。")
    public String setVolume(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "卷序号，从1开始") int volumeNumber,
            @ToolParam(description = "卷标题，如 第一卷：初入都市") String title,
            @ToolParam(description = "本卷起始章号（含）") int chapterStart,
            @ToolParam(description = "本卷结束章号（含）") int chapterEnd,
            @ToolParam(description = "本卷主线") String mainPlot,
            @ToolParam(description = "本卷分章简要，每行一章，格式：'第N章：一句话细纲（出场：人物A、人物B）'，出场人物用于写该章时精确筛人物卡") String chapterOutlines,
            @ToolParam(description = "本卷伏笔计划，每行一条") String foreshadowings,
            @ToolParam(description = "本卷反泄露清单（可选），每行一条，如 '沈砚身份：第45章前绝不可提'") String secrets) {
        if (novelName == null || novelName.isBlank()) {
            return "❌ 缺少 novelName：set_volume 必须携带小说名称，否则分卷会丢失归属、写正文时无法读取。请带上书名重新调用";
        }
        memory.setVolume(novelName, volumeNumber, title, chapterStart, chapterEnd,
                mainPlot, chapterOutlines, foreshadowings, secrets);
        return "✅ 第" + volumeNumber + "卷《" + title + "》已保存（第" + chapterStart + "-" + chapterEnd + "章）";
    }

    @Tool(description = "查看小说所有分卷大纲（各卷标题、章节范围、主线）。")
    public String getVolumes(
            @ToolParam(description = "小说名称") String novelName) {
        String volumes = memory.getVolumesAsPrompt(novelName);
        return volumes.isEmpty() ? "❌ 尚未设置分卷，请先调用 set_volume" : volumes;
    }

    // ========== 人物管理 ==========

    @Tool(description = "添加小说人物。人物是后续写作的基础，至少需要主角。role 为主角/配角/反派/路人等。")
    public String addCharacter(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "人物姓名") String name,
            @ToolParam(description = "角色定位，如 主角/配角/反派/路人") String role,
            @ToolParam(description = "性格描述，一句话") String personality,
            @ToolParam(description = "外貌描述，可选") String appearance,
            @ToolParam(description = "背景故事，可选") String background) {
        NovelCharacterNode c = memory.addCharacter(novelName, name, role, personality, appearance, background);
        return "✅ 已添加人物: " + c.getName() + "（" + c.getRole() + "）";
    }

    @Tool(description = "在两个已有人物之间建立关系。如 暗恋/恋爱/敌对/师徒/父子/朋友。")
    public String addCharacterRelationship(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "人物A姓名") String characterA,
            @ToolParam(description = "人物B姓名") String characterB,
            @ToolParam(description = "关系类型，如 暗恋/恋爱/敌对/师徒/父子/朋友") String relationType,
            @ToolParam(description = "关系描述，简短说明") String description) {
        memory.addRelationship(novelName, characterA, characterB, relationType, description);
        return "✅ 已建立关系: " + characterA + " -[" + relationType + "]-> " + characterB;
    }

    @Tool(description = "获取小说的所有人物列表及设定。写新章节前应调用以获取人物上下文。")
    public String getCharacters(
            @ToolParam(description = "小说名称") String novelName) {
        return memory.getCharactersAsPrompt(novelName);
    }

    @Tool(description = "更新人物设定。如故事进展中人物性格变化、外貌变化、揭示新背景等。" +
            "参数为 null 的字段保持原值不变。")
    public String updateCharacter(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "人物姓名") String name,
            @ToolParam(description = "新的性格描述，不修改填 null") String personality,
            @ToolParam(description = "新的外貌描述，不修改填 null") String appearance,
            @ToolParam(description = "新的背景故事，不修改填 null") String background) {
        memory.updateCharacter(novelName, name, personality, appearance, background);
        return "✅ " + name + " 已更新";
    }

    @Tool(description = "更新两个人物之间的关系。如 暗恋→恋爱、友好→敌对 等。")
    public String updateRelationship(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "人物A姓名") String characterA,
            @ToolParam(description = "人物B姓名") String characterB,
            @ToolParam(description = "新的关系类型，如 恋爱/敌对/分手") String newType,
            @ToolParam(description = "新的关系描述") String newDescription) {
        memory.updateRelationship(novelName, characterA, characterB, newType, newDescription);
        return "✅ " + characterA + " -[" + newType + "]-> " + characterB + " 已更新";
    }

    // ========== 章节管理 ==========

    @Tool(description = "一键获取写下一章所需的完整上下文。包括大纲（世界观/主线/分章简要）、人物设定与关系、最近章节摘要、未回收伏笔。" +
            "这是写每章前最核心的工具，用一次就行，无需逐个调用 getCharacters/getRecentSummaries/getUnresolvedPlotThreads。" +
            "新写或重写每一章都必须先调用它；返回内容已内置文笔要求（有画面感）、比喻要求（不跨章复用、不用俗套比喻）、" +
            "伏笔回看、反泄露清单等写作约束。" +
            "重写旧章时（如从第6章开始重写），必须传 chapterNumber 指定目标章号，上下文会按该章所在卷生成；不传则默认取下一章上下文。")
    public String buildWritingContext(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "目标章号（重写旧章时必传，例如 6），不传则默认写下一章") Integer chapterNumber) {
        if (novelName == null || novelName.isBlank()) {
            novelName = memory.resolveNovelName(novelName);
        }
        if (novelName == null) {
            return "❌ 无法确定小说名称：请带上完整小说名（例如\"心理师专治修仙心魔劫\"）重新调用本工具。";
        }
        return memory.buildWritingContext(novelName, chapterNumber);
    }

    @Tool(description = "添加/保存一个章节。每章写完立即调用此工具存入记忆。" +
            "content 为章节完整正文。保存后会自动写入图谱，并落盘到文件（每章一个 .txt），并自动触发章节质量检查。" +
            "同章号重复保存会覆盖旧内容（用于重写）。重写章节的固定流程：get_chapter 读该章原文 → build_writing_context 取上下文 → " +
            "在原文基础上重写（保持与前后章衔接、不改变大纲细纲）→ add_chapter 用新内容覆盖保存。")
    public String addChapter(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "章节序号，从1开始") int chapterNumber,
            @ToolParam(description = "章节标题") String title,
            @ToolParam(description = "章节完整正文") String content) {
        memory.addChapter(novelName, chapterNumber, title, content);
        String base = "✅ 第" + chapterNumber + "章《" + title + "》已保存（" + content.length() + "字），正文已落盘到文件";
        String report = chapterCheckService.onChapterSaved(novelName, chapterNumber, content);
        return report == null || report.isBlank() ? base : base + report;
    }

    @Tool(description = "设置章节的摘要、人物调度和情节伏笔标记。每章写完应调用此工具标记关键信息。" +
            "summary 为章节摘要（200字），characters 为本章出场人物（逗号分隔），" +
            "plotThreads 为本章涉及的情节线（逗号分隔）。")
    public String setChapterSummary(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "章节序号") int chapterNumber,
            @ToolParam(description = "章节摘要，200字以内") String summary,
            @ToolParam(description = "本章出场人物，逗号分隔") String characters,
            @ToolParam(description = "本章情节线，逗号分隔") String plotThreads) {
        if (novelName == null || novelName.isBlank()) {
            novelName = memory.resolveNovelName(novelName);
        }
        if (novelName == null) {
            return "❌ 无法确定小说名称：请带上完整小说名（例如\"心理师专治修仙心魔劫\"）重新调用本工具。";
        }
        memory.setChapterSummary(novelName, chapterNumber, summary, characters, plotThreads);
        return "✅ 第" + chapterNumber + "章摘要已记录 | 人物: " + characters + " | 情节: " + plotThreads;
    }

    @Tool(description = "获取小说最近 N 章的摘要，用于写新章节时回顾前文。建议写每章前调用看前3-5章。")
    public String getRecentSummaries(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "获取最近几章，建议3-5") int count) {
        return memory.getRecentSummariesAsPrompt(novelName, count);
    }

    @Tool(description = "获取小说的所有章节列表（仅标题和状态，不含正文）。")
    public String getAllChapters(
            @ToolParam(description = "小说名称") String novelName) {
        List<NovelChapterNode> chapters = memory.getAllChapters(novelName);
        if (chapters.isEmpty()) return "暂无章节";
        StringBuilder sb = new StringBuilder("【" + novelName + "】章节列表：\n");
        for (NovelChapterNode ch : chapters) {
            sb.append(String.format("  第%d章 %s [%s] %d字\n",
                    ch.getChapterNumber(), ch.getTitle(), ch.getStatus(), ch.getWordCount()));
        }
        return sb.toString();
    }

    @Tool(description = "获取指定章节的完整正文。用于回顾旧章节；重写章节时第一步必须先调它读原文，再在原文基础上重写。")
    public String getChapter(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "章节序号") int chapterNumber) {
        return memory.getChapter(novelName, chapterNumber)
                .map(ch -> "第" + chapterNumber + "章《" + ch.getTitle() + "》\n\n" + ch.getContent())
                .orElse("❌ 未找到第" + chapterNumber + "章");
    }

    @Tool(description = "修改已存在的章节内容。用于润色或重写。novelName 必须携带完整小说名，每次都要传！保存后会自动触发章节质量检查（每6章合并检查逻辑/文风等）。")
    public String updateChapter(
            @ToolParam(description = "小说名称，必须完整传入，缺失会导致更新失败") String novelName,
            @ToolParam(description = "章节序号") int chapterNumber,
            @ToolParam(description = "新的章节完整正文") String content) {
        if (novelName == null || novelName.isBlank()) {
            novelName = memory.resolveNovelName(novelName);
        }
        if (novelName == null) {
            return "❌ 无法确定小说名称：请带上完整小说名（例如\"心理师专治修仙心魔劫\"）重新调用本工具。";
        }
        memory.updateChapter(novelName, chapterNumber, content);
        String base = "✅ 第" + chapterNumber + "章已更新（" + content.length() + "字），正文已落盘到文件";
        String report = chapterCheckService.onChapterSaved(novelName, chapterNumber, content);
        return report == null || report.isBlank() ? base : base + report;
    }

    @Tool(description = "检查某一章的正文质量（写完/重写后抽查）：重复表达（与最近5章重复的句子、每章都用的高频描写、重复比喻）、" +
            "反泄露违规、伏笔冲突、剧情衔接、文风偏离、情绪目标落实。" +
            "建议每写 5-10 章抽查一次最近章节：查出有问题的章节统一轻微修改（补情节或改词），不重写整章。" +
            "content 可留空，为空时自动读取该章已保存的正文。")
    public String verifyChapter(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "章节序号") int chapterNumber,
            @ToolParam(description = "要检查的正文（可选，留空则读取已保存的正文）") String content) {
        String text = (content == null || content.isBlank())
                ? memory.getChapter(novelName, chapterNumber).map(ch -> ch.getContent()).orElse("")
                : content;
        return qualityChecker.verify(novelName, chapterNumber, text);
    }

    // ========== 情节线管理 ==========

    @Tool(description = "添加一条情节线/伏笔。type 为 MAIN/A_SUB/B_SUB/FORESHADOW。planedChapter 为埋设章节号。")
    public String addPlotThread(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "情节线名称，简短标题") String threadName,
            @ToolParam(description = "类型: MAIN(主线)/A_SUB(A支线)/B_SUB(B支线)/FORESHADOW(伏笔)") String type,
            @ToolParam(description = "情节描述") String description,
            @ToolParam(description = "在哪一章埋下伏笔") int plantedChapter) {
        memory.addPlotThread(novelName, threadName, type, description, plantedChapter);
        return "✅ 已添加情节线: [" + type + "] " + threadName + "（埋于第" + plantedChapter + "章）";
    }

    @Tool(description = "将一条情节线标记为已回收/已解决。resolvedChapter 为回收章节号。")
    public String resolvePlotThread(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "情节线名称") String threadName,
            @ToolParam(description = "在哪一章回收") int resolvedChapter) {
        memory.resolvePlotThread(novelName, threadName, resolvedChapter);
        return "✅ 情节线 '" + threadName + "' 已回收（第" + resolvedChapter + "章）";
    }

    @Tool(description = "获取所有未回收的伏笔/情节线。写新章节前应调用，避免遗忘线索。")
    public String getUnresolvedPlotThreads(
            @ToolParam(description = "小说名称") String novelName) {
        return memory.getUnresolvedThreadsAsPrompt(novelName);
    }

    // ========== 上下文检索 ==========

    @Tool(description = "在已写章节中语义搜索相关内容。用于查找旧章节中的人物细节、事件等。")
    public String searchNovelContext(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "搜索查询，如 '张三和李四的第一次见面'") String query) {
        return memory.searchRelevantContext(novelName, query);
    }

    // ========== 人物状态变更 ==========

    @Tool(description = "变更人物状态。如角色死亡/失踪/离开等。status 为 ALIVE/DEAD/ABSENT/INACTIVE。")
    public String updateCharacterStatus(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "人物姓名") String name,
            @ToolParam(description = "新状态: ALIVE/DEAD/ABSENT/INACTIVE") String newStatus) {
        boolean updated = memory.updateCharacterStatus(novelName, name, newStatus);
        return updated ? "✅ " + name + " 状态已变为 " + newStatus
                       : "❌ 未找到人物: " + name;
    }

    // ========== 导出 ==========

    @Tool(description = "把已写的所有章节正文导出成一个完整的 .txt 文件，返回文件路径，供用户查看质量。")
    public String exportNovel(
            @ToolParam(description = "小说名称") String novelName) {
        return memory.exportNovel(novelName);
    }
}
