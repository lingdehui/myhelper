package com.example.desktopbrain.service;

import com.example.desktopbrain.memory.vector.episode.Episode;
import com.example.desktopbrain.memory.vector.episode.EpisodeCacheService;
import com.example.desktopbrain.memory.vector.episode.ToolCallLog;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 三阶段工具规划器（升级版：三层缓存 + Episode 经验学习）。
 *
 * <p>借鉴 ExpeL/MUSE 经验学习思想，在原有内存缓存基础上增加 Episode 向量检索层，
 * 实现跨重启的语义级方案复用。</p>
 *
 * <h3>三层缓存查询逻辑</h3>
 * <pre>
 * plan(userInput, allTools):
 *   [Layer 1] 内存缓存（ConcurrentHashMap，key=normalizeKey(userInput)）
 *             命中 → 返回 PlanResult(fromCache=true, episodeId=entry.episodeId)
 *             不查 Qdrant，避免重复 embed（200-500ms）
 *   [Layer 2] Episode 向量检索（Qdrant episodes collection）
 *             embed(userInput) → search(top_k=3, score_threshold=0.65,
 *                                        filter: archived=false AND stability>=0.6)
 *             命中 → 回填内存缓存 → 返回 PlanResult(fromCache=true, episodeId=ep.id)
 *   [Layer 3] AI 规划（现有 doPlan 逻辑）
 *             返回 PlanResult(fromCache=false, episodeId=null)
 * </pre>
 *
 * <h3>淘汰策略（两层独立）</h3>
 * <ul>
 *   <li>内存缓存：连续失败 3 次 → 清除（重启后也清空）</li>
 *   <li>Episode：failureCount+1，stability&lt;0.3 或连续失败 3 次 → archived=true
 *       （不被检索但数据保留，跨重启持久化）</li>
 * </ul>
 *
 * <h3>反馈学习</h3>
 * <ul>
 *   <li>命中缓存+成功 → 内存重置失败计数 + 异步 incrementSuccess（successCount+1，不重置 failureCount，让 stability 自然衰退）</li>
 *   <li>命中缓存+失败 → 内存失败计数+1 + 异步 recordFailure（达阈值 archive）</li>
 *   <li>首次规划+成功 → 内存写入 + 异步 recordSuccess（新建 Episode，返回 episodeId 回填内存缓存）</li>
 *   <li>首次请求就失败 → episodeId=null，所有回调 no-op（Episode 库只存成功方案）</li>
 * </ul>
 */
@Component
public class ToolPlanner {

    private final ChatClient chatClient;
    private final EpisodeCacheService episodeCacheService;
    private static final int FAILURE_THRESHOLD = 3;

    /** 脚本化阈值：与 EpisodeCacheService 保持一致，successCount≥此值 且 stability>0.9 时 canScript=true */
    @Value("${qdrant.episode.script-success-threshold:5}")
    private int scriptSuccessThreshold;

    /** 缓存条目：方案 + episodeId + episode引用 + 连续失败次数 */
    private static class CacheEntry {
        final List<String> selectedToolNames;
        final List<String> missingDescriptions;
        final String episodeId;  // 关联的 Qdrant episode id（可能为 null）
        volatile Episode episode;   // episode 引用（含 successLesson/failureLesson，可能为 null）；volatile 因 onCacheHitSuccess 会更新 canScript
        int failureCount;
        CacheEntry(List<String> selectedToolNames, List<String> missingDescriptions,
                   String episodeId, Episode episode) {
            this.selectedToolNames = selectedToolNames;
            this.missingDescriptions = missingDescriptions;
            this.episodeId = episodeId;
            this.episode = episode;
            this.failureCount = 0;
        }
    }

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private static final List<Category> CATEGORIES = List.of(
        new Category("1", "窗口管理", "聚焦/列表/最小化/最大化/恢复/窗口位置",
            new String[]{"focusWindow", "listWindows", "getActiveWindowTitle",
                "minimizeWindow", "maximizeWindow", "restoreWindow", "getWindowBounds"}),
        new Category("2", "鼠标操作", "移动/左击/双击/右击/中击/滚动/拖拽",
            new String[]{"mouseMove", "getMousePosition", "leftClick", "doubleClick",
                "rightClick", "middleClick", "mouseScroll", "mouseDrag", "scrollUntilPixelColor"}),
        new Category("3", "键盘输入", "打字/按键/组合键/粘贴输入",
            new String[]{"typeText", "typeTextViaClipboard", "pressKey", "pressKeyCombination"}),
        new Category("4", "屏幕OCR", "全屏/区域/窗口/显示器 文字识别+点击",
            new String[]{"ocrScreen", "ocrRegion", "ocrMonitor", "ocrWindow",
                "findTextOnScreen", "clickTextOnScreen"}),
        new Category("5", "UI元素", "查找控件/列出交互元素/树结构/读取文本/展开折叠",
            new String[]{"findElement", "isElementPresent", "waitForElement", "listInteractiveElements",
                "dumpWindowTree", "getFocusedElement", "getElementAtPoint",
                "getTextContent", "expandElement", "collapseElement"}),
        new Category("6", "像素监控", "像素颜色/等待变化/等待稳定/显示器列表",
            new String[]{"findPixelColor", "waitForPixelColor", "findPixelInRegion",
                "waitForScreenChange", "waitForScreenStable", "listMonitors"}),
        new Category("7", "剪贴板", "读取/设置剪贴板文本",
            new String[]{"getClipboard", "setClipboard"}),
        new Category("8", "联系人匹配", "模糊匹配微信好友名字（拼音/编辑距离）",
            new String[]{"findFriend"}),
        new Category("9", "技能查询", "列出本地技能列表",
            new String[]{"listLocalSkills"}),
        new Category("10", "通用", "暂停/延时",
            new String[]{"sleep"})
    );

    private static final List<String> FALLBACK_TOOLS = List.of(
            "sleep", "listWindows", "getActiveWindowTitle", "getMousePosition",
            "focusWindow", "leftClick", "doubleClick", "rightClick",
            "typeText", "typeTextViaClipboard", "pressKey", "pressKeyCombination",
            "mouseMove", "mouseScroll", "clickTextOnScreen", "findTextOnScreen"
    );

    public ToolPlanner(ChatClient.Builder chatClientBuilder, EpisodeCacheService episodeCacheService) {
        this.chatClient = chatClientBuilder
                .defaultSystem("你是工具规划器，根据用户请求选择所需的能力分类，只返回编号。")
                .build();
        this.episodeCacheService = episodeCacheService;
    }

    /**
     * 规划结果。
     *
     * @param selectedToolNames   选中的工具名列表
     * @param missingDescriptions 缺失的工具描述
     * @param fromCache           是否来自缓存（内存或 Episode）
     * @param episodeId           关联的 Episode id（内存命中或 Episode 命中时非 null；AI 规划时为 null）
     * @param episode             命中的完整 Episode（含 successLesson/failureLesson/toolCalls）；
     *                            AI 规划时为 null。调用方可据此增强 prompt（参考计划+经验提示）
     */
    public record PlanResult(
            List<String> selectedToolNames,
            List<String> missingDescriptions,
            boolean fromCache,
            String episodeId,
            Episode episode
    ) {
        /** AI 规划的便捷工厂（episode=null） */
        public static PlanResult ofAIPlan(List<String> tools, List<String> missing) {
            return new PlanResult(tools, missing, false, null, null);
        }
    }

    private record Category(String id, String name, String desc, String[] tools) {}

    /**
     * 正常规划：三层缓存查询（内存 → Episode → AI 规划）。
     *
     * <p>命中缓存时 PlanResult.episode 非 null（含 successLesson/failureLesson/toolCalls），
     * 调用方（DesktopBrainApplication）可据此让 AI 判断可用性 + 带参考计划执行。</p>
     */
    public PlanResult plan(String userInput, ToolCallback[] allTools) {
        String key = normalizeKey(userInput);

        // ===== Layer 1: 内存缓存（精确匹配，最快）=====
        CacheEntry entry = cache.get(key);
        if (entry != null) {
            System.out.println("💾 命中内存缓存（失败计数: " + entry.failureCount + "/" + FAILURE_THRESHOLD
                    + (entry.episodeId != null ? ", episode=" + entry.episodeId.substring(0, 8) + "..." : "") + "）");
            return new PlanResult(entry.selectedToolNames, entry.missingDescriptions,
                    true, entry.episodeId, entry.episode);
        }

        // ===== Layer 2: Episode 向量检索（语义匹配，跨重启持久化）=====
        Optional<Episode> ep = episodeCacheService.findSimilarEpisode(userInput);
        if (ep.isPresent()) {
            Episode episode = ep.get();
            // 回填内存缓存（带 episode 引用，含 lesson），下次相同输入直接内存命中
            cache.put(key, new CacheEntry(episode.selectedToolNames(), episode.missingDescriptions(),
                    episode.id(), episode));
            System.out.println("💾 命中 Episode 缓存（稳定度: " + String.format("%.2f", episode.computedStability())
                    + ", episode=" + episode.id().substring(0, 8) + "...）");
            return new PlanResult(episode.selectedToolNames(), episode.missingDescriptions(),
                    true, episode.id(), episode);
        }

        // ===== Layer 3: AI 规划 =====
        return doPlan(userInput, allTools, null);
    }

    /**
     * 缓存命中但失败时调用（带 AI 归因结果）。
     *
     * <p>用户核心逻辑："按问题判断是否是计划问题→是计划问题→失败数+1→不是计划问题→分段继续"。</p>
     *
     * <p>归因结果决定惩罚策略：
     * <ul>
     *   <li>isPlanIssue=true（计划问题）：内存失败计数+1（达阈值清除）+ Episode failureCount+1（达阈值 archive）</li>
     *   <li>isPlanIssue=false（环境问题）：不惩罚计划，只存 failureLesson，保留缓存（下次还能用）</li>
     * </ul>
     *
     * @param userInput    用户原话（用于查内存缓存 key）
     * @param plan         当前命中的 PlanResult
     * @param failureLesson 失败教训（AI 反思总结，可为 null）
     * @param isPlanIssue  是否计划逻辑问题（true=惩罚 / false=不惩罚）
     * @return true = 应写入新方案（计划问题且达阈值，或内存缓存已清除）；
     *         false = 保留旧缓存，本次用新方案但不持久化
     */
    public boolean onCacheHitFailure(String userInput, PlanResult plan,
                                      String failureLesson, boolean isPlanIssue) {
        String key = normalizeKey(userInput);
        CacheEntry entry = cache.get(key);

        // 异步更新 Episode（带归因：计划问题才 failureCount+1，环境问题只存 lesson）
        episodeCacheService.recordFailure(plan.episodeId(), failureLesson, isPlanIssue);

        // 环境问题：不惩罚内存缓存，计划本身没问题，保留供下次使用
        if (!isPlanIssue) {
            System.out.println("ℹ️ 环境问题导致失败，不惩罚计划，保留缓存（分段继续）");
            return false;
        }

        // 计划问题：内存失败计数+1
        if (entry == null) {
            return true;
        }
        entry.failureCount++;
        if (entry.failureCount >= FAILURE_THRESHOLD) {
            cache.remove(key);
            System.out.println("🗑️ 缓存连续失败 " + FAILURE_THRESHOLD + " 次（计划问题），已清除旧方案");
            return true;
        }
        System.out.println("⚠️ 缓存方案失败（计划问题，第 " + entry.failureCount + " 次/" + FAILURE_THRESHOLD + "），保留缓存，本次重新规划");
        return false;
    }

    /**
     * 缓存命中且成功：重置内存失败计数 + 同步更新内存 episode 统计 + 异步 Qdrant 成功计数+1。
     *
     * <p>Episode 端用 incrementSuccess（successCount+1，不重置 failureCount），
     * 让 stability 随环境变化自然衰退，避免老 episode 永不淘汰。</p>
     *
     * <p>修复问题3：同步更新内存 CacheEntry 的 episode.successCount 和 canScript，
     * 使内存命中时也能走脚本执行（否则内存 episode 的 canScript 永远是初始值 false）。</p>
     *
     * @param userInput 用户原话（用于查内存缓存 key）
     * @param plan      当前命中的 PlanResult（用 plan.episodeId() 调 Episode 成功计数）
     */
    public void onCacheHitSuccess(String userInput, PlanResult plan) {
        String key = normalizeKey(userInput);
        CacheEntry entry = cache.get(key);
        if (entry != null) {
            if (entry.failureCount > 0) entry.failureCount = 0;
            // 修复问题3：同步更新内存 episode 的 successCount + canScript
            if (entry.episode != null) {
                Episode old = entry.episode;
                int newSuccess = old.successCount() + 1;
                int failure = old.failureCount();
                double newStability = (newSuccess + failure) == 0 ? 0.0 : (double) newSuccess / (newSuccess + failure);
                boolean wasScriptable = old.canScript();
                boolean canScript = newSuccess >= scriptSuccessThreshold && newStability > 0.9;
                entry.episode = new Episode(
                        old.id(), old.userInput(), old.selectedToolNames(), old.missingDescriptions(),
                        old.toolCalls(), old.aiResponse(), old.successLesson(), old.failureLesson(),
                        old.signature(), old.unitType(), old.isGeneric(), old.parentIds(),
                        newSuccess, failure, old.archived(), old.timestamp(), newStability,
                        old.status(), canScript, old.failedStepIndex());
                if (canScript && !wasScriptable) {
                    System.out.println("🚀 内存缓存 episode 升级为可脚本化（id=" + old.id().substring(0, 8) + "...）");
                }
            }
        }
        episodeCacheService.incrementSuccess(plan.episodeId());
    }

    /** 重新规划（带失败原因，不走缓存） */
    public PlanResult replan(String userInput, ToolCallback[] allTools, String failureReason) {
        return doPlan(userInput, allTools, failureReason);
    }

    // ========== DRAFT 生命周期委托（决策4：立即创建draft，成功转active，失败也保存步骤） ==========

    /**
     * 执行前创建 DRAFT episode（委托 EpisodeCacheService.createDraft）。
     *
     * @return episodeId（执行失败时返回 null）
     */
    public String createDraftEpisode(String userInput, PlanResult plan) {
        return episodeCacheService.createDraft(userInput,
                plan.selectedToolNames(), plan.missingDescriptions());
    }

    /**
     * DRAFT → ACTIVE（执行成功时调用，委托 EpisodeCacheService.activateDraft）。
     */
    public void activateDraftEpisode(String episodeId, List<ToolCallLog> toolCallLogs,
                                      String aiResponse, String successLesson) {
        episodeCacheService.activateDraft(episodeId, toolCallLogs, aiResponse, successLesson);
    }

    /**
     * DRAFT → FAILED（执行失败时调用，决策4：失败也保存步骤）。
     */
    public void failDraftEpisode(String episodeId, List<ToolCallLog> toolCallLogs,
                                  String failureLesson, int failedStepIndex) {
        episodeCacheService.failDraft(episodeId, toolCallLogs, failureLesson, failedStepIndex);
    }

    /**
     * 写入新方案到内存缓存（首次规划成功 + draft 转 active 后调用）。
     *
     * <p>构造一个 Episode 对象存入内存，使后续内存命中时也能拿到 lesson 和参考计划。
     * Qdrant 持久化由 {@link #activateDraftEpisode} 完成，本方法只负责内存缓存。</p>
     *
     * @param userInput      用户原话
     * @param plan           首次规划成功的方案
     * @param episodeId      activateDraft 返回的 episode id
     * @param toolCallLogs   本次执行的工具调用轨迹
     * @param aiResponse     AI 最终回复
     * @param successLesson  成功经验（可为 null）
     */
    public void cacheToMemory(String userInput, PlanResult plan, String episodeId,
                               List<ToolCallLog> toolCallLogs, String aiResponse, String successLesson) {
        Episode memEpisode = new Episode(episodeId, userInput, plan.selectedToolNames(),
                plan.missingDescriptions(), toolCallLogs, truncate(aiResponse, 500),
                successLesson, null, Map.of(), Episode.UnitType.COMPOSITE, false, List.of(),
                1, 0, false, System.currentTimeMillis(), 1.0,
                Episode.EpisodeStatus.ACTIVE, false, -1);
        cache.put(normalizeKey(userInput), new CacheEntry(
                plan.selectedToolNames(), plan.missingDescriptions(), episodeId, memEpisode));
        System.out.println("💾 已写入内存缓存（" + plan.selectedToolNames().size()
                + " 个工具，episode=" + episodeId.substring(0, 8) + "..."
                + (successLesson != null ? ", 经验: " + truncate(successLesson, 40) : "") + "）");
    }

    /** 内部：调 AI 规划 */
    private PlanResult doPlan(String userInput, ToolCallback[] allTools, String failureReason) {
        StringBuilder categoryList = new StringBuilder();
        for (Category cat : CATEGORIES) {
            categoryList.append(cat.id).append(". ")
                    .append(cat.name).append(": ")
                    .append(cat.desc).append("\n");
        }

        String prompt;
        if (failureReason != null) {
            prompt = """
                    上次方案执行失败，请根据失败原因重新选择能力分类。

                    可用分类:
                    %s
                    用户请求: %s
                    失败原因: %s

                    规则:
                    - 返回需要的分类编号，逗号分隔
                    - 避开导致失败的工具，换用其他分类
                    - 如果某能力不存在，用 MISSING: 描述 说明
                    """.formatted(categoryList, userInput, failureReason);
        } else {
            prompt = """
                    根据用户请求，选择需要的能力分类。

                    可用分类:
                    %s
                    用户请求: %s

                    规则:
                    - 返回需要的分类编号，逗号分隔，不要多余内容
                    - 只选确实需要的，不要全选
                    - 如果某项能力在分类中完全不存在，用 MISSING: 描述 说明
                    - 工具可能未安装（如OCR）仍可选，执行时再处理
                    - 示例: 1,3,8
                    - 示例: 1,3,8, MISSING: 文件压缩工具
                    """.formatted(categoryList, userInput);
        }

        String planResponse;
        try {
            planResponse = chatClient.prompt().user(prompt).call().content();
        } catch (Exception e) {
            System.out.println("⚠️ 规划失败，使用兜底工具: " + e.getMessage());
            return PlanResult.ofAIPlan(new ArrayList<>(FALLBACK_TOOLS), List.of());
        }

        Set<String> neededTools = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();

        if (planResponse != null) {
            for (String part : planResponse.split("[,，\n]")) {
                String trimmed = part.trim().replaceAll("^\\d+[.\\)]\\s*", "");
                if (trimmed.toUpperCase().startsWith("MISSING:")) {
                    missing.add(trimmed.substring("MISSING:".length()).trim());
                    continue;
                }
                for (Category cat : CATEGORIES) {
                    if (cat.id.equals(trimmed)) {
                        neededTools.addAll(Arrays.asList(cat.tools));
                        break;
                    }
                }
            }
        }

        if (neededTools.isEmpty()) {
            neededTools.addAll(FALLBACK_TOOLS);
        }

        Set<String> existing = new HashSet<>();
        for (ToolCallback tc : allTools) {
            existing.add(tc.getToolDefinition().name());
        }
        neededTools.retainAll(existing);

        return PlanResult.ofAIPlan(new ArrayList<>(neededTools), missing);
    }

    private static String normalizeKey(String input) {
        if (input == null) return "";
        // \p{P} 匹配所有 Unicode 标点（含中文全角 ，。！？等）
        return input.replaceAll("[\\d\\s\\p{P}]", "").toLowerCase();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        String oneLine = s.replace("\n", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "..." : oneLine;
    }

    public void clearCache() {
        cache.clear();
        System.out.println("🗑️ 工具规划缓存已清除");
    }
}
