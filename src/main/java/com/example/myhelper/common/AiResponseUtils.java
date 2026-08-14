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
}
