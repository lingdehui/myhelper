package com.example.myhelper.common;

import java.util.*;

/**
 * AI 响应处理工具：统一项目内散落的 truncate/json清洗/关键词匹配。
 */
public final class AiResponseUtils {

    private AiResponseUtils() {}

    /** 截断字符串到 max 长度，换行符替换为空格 */
    public static String truncate(String s, int max) {
        if (s == null) return null;
        String oneLine = s.replace("\n", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "..." : oneLine;
    }

    /** 截断（null → 空字符串） */
    public static String truncateNotNull(String s, int max) {
        if (s == null) return "";
        return truncate(s, max);
    }

    /** 清理 AI 返回的 JSON 中的 markdown 代码块标记 */
    public static String stripMarkdownCodeBlock(String response) {
        if (response == null) return null;
        String json = response.trim();
        if (json.startsWith("```")) {
            json = json.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
        }
        return json;
    }

    /** 归一化：去掉数字、空格、标点，转小写（用于缓存键匹配） */
    public static String normalizeKey(String input) {
        if (input == null) return "";
        return input.replaceAll("[\\d\\s\\p{P}]", "").toLowerCase();
    }

    /**
     * 提取「学习目标」片段，剥离工具清单等噪声。
     *
     * <p>探索模式的输入是完整 prompt（学习方法 + 工具清单 + 学习目标 + 期望成果 + 成功标准），
     * 直接用整段做向量检索/匹配会被英文工具清单污染（不同学习目标共用同一批工具名，
     * 导致「移动鼠标」的 Unit 在 OCR 会话里也排第一）。这里只取「学习目标」片段；
     * 普通用户输入（无「学习目标」标记）原样返回。</p>
     */
    public static String extractLearningGoal(String input) {
        if (input == null) return null;
        int idx = input.indexOf("学习目标：");
        if (idx < 0) idx = input.indexOf("学习目标:");
        if (idx < 0) return input;

        String after = input.substring(idx).replaceFirst("^学习目标[:：]\\s*", "");
        int end = after.length();
        for (String marker : new String[]{"\n期望成果", "\n成功标准", "期望成果：", "成功标准："}) {
            int m = after.indexOf(marker);
            if (m >= 0 && m < end) end = m;
        }
        String goal = after.substring(0, end).trim();
        return goal.isEmpty() ? input : goal;
    }

    /** 元工具（搜索/列举工具），用于发现工具而非完成任务，不应沉淀为可复用步骤或提取业务变量。 */
    public static boolean isMetaTool(String toolName) {
        return "searchTool".equals(toolName)
                || "listAllTools".equals(toolName);
    }
}
