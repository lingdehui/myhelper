package com.example.desktopbrain.service;

import com.example.desktopbrain.common.AiResponseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具搜索服务：让 AI 在执行过程中随时搜索系统中已有的工具。
 *
 * <p>AI 拿到执行工具集合后，如果发现缺少某个能力，可以调用
 * {@code searchTool("OCR")} 查找是否有相关工具，而不是直接说"我没有"。</p>
 */
@Service
public class ToolSearchService {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchService.class);

    private volatile ToolCallback[] allTools = new ToolCallback[0];

    /** 由 DesktopBrainApplication 在工具列表变更时调用 */
    public void updateTools(ToolCallback[] tools) {
        this.allTools = tools;
        log.info("ToolSearchService 已更新: {} 个工具", tools.length);
    }

    @Tool(description = "搜索系统中已有的工具：按关键词匹配工具名和功能描述。当你需要某个能力但手中工具不够时调用此方法查找。")
    public String searchTool(@ToolParam(description = "搜索关键词，如 OCR、文件、窗口、剪贴板") String keyword) {
        if (keyword == null || keyword.isBlank()) return "请提供搜索关键词";

        String kw = keyword.toLowerCase().trim();
        List<String> results = new ArrayList<>();

        for (ToolCallback tc : allTools) {
            String name = tc.getToolDefinition().name();
            String desc = tc.getToolDefinition().description();
            boolean match = name.toLowerCase().contains(kw);
            if (!match && desc != null) {
                match = desc.toLowerCase().contains(kw);
            }
            if (match) {
                String shortDesc = (desc != null && !desc.isBlank())
                        ? AiResponseUtils.truncateNotNull(desc, 60)
                        : "";
                results.add("- " + name + ": " + shortDesc);
            }
        }

        if (results.isEmpty()) {
            return "未找到匹配 '" + keyword + "' 的工具。可尝试其他关键词，或告知用户此能力缺失需要生成新工具。";
        }

        log.info("🔍 searchTool('{}') → {} 个匹配", keyword, results.size());
        return "找到 " + results.size() + " 个匹配 '" + keyword + "' 的工具:\n" + String.join("\n", results);
    }
}
