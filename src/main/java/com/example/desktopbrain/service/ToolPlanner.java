package com.example.desktopbrain.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 两阶段工具规划器（方案C：规划+记忆）
 *
 * 缓存策略：
 * - 命中缓存 + 成功 → 重置失败计数，不改缓存
 * - 命中缓存 + 失败 → 失败计数+1，重新规划本次执行，但不清除缓存
 * - 连续失败达到阈值 → 清除旧缓存，重新规划，成功则写入新方案
 * - 缓存未命中 → 规划 → 成功才缓存
 */
@Component
public class ToolPlanner {

    private final ChatClient chatClient;
    private static final int FAILURE_THRESHOLD = 3;

    /** 缓存条目：方案 + 连续失败次数 */
    private static class CacheEntry {
        final PlanResult plan;
        int failureCount;
        CacheEntry(PlanResult plan) { this.plan = plan; this.failureCount = 0; }
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

    public ToolPlanner(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem("你是工具规划器，根据用户请求选择所需的能力分类，只返回编号。")
                .build();
    }

    public record PlanResult(
            List<String> selectedToolNames,
            List<String> missingDescriptions,
            boolean fromCache
    ) {}

    private record Category(String id, String name, String desc, String[] tools) {}

    /** 正常规划：先查缓存，未命中才调 AI */
    public PlanResult plan(String userInput, ToolCallback[] allTools) {
        String key = normalizeKey(userInput);
        CacheEntry entry = cache.get(key);
        if (entry != null) {
            System.out.println("💾 命中缓存（失败计数: " + entry.failureCount + "/" + FAILURE_THRESHOLD + "）");
            // 包装为 fromCache=true，让调用方能区分缓存命中 vs 首次规划
            PlanResult p = entry.plan;
            return new PlanResult(p.selectedToolNames(), p.missingDescriptions(), true);
        }
        return doPlan(userInput, allTools, null);
    }

    /**
     * 缓存命中但失败时调用。
     * @return true = 连续失败达阈值，已清除旧缓存，应写入新方案；
     *         false = 未达阈值，保留旧缓存，本次用新方案但不持久化
     */
    public boolean onCacheHitFailure(String userInput) {
        CacheEntry entry = cache.get(normalizeKey(userInput));
        if (entry == null) return true;  // 已被清除
        entry.failureCount++;
        if (entry.failureCount >= FAILURE_THRESHOLD) {
            cache.remove(normalizeKey(userInput));
            System.out.println("🗑️ 缓存连续失败 " + FAILURE_THRESHOLD + " 次，已清除旧方案");
            return true;
        }
        System.out.println("⚠️ 缓存方案失败（第 " + entry.failureCount + " 次/" + FAILURE_THRESHOLD + "），保留缓存，本次重新规划");
        return false;
    }

    /** 缓存命中且成功：重置失败计数 */
    public void onCacheHitSuccess(String userInput) {
        CacheEntry entry = cache.get(normalizeKey(userInput));
        if (entry != null && entry.failureCount > 0) {
            entry.failureCount = 0;
        }
    }

    /** 重新规划（带失败原因，不走缓存） */
    public PlanResult replan(String userInput, ToolCallback[] allTools, String failureReason) {
        return doPlan(userInput, allTools, failureReason);
    }

    /** 写入新方案到缓存 */
    public void cachePlan(String userInput, PlanResult plan) {
        cache.put(normalizeKey(userInput), new CacheEntry(plan));
        System.out.println("💾 已缓存成功方案（" + plan.selectedToolNames().size() + " 个工具）");
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
            return new PlanResult(new ArrayList<>(FALLBACK_TOOLS), List.of(), false);
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

        return new PlanResult(new ArrayList<>(neededTools), missing, false);
    }

    private static String normalizeKey(String input) {
        if (input == null) return "";
        // \p{P} 匹配所有 Unicode 标点（含中文全角 ，。！？等）
        return input.replaceAll("[\\d\\s\\p{P}]", "").toLowerCase();
    }

    public void clearCache() {
        cache.clear();
        System.out.println("🗑️ 工具规划缓存已清除");
    }
}
