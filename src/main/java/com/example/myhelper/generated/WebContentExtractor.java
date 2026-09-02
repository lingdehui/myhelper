package com.example.myhelper.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/** 使用统一的 HTTP 提取器，避免另一套只返回 HTML 行预览的伪实现。 */
@Component
@GeneratedTool
public class WebContentExtractor {

    private final WebContentExtractionTool webContentExtractionTool;

    public WebContentExtractor(WebContentExtractionTool webContentExtractionTool) {
        this.webContentExtractionTool = webContentExtractionTool;
    }

    @Tool(description = "提取指定 URL 的可读正文摘录和摘要。")
    public String extractMainContent(@ToolParam(description = "Web page URL") String url) {
        return webContentExtractionTool.extractAndSummarizeWebPage(url);
    }
}
