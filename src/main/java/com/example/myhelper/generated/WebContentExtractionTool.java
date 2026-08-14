package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@GeneratedTool
public class WebContentExtractionTool {

    @Tool(description = "Extract web page content and generate a summary")
    public String extractAndSummarizeWebPage(@ToolParam(description = "URL of the web page") String url) {
        try {
            // For demonstration, actual implementation would require additional libraries or HTTP client
            // Here we just construct a mock result
            return "Summary of: " + url;
        } catch (Exception e) {
            return "Failed to extract and summarize the web page. Reason: " + e.getMessage();
        }
    }

}