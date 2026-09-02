package dev.harrjdk.robotmcp.tools.software;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 面向软件问题的轻量网页检索工具。
 *
 * <p>使用 DuckDuckGo Instant Answer API 获取公开摘要和相关主题；它适合问题初筛，
 * 不应被当作完整网页浏览或权威结论来源。</p>
 */
@Component
public class WebSearchTool {

    /** 避免单次工具调用返回过多噪声结果，保留最相关的少量主题。 */
    private static final int MAX_RELATED_RESULTS = 5;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 搜索互联网，查找技术问题解决方案或软件安装教程。
     * 使用 DuckDuckGo Instant Answer API（免费，无需密钥）。
     */
    @Tool(description = """
            搜索互联网，查找技术问题的解决方案、软件安装教程或错误信息排查方法。
            当软件安装失败或遇到不明确的错误时，可以使用此工具搜索相关信息。
            """)
    public String webSearch(@ToolParam(description = "要搜索的关键词或技术问题") String query) {
        try {
            if (query == null || query.isBlank()) return "请输入要搜索的关键词。";
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String urlStr = "https://api.duckduckgo.com/?q=" + encodedQuery + "&format=json&no_html=1&skip_disambig=1";

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "MyHelper/1.0 WebSearchTool");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return "⚠️ 搜索服务暂时不可用（HTTP " + responseCode + "），请稍后重试。";
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            conn.disconnect();

            JsonNode root = objectMapper.readTree(response.toString());
            String abstractText = root.path("AbstractText").asText("");
            String definition = root.path("Definition").asText("");
            List<String> relatedResults = new ArrayList<>();
            collectRelatedTopics(root.path("RelatedTopics"), relatedResults);

            StringBuilder result = new StringBuilder();
            if (!abstractText.isEmpty()) {
                result.append("📖 ").append(abstractText).append("\n");
            }
            if (!definition.isEmpty()) {
                result.append("📌 ").append(definition).append("\n");
            }
            if (!relatedResults.isEmpty()) {
                result.append("相关结果：\n");
                for (String related : relatedResults) {
                    result.append("- ").append(related).append("\n");
                }
            }
            if (result.isEmpty()) {
                return "🔍 未找到与 '" + query + "' 相关的直接结果，建议更换关键词重试。";
            }

            result.append("\n💡 如需更详细结果，请访问：https://duckduckgo.com/?q=")
                    .append(encodedQuery);

            return result.toString();

        } catch (Exception e) {
            return "❌ 搜索失败：" + e.getMessage();
        }
    }

    /** DuckDuckGo 的 RelatedTopics 既可能是结果数组，也可能是嵌套 Topic 分组。 */
    private void collectRelatedTopics(JsonNode topics, List<String> results) {
        if (topics == null || results.size() >= MAX_RELATED_RESULTS) return;
        if (topics.isArray()) {
            for (JsonNode topic : topics) {
                if (results.size() >= MAX_RELATED_RESULTS) return;
                String text = topic.path("Text").asText("");
                String url = topic.path("FirstURL").asText("");
                if (!text.isBlank()) {
                    results.add(url.isBlank() ? text : text + " — " + url);
                } else {
                    collectRelatedTopics(topic.path("Topics"), results);
                }
            }
        }
    }
}
