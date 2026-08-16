package com.example.myhelper.service;

import com.example.myhelper.common.AiResponseUtils;
import com.example.myhelper.registry.ToolModel;
import com.example.myhelper.registry.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

    /** 最近搜索关键词（归一化后），用于检测 AI 是否陷入反复搜索死循环。 */
    private final Deque<String> recentSearches = new ArrayDeque<>();
    private static final int MAX_RECENT_SEARCHES = 8;
    private static final int REPEAT_SEARCH_THRESHOLD = 3;

    public ToolSearchService(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /** 执行阶段前设置是否允许 listAllTools 兜底（§4.1 轮次用尽 / §6 拒绝分类时放行）。 */
    public void setListAllToolsAllowed(boolean allowed) {
        listAllToolsAllowed.set(allowed);
    }

    @Tool(description = "搜索系统中已有的工具：从统一注册中心按关键词搜索，返回完整工具信息（含参数类型、来源）。必须同时传入中文关键词和对应的英文关键词。例如 keyword=\"截图\", keywordEn=\"capture\"。返回结果包含完整的参数列表（参数名、类型、是否必填、描述），AI 应根据这些信息正确构造参数。")
    public String searchTool(
            @ToolParam(description = "中文搜索关键词，如 鼠标、截图、文件、点击") String keyword,
            @ToolParam(description = "英文搜索关键词，如 mouse、screenshot、file、click。必须传入对应英文词作为兜底") String keywordEn) {

        if (keyword == null || keyword.isBlank()) return "请提供搜索关键词";

        // 死循环检测：归一化后统计最近相似搜索次数，超阈值直接返回兜底提示
        String normalized = normalizeQuery(keyword);
        recentSearches.addLast(normalized);
        while (recentSearches.size() > MAX_RECENT_SEARCHES) recentSearches.removeFirst();
        int repeatCount = countRecentSimilar(normalized);
        if (repeatCount >= REPEAT_SEARCH_THRESHOLD) {
            log.warn("⚠️ searchTool 检测到反复搜索 '{}'（{}次），返回兜底提示", keyword, repeatCount);
            return buildLoopHint(keyword);
        }

        Set<String> results = new LinkedHashSet<>();

        // 1. 名称/描述包含匹配优先（最准，跨语言靠中文描述命中，排最前）
        nameMatchAndFormat(keyword, results);
        if (keywordEn != null && !keywordEn.isBlank()) {
            nameMatchAndFormat(keywordEn, results);
        }

        // 2. 向量语义搜索补充（limit 提高）
        searchAndFormat(keyword, results);
        if (keywordEn != null && !keywordEn.isBlank()) {
            searchAndFormat(keywordEn, results);
        }

        if (results.isEmpty()) {
            return "未找到匹配 '" + keyword + "' 的工具。\n\n💡 提示：试试其他关键词，或调用 listAllTools 查看全部工具。\n\n若是打开网页/运行命令等通用操作，可直接用系统命令：cmd /c start <url>、cmd /c <command>。";
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

    /** 向量语义搜索补充（limit 提高，名称匹配已覆盖关键词兜底）。 */
    private void searchAndFormat(String query, Set<String> results) {
        List<ToolModel> tools = toolRegistry.searchTools(query, 15, 0.3);
        for (ToolModel m : tools) {
            results.add(m.toPromptText());
        }
    }

    /** 名称/描述包含匹配：命中 toolName 或 description 的工具排最前。 */
    private void nameMatchAndFormat(String query, Set<String> results) {
        String q = query.toLowerCase();
        List<String> tokens = tokenize(query);
        for (ToolModel m : toolRegistry.findAllActive()) {
            String name = m.name().toLowerCase();
            String desc = (m.description() != null ? m.description().toLowerCase() : "");
            if (name.contains(q)) {
                results.add(m.toPromptText());
                continue;
            }
            for (String t : tokens) {
                if (name.contains(t) || desc.contains(t)) {
                    results.add(m.toPromptText());
                    break;
                }
            }
        }
    }

    /** 按空格/标点切词，过滤单字符，统一小写。 */
    private List<String> tokenize(String query) {
        List<String> tokens = new ArrayList<>();
        for (String p : query.toLowerCase().split("[\\s\\p{P}]+")) {
            if (!p.isBlank() && p.length() >= 2) tokens.add(p);
        }
        return tokens;
    }

    /** 归一化关键词：去空格标点、小写，用于相似搜索判定。 */
    private String normalizeQuery(String q) {
        return q.toLowerCase().replaceAll("[\\s\\p{P}]+", "");
    }

    /** 统计最近搜索里与 normalized 相同或互相包含的次数。 */
    private int countRecentSimilar(String normalized) {
        int count = 0;
        for (String s : recentSearches) {
            if (s.equals(normalized) || s.contains(normalized) || normalized.contains(s)) {
                count++;
            }
        }
        return count;
    }

    /** 反复搜索兜底提示，避免死循环。 */
    private String buildLoopHint(String keyword) {
        return "⚠️ 你已反复搜索相关工具（'" + keyword + "'），请停止继续换关键词重搜，改用以下方式直接操作：\n"
            + "1. 打开网页/网址：直接调用 openWebPageWithBrowser(url)；或 pressKeyCombination(WIN+R) 后 typeTextViaClipboard('cmd /c start <url>') 再回车。\n"
            + "2. 运行命令：pressKeyCombination(WIN+R) + typeTextViaClipboard('cmd /c <命令>')。\n"
            + "3. 打开文件/文件夹：'explorer <路径>'。\n"
            + "4. 需要看全量工具名时调用 listAllTools。";
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
