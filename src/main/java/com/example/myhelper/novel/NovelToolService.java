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

    public NovelToolService(NovelMemoryService memory) {
        this.memory = memory;
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
            "foreshadowings 本卷伏笔计划（每行一条，如 '第3章埋：xxx，第20章回收'）。伏笔要提前规划好，不要等写每章时临时想。")
    public String setVolume(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "卷序号，从1开始") int volumeNumber,
            @ToolParam(description = "卷标题，如 第一卷：初入都市") String title,
            @ToolParam(description = "本卷起始章号（含）") int chapterStart,
            @ToolParam(description = "本卷结束章号（含）") int chapterEnd,
            @ToolParam(description = "本卷主线") String mainPlot,
            @ToolParam(description = "本卷分章简要，每行一章，格式：'第N章：一句话细纲（出场：人物A、人物B）'，出场人物用于写该章时精确筛人物卡") String chapterOutlines,
            @ToolParam(description = "本卷伏笔计划，每行一条") String foreshadowings) {
        memory.setVolume(novelName, volumeNumber, title, chapterStart, chapterEnd,
                mainPlot, chapterOutlines, foreshadowings);
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
            "这是写每章前最核心的工具，用一次就行，无需逐个调用 getCharacters/getRecentSummaries/getUnresolvedPlotThreads。")
    public String buildWritingContext(
            @ToolParam(description = "小说名称") String novelName) {
        return memory.buildWritingContext(novelName);
    }

    @Tool(description = "添加/保存一个章节。每章写完立即调用此工具存入记忆。" +
            "content 为章节完整正文。保存后会自动写入图谱，并落盘到文件（每章一个 .txt）。同章号重复保存会覆盖旧内容。")
    public String addChapter(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "章节序号，从1开始") int chapterNumber,
            @ToolParam(description = "章节标题") String title,
            @ToolParam(description = "章节完整正文") String content) {
        memory.addChapter(novelName, chapterNumber, title, content);
        return "✅ 第" + chapterNumber + "章《" + title + "》已保存（" + content.length() + "字），正文已落盘到文件";
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

    @Tool(description = "获取指定章节的完整正文。用于回顾或修改旧章节。")
    public String getChapter(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "章节序号") int chapterNumber) {
        return memory.getChapter(novelName, chapterNumber)
                .map(ch -> "第" + chapterNumber + "章《" + ch.getTitle() + "》\n\n" + ch.getContent())
                .orElse("❌ 未找到第" + chapterNumber + "章");
    }

    @Tool(description = "修改已存在的章节内容。用于润色或重写。")
    public String updateChapter(
            @ToolParam(description = "小说名称") String novelName,
            @ToolParam(description = "章节序号") int chapterNumber,
            @ToolParam(description = "新的章节完整正文") String content) {
        memory.updateChapter(novelName, chapterNumber, content);
        return "✅ 第" + chapterNumber + "章已更新（" + content.length() + "字）";
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
