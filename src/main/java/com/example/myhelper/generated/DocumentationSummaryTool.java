package com.example.myhelper.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class DocumentationSummaryTool {

    @Tool(description = "从技术文档提取摘要")
    public String extractSummary(@ToolParam(description = "文档的URL或路径") String docUrlOrPath) {
        try {
            if (docUrlOrPath.startsWith("http")) {
                return openUrlAndExtract(docUrlOrPath);
            } else {
                // 假设提供一个本地文件支持的方法
                return extractSummaryFromFile(docUrlOrPath);
            }
        } catch (Exception e) {
            return "操作失败：" + e.getMessage();
        }
    }

    private String openUrlAndExtract(String url) throws Exception {
        if (!url.startsWith("http")) {
            throw new IllegalArgumentException("无效的URL格式");
        }
        
        ProcessBuilder pb = detectSystemForOpenUrl(url);
        Process process = pb.start();
        process.waitFor();

        // 模拟从网络页面提取摘要逻辑
        return "已打开并提取了来自 " + url + " 的摘要";
    }

    private ProcessBuilder detectSystemForOpenUrl(String url) {
        String osName = System.getProperty("os.name").toLowerCase();
        ProcessBuilder pb;
        if (osName.contains("win")) {
            pb = new ProcessBuilder("cmd", "/c", "start", url);
        } else if (osName.contains("mac")) {
            pb = new ProcessBuilder("/usr/bin/open", url);
        } else { // 假设是Linux
            pb = new ProcessBuilder("/usr/bin/xdg-open", url);
        }
        return pb;
    }

    private String extractSummaryFromFile(String filePath) throws Exception {
        try {
            // 模拟从本地文件提取摘要逻辑，这里只是一个简单的示例处理
            return "已从本地路径 " + filePath + " 提取了摘要";
        } catch (Exception e) {
            throw new RuntimeException("无法访问提供的文件：" + filePath, e);
        }
    }
}