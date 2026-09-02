package com.example.myhelper.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从非结构化文本中抽取关键信息的工具；输出应保留来源语义，不应凭空补全事实。
 */
@Component
@GeneratedTool
public class KeyInfoExtractor {

    private static final int MAX_INPUT_CHARS = 12_000;
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？!?])|\\R+");
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{2,}");
    private static final Pattern CHINESE_PHRASE = Pattern.compile("[\\u4e00-\\u9fff]{2,4}");

    @Tool(description = "从输入文本中提取简短摘要和高频关键词")
    public String extractSummary(@ToolParam(description = "要处理的原始文本") String rawText) {
        if (rawText == null || rawText.isBlank()) return "没有可提取的文本。";
        String text = rawText.replaceAll("\\s+", " ").trim();
        if (text.length() > MAX_INPUT_CHARS) text = text.substring(0, MAX_INPUT_CHARS) + "…";

        StringBuilder summary = new StringBuilder();
        int selected = 0;
        for (String sentence : SENTENCE_SPLIT.split(text)) {
            String candidate = sentence.trim();
            if (candidate.length() < 8) continue;
            if (summary.length() > 0) summary.append(' ');
            summary.append(candidate);
            if (++selected == 3 || summary.length() >= 420) break;
        }
        if (summary.isEmpty()) summary.append(text, 0, Math.min(text.length(), 420));

        Map<String, Integer> frequencies = new LinkedHashMap<>();
        collectKeywords(LATIN_WORD.matcher(text), frequencies, true);
        collectKeywords(CHINESE_PHRASE.matcher(text), frequencies, false);
        String keywords = frequencies.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(8)
                .map(Map.Entry::getKey)
                .reduce((left, right) -> left + "、" + right)
                .orElse("未识别到稳定关键词");
        return "摘要：" + summary + "\n关键词：" + keywords;
    }

    private void collectKeywords(Matcher matcher, Map<String, Integer> frequencies, boolean lowerCase) {
        while (matcher.find()) {
            String keyword = lowerCase ? matcher.group().toLowerCase(Locale.ROOT) : matcher.group();
            frequencies.merge(keyword, 1, Integer::sum);
        }
    }

    @Tool(description = "在 Windows 系统浏览器中打开一个 http 或 https 网页")
    public String openBrowser(@ToolParam(description = "要访问的网页地址") String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) return "只支持打开 http 或 https 网页地址。";
            if (!System.getProperty("os.name").startsWith("Windows")) return "本功能当前仅支持 Windows。";
            new ProcessBuilder("cmd", "/c", "start", "", uri.toString()).start();
            return "已请求系统浏览器打开：" + uri;
        } catch (Exception e) {
            return "打开浏览器失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
