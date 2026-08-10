package com.example.desktopbrain.service;

import com.example.desktopbrain.common.AiResponseUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    @Tool(description = "搜索系统中已有的工具：按关键词匹配工具名和功能描述。必须同时传入中文关键词和对应的英文关键词，系统会先搜中文、搜不到再搜英文。例如 keyword=\"鼠标\", keywordEn=\"mouse\"。")
    public String searchTool(
            @ToolParam(description = "中文搜索关键词，如 鼠标、截图、文件、点击") String keyword,
            @ToolParam(description = "英文搜索关键词，如 mouse、screenshot、file、click。必须传入对应英文词作为兜底") String keywordEn) {

        if (keyword == null || keyword.isBlank()) return "请提供搜索关键词";

        Set<String> results = new LinkedHashSet<>();

        // 先搜索中文关键词
        matchTools(keyword.toLowerCase().trim(), results);

        // 中文搜不到，用英文关键词兜底
        if (results.isEmpty() && keywordEn != null && !keywordEn.isBlank()) {
            log.info("🔍 searchTool('{}') 无中文匹配，用英文 '{}' 兜底", keyword, keywordEn);
            matchTools(keywordEn.toLowerCase().trim(), results);
        }

        if (results.isEmpty()) {
            return "未找到匹配 '" + keyword + "' 的工具。\n\n💡 提示：试试其他中英文关键词，或调用 listAllTools 查看全部工具。";
        }

        log.info("🔍 searchTool('{}') → {} 个匹配", keyword, results.size());
        return "找到 " + results.size() + " 个匹配 '" + keyword + "' 的工具:\n" + String.join("\n", results);
    }

    private void matchTools(String term, Set<String> results) {
        for (ToolCallback tc : allTools) {
            String name = tc.getToolDefinition().name();
            String desc = tc.getToolDefinition().description();
            String combined = (name + " " + (desc != null ? desc : "")).toLowerCase();
            if (combined.contains(term)) {
                String shortDesc = (desc != null && !desc.isBlank())
                        ? AiResponseUtils.truncateNotNull(desc, 60)
                        : "";
                results.add("- " + name + ": " + shortDesc);
            }
        }
    }

    @Tool(description = "一次性列出系统中所有可用工具的名称和简要描述。当需要了解整体能力、做环境盘点、或搜索工具时优先调用此方法，避免多次 searchTool 查询。")
    public String listAllTools() {
        if (allTools.length == 0) return "暂无可用工具";

        List<String> result = new ArrayList<>();
        for (ToolCallback tc : allTools) {
            String name = tc.getToolDefinition().name();
            String desc = tc.getToolDefinition().description();
            String shortDesc = (desc != null && !desc.isBlank())
                    ? " — " + AiResponseUtils.truncateNotNull(desc, 50)
                    : "";
            result.add(name + shortDesc);
        }

        log.info("📋 listAllTools → {} 个工具", allTools.length);
        return "共 " + allTools.length + " 个工具:\n" + String.join("\n", result);
    }
}
