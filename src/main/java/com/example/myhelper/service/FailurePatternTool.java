package com.example.myhelper.service;

import com.example.myhelper.memory.unit.FailureCause;
import com.example.myhelper.memory.unit.UnitFailureService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 失败原因查询工具 —— 让 AI 在执行任务前主动检索历史失败原因（指向 FailureCause 图）。
 *
 * <p>数据源是 Neo4j 的 FailureCause 节点（文档 15 v1.7 §5），不再是旧的独立 FailurePattern 文本库。</p>
 */
@Component
public class FailurePatternTool {

    private final UnitFailureService unitFailureService;

    public FailurePatternTool(UnitFailureService unitFailureService) {
        this.unitFailureService = unitFailureService;
    }

    @Tool(description = """
            搜索历史失败原因/经验教训。
            在执行任务前调用此工具，检查类似操作是否曾经失败，
            用于避开已知的坑、调整执行策略。
            参数 query: 要执行的任务描述（如"打开番茄网站"）""")
    public String searchFailurePatterns(
            @ToolParam(description = "要执行的任务或操作描述") String query) {

        if (query == null || query.isBlank()) {
            return "【提示】请提供要查询的任务描述。";
        }

        List<FailureCause> results = unitFailureService.searchFailureCauses(query, 3);

        if (results.isEmpty()) {
            return "【未找到】没有与「" + query + "」相关的历史失败原因。";
        }

        return results.stream()
                .map(r -> String.format(
                        "⚠️ [%s] %s\n  建议: %s",
                        r.category(),
                        r.reason(),
                        r.analysis() != null && !r.analysis().isBlank() ? r.analysis() : "无"))
                .collect(Collectors.joining("\n\n---\n\n",
                        "📋 发现 " + results.size() + " 条相关失败原因:\n\n", ""));
    }
}
