package com.example.desktopbrain.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class InternetSearchTool {

    @Tool(description = "上网搜索工具，根据关键词搜索互联网，返回相关结果的标题和链接摘要")
    public String search(@ToolParam(description = "搜索关键词") String query) {
        if (query == null || query.isBlank()) {
            return "搜索关键词不能为空";
        }
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String searchUrl = "https://html.duckduckgo.com/html/?q=" + encodedQuery;

            URL url = new URL(searchUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                return "搜索请求失败，HTTP状态码: " + responseCode;
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder htmlBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                htmlBuilder.append(line).append("\n");
            }
            reader.close();

            String html = htmlBuilder.toString();
            List<String> results = extractResults(html);

            if (results.isEmpty()) {
                return "未找到相关搜索结果";
            }

            StringBuilder resultStr = new StringBuilder();
            for (int i = 0; i < results.size(); i++) {
                resultStr.append(i + 1).append(". ").append(results.get(i)).append("\n");
            }
            return resultStr.toString().trim();

        } catch (Exception e) {
            return "搜索过程发生错误: " + e.getMessage();
        }
    }

    private List<String> extractResults(String html) {
        List<String> results = new ArrayList<>();
        // 简化的正则提取，匹配 DuckDuckGo HTML 版本的搜索结果项
        String resultBlockRegex = "<div class=\"result results_links results_links_deep web-result \">(.*?)</div>";
        Pattern blockPattern = Pattern.compile(resultBlockRegex, Pattern.DOTALL);
        Matcher blockMatcher = blockPattern.matcher(html);

        while (blockMatcher.find() && results.size() < 10) {
            String block = blockMatcher.group(1);
            String title = extractValue(block, "result__title.*?<a.*?>(.*?)</a>");
            String link = extractValue(block, "result__url.*?href=\"(.*?)\"");
            String snippet = extractValue(block, "result__snippet.*?>(.*?)</a>");

            StringBuilder entry = new StringBuilder();
            if (title != null && !title.isBlank()) {
                entry.append(title);
            } else {
                entry.append("无标题");
            }
            if (link != null && !link.isBlank()) {
                entry.append(" (").append(link).append(")");
            }
            if (snippet != null && !snippet.isBlank()) {
                entry.append(" - ").append(snippet);
            }
            if (entry.length() > 0) {
                results.add(entry.toString());
            }
        }
        return results;
    }

    private String extractValue(String text, String regex) {
        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).replaceAll("<[^>]+>", "").trim();
        }
        return null;
    }
}