package com.example.myhelper.service;

import com.example.myhelper.common.AiResponseUtils;
import com.example.myhelper.registry.ToolModel;
import com.example.myhelper.registry.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具搜索服务：从统一注册中心（ToolRegistry）搜索工具。
 *
 * <p>与旧版区别：</p>
 * <ul>
 *   <li>不再依赖内存 ToolCallback[] 数组</li>
 *   <li>搜索走 Qdrant 向量检索（语义匹配，不只是关键词）</li>
 *   <li>返回完整工具信息（含参数类型、来源 Java/MCP）</li>
 *   <li>AI 拿到结果后能知道怎么调、传哪些参数</li>
 * </ul>
 */
@Service
public class ToolSearchService {

    private static final Logger log = LoggerFactory.getLogger(ToolSearchService.class);

    private final ToolRegistry toolRegistry;

    /** 本轮是否允许调用 listAllTools（由规划阶段降级信号决定，TurnProcessor 在 executeWithTools 前设置）。 */
    private final ThreadLocal<Boolean> listAllToolsAllowed = ThreadLocal.withInitial(() -> false);

    public ToolSearchService(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /** 执行阶段前设置是否允许 listAllTools 兜底（§4.1 轮次用尽 / §6 拒绝分类时放行）。 */
    public void setListAllToolsAllowed(boolean allowed) {
        listAllToolsAllowed.set(allowed);
    }

    @Tool(description = "搜索系统中已有的工具：从统一注册中心按关键词搜索，返回完整工具信息（含参数类型、来源）。必须同时传入中文关键词和对应的英文关键词，系统先搜中文、无结果再搜英文。例如 keyword=\"截图\", keywordEn=\"capture\"。返回结果包含完整的参数列表（参数名、类型、是否必填、描述），AI 应根据这些信息正确构造参数。")
    public String searchTool(
            @ToolParam(description = "中文搜索关键词，如 鼠标、截图、文件、点击") String keyword,
            @ToolParam(description = "英文搜索关键词，如 mouse、screenshot、file、click。必须传入对应英文词作为兜底") String keywordEn) {

        if (keyword == null || keyword.isBlank()) return "请提供搜索关键词";

        Set<String> results = new LinkedHashSet<>();

        // 先向量搜索中文
        searchAndFormat(keyword, results);

        // 中文无结果，用英文兜底
        if (results.isEmpty() && keywordEn != null && !keywordEn.isBlank()) {
            log.info("🔍 searchTool('{}') 无匹配，用英文 '{}' 兜底", keyword, keywordEn);
            searchAndFormat(keywordEn, results);
        }

        if (results.isEmpty()) {
            return "未找到匹配 '" + keyword + "' 的工具。\n\n💡 提示：试试其他关键词，或调用 listAllTools 查看全部工具。";
        }

        log.info("🔍 searchTool('{}') → {} 个匹配", keyword, results.size());

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(results.size()).append(" 个匹配 '").append(keyword).append("' 的工具:\n\n");
        sb.append("【重要】每个工具标注了来源类型 [JAVA]/[MCP]/[GENERATED]，调用方式不同：\n");
        sb.append("  - [JAVA] 本地工具：直接调用，参数按类型传入\n");
        sb.append("  - [MCP] 远程工具：通过 MCP 协议调用，参数须匹配 JSON Schema\n");
        sb.append("  - [GENERATED] 自生成工具：同 JAVA，但首次调用可能需编译\n\n");
        for (String r : results) {
            sb.append(r).append("\n");
        }
        return sb.toString();
    }

    private void searchAndFormat(String query, Set<String> results) {
        List<ToolModel> tools = toolRegistry.searchTools(query, 5, 0.3);

        // 向量搜索不够 → 关键词兜底
        if (tools.isEmpty()) {
            tools = toolRegistry.findAllActive().stream()
                    .filter(m -> {
                        String combined = (m.name() + " " + (m.description() != null ? m.description() : "")).toLowerCase();
                        return combined.contains(query.toLowerCase());
                    })
                    .limit(5)
                    .toList();
        }

        for (ToolModel m : tools) {
            results.add(m.toPromptText());
        }
    }

    @Tool(description = "分页列出系统中所有可用工具的名称（仅名称，不含描述与参数）。仅当分类浏览轮次用尽、或工具列表超限降级后，才能调用此方法兜底翻页查看全量。page 从 1 开始，每页最多 50 个，可通过多次调用翻页获取全量。")
    public String listAllTools(
            @ToolParam(description = "页码，从 1 开始。每页 50 个工具，可通过多次调用翻页获取全量。") Integer page) {

        // §4.1/§6：分类轮次用尽/工具超限降级后才允许 listAllTools 兜底
        if (!Boolean.TRUE.equals(listAllToolsAllowed.get())) {
            return "❌ 暂不能直接查看全部工具。请先通过分类浏览选择工具；只有分类切换次数用尽或工具列表超限降级后，才能用 listAllTools 兜底翻页查看全量。";
        }

        List<ToolModel> all = toolRegistry.findAllActive();
        if (all.isEmpty()) return "暂无可用工具（工具注册表为空，请检查系统启动日志）";

        // §4.1 max_tools_displayed = 50，分页获取全量
        int pageSize = 50;
        int total = all.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
        int p = (page == null || page < 1) ? 1 : Math.min(page, totalPages);
        int from = (p - 1) * pageSize;
        int to = Math.min(from + pageSize, total);

        StringBuilder sb = new StringBuilder();
        sb.append("全部工具共 ").append(total).append(" 个，第 ").append(p)
                .append("/").append(totalPages).append(" 页（仅名称）:\n");
        for (int i = from; i < to; i++) {
            ToolModel m = all.get(i);
            sb.append("- ").append(m.name()).append(" [").append(m.type()).append("]\n");
        }
        if (p < totalPages) {
            sb.append("...（下一页请调用 listAllTools(page=").append(p + 1).append(")）\n");
        }

        log.info("📋 listAllTools → 第 {}/{} 页（本页 {} 个）", p, totalPages, to - from);
        return sb.toString();
    }
}
