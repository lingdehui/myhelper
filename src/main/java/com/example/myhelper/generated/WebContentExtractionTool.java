package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页正文提取工具：先获取页面，再从噪声中分离可读的主体内容。
 */
@Component
@GeneratedTool
public class WebContentExtractionTool {

    private static final int MAX_EXTRACTED_CHARS = 1_500;
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern META_DESCRIPTION_PATTERN = Pattern.compile(
            "(?is)<meta[^>]+(?:name|property)\\s*=\\s*[\\\"'](?:description|og:description)[\\\"'][^>]+content\\s*=\\s*[\\\"'](.*?)[\\\"'][^>]*>");
    private static final Pattern META_DESCRIPTION_REVERSED_PATTERN = Pattern.compile(
            "(?is)<meta[^>]+content\\s*=\\s*[\\\"'](.*?)[\\\"'][^>]+(?:name|property)\\s*=\\s*[\\\"'](?:description|og:description)[\\\"'][^>]*>");
    private static final Pattern TAG_PATTERN = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern SCRIPT_STYLE_PATTERN = Pattern.compile("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Tool(description = "提取网页正文并生成基于标题、描述和正文开头的简短摘要。仅支持 http 或 https 页面。")
    public String extractAndSummarizeWebPage(@ToolParam(description = "URL of the web page") String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return "无法提取：URL 必须使用 http 或 https 协议。";
            }

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "MyHelper/1.0 WebContentExtractor")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "网页请求失败，HTTP 状态码: " + response.statusCode();
            }

            String html = response.body();
            if (html == null || html.isBlank()) return "网页没有可提取的内容。";

            String title = cleanText(firstGroup(TITLE_PATTERN, html));
            String description = cleanText(firstGroup(META_DESCRIPTION_PATTERN, html));
            if (description.isBlank()) {
                description = cleanText(firstGroup(META_DESCRIPTION_REVERSED_PATTERN, html));
            }
            String body = extractVisibleText(html);
            String summary = description.isBlank() ? summarize(body) : description;

            StringBuilder result = new StringBuilder();
            if (!title.isBlank()) result.append("标题：").append(title).append('\n');
            result.append("摘要：").append(summary).append('\n');
            result.append("正文摘录：").append(truncate(body, MAX_EXTRACTED_CHARS));
            return result.toString();
        } catch (Exception e) {
            return "提取网页失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static String extractVisibleText(String html) {
        String withoutNonContent = SCRIPT_STYLE_PATTERN.matcher(html).replaceAll(" ");
        String withBreaks = withoutNonContent.replaceAll("(?is)</(p|div|h[1-6]|li|tr|br|article|section)>", "\n");
        return cleanText(TAG_PATTERN.matcher(withBreaks).replaceAll(" "));
    }

    private static String summarize(String text) {
        if (text.isBlank()) return "未能从页面中提取到可读正文。";
        int boundary = text.length();
        for (char delimiter : new char[]{'。', '！', '？', '.', '!', '?'}) {
            int index = text.indexOf(delimiter, 120);
            if (index >= 0) boundary = Math.min(boundary, index + 1);
        }
        return truncate(text.substring(0, boundary), 360);
    }

    private static String firstGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String cleanText(String value) {
        if (value == null) return "";
        return value.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        return value.substring(0, maxChars) + "…";
    }

}
