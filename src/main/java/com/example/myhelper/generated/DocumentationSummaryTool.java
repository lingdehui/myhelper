package com.example.myhelper.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文档摘要入口：读取指定文档并输出压缩后的关键内容，供后续规划或回答使用。
 */
@Component
public class DocumentationSummaryTool {

    private static final int MAX_SUMMARY_CHARS = 600;
    private static final int MAX_EXCERPT_CHARS = 1_500;

    private final WebContentExtractionTool webContentExtractionTool;

    public DocumentationSummaryTool(WebContentExtractionTool webContentExtractionTool) {
        this.webContentExtractionTool = webContentExtractionTool;
    }

    @Tool(description = "从技术文档提取摘要")
    public String extractSummary(@ToolParam(description = "文档的URL或路径") String docUrlOrPath) {
        try {
            if (docUrlOrPath == null || docUrlOrPath.isBlank()) {
                return "请提供文档 URL 或本地路径。";
            }
            if (docUrlOrPath.startsWith("http://") || docUrlOrPath.startsWith("https://")) {
                return webContentExtractionTool.extractAndSummarizeWebPage(docUrlOrPath);
            } else {
                return extractSummaryFromFile(docUrlOrPath);
            }
        } catch (Exception e) {
            return "操作失败：" + e.getMessage();
        }
    }

    private String extractSummaryFromFile(String filePath) throws Exception {
        Path path = Path.of(filePath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) return "未找到可读取的文件：" + path;
        if (Files.size(path) > 5L * 1024 * 1024) return "文档超过 5MB，暂不直接读取，请先提供较小的文本文件。";

        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx")) {
            return "该文件是二进制文档，请先用对应的文档读取工具转换为文本后再摘要：" + path;
        }

        String content = Files.readString(path, StandardCharsets.UTF_8)
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (content.isBlank()) return "文档为空或没有可提取的文本：" + path;

        return "文档：" + path.getFileName() + "\n摘要：" + truncate(content, MAX_SUMMARY_CHARS)
                + "\n正文摘录：" + truncate(content, MAX_EXCERPT_CHARS);
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }
}
