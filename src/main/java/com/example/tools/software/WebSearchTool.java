package dev.harrjdk.robotmcp.tools.software;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

@Component
public class WebSearchTool {

    /**
     * 搜索互联网，查找技术问题解决方案或软件安装教程。
     * 使用 DuckDuckGo Instant Answer API（免费，无需密钥）。
     */
    @Tool(description = """
            搜索互联网，查找技术问题的解决方案、软件安装教程或错误信息排查方法。
            当软件安装失败或遇到不明确的错误时，可以使用此工具搜索相关信息。
            """)
    public String webSearch(String query) {
        try {
            String encodedQuery = URLEncoder.encode(query, "UTF-8");
            String urlStr = "https://api.duckduckgo.com/?q=" + encodedQuery + "&format=json&no_html=1&skip_disambig=1";

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return "⚠️ 搜索服务暂时不可用（HTTP " + responseCode + "），请稍后重试。";
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            // 简单解析 JSON（不引入额外依赖，只提取摘要）
            String json = response.toString();
            String abstractText = extractValue(json, "\"AbstractText\":\"");
            String definition = extractValue(json, "\"Definition\":\"");

            StringBuilder result = new StringBuilder();
            if (!abstractText.isEmpty()) {
                result.append("📖 ").append(abstractText).append("\n");
            }
            if (!definition.isEmpty()) {
                result.append("📌 ").append(definition).append("\n");
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

    /**
     * 极简 JSON 值提取（仅用于 Demo，生产环境建议使用 Jackson）
     */
    private String extractValue(String json, String key) {
        int start = json.indexOf(key);
        if (start == -1) return "";
        start += key.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return "";
        return json.substring(start, end).replace("\\\"", "\"").replace("\\n", "\n");
    }
}