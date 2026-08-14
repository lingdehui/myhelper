package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@GeneratedTool
public class WebContentExtractor {

    @Tool(description = "Extracts the main content from a specified URL.")
    public String extractMainContent(
            @ToolParam(description="Web page URL") String url) {
        try {
            // 模拟网页内容提取，使用标准库进行网络请求和读取
            java.net.URL websiteUrl = new java.net.URL(url);
            try (java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(websiteUrl.openStream()))) {

                String inputLine;
                StringBuilder contentBuilder = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    contentBuilder.append(inputLine).append("\n");
                }

                // 假设我们只返回前10行作为模拟提取的主内容
                return getContentPreview(contentBuilder.toString(), 10);
            }
        } catch (Exception e) {
            return "Failed to extract content from the URL: " + url;
        }
    }

    private String getContentPreview(String fullContent, int lineLimit) {
        java.util.StringTokenizer lines = new java.util.StringTokenizer(fullContent, "\n");
        StringBuilder previewBuilder = new StringBuilder();
        int count = 0;
        while (lines.hasMoreTokens() && count < lineLimit) {
            previewBuilder.append(lines.nextToken()).append("\n");
            count++;
        }
        return previewBuilder.toString();
    }

}