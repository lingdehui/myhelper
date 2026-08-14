package com.example.myhelper.service;

import com.example.myhelper.memory.vector.episode.EpisodeCacheService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 失败经验查询工具 —— 让 AI 在执行任务前主动检索历史失败模式。
 *
 * <p>AI 可以在规划阶段调用此工具，查询"类似操作是否曾经频繁失败"，
 * 从而避开已知的坑或调整策略。</p>
 */
@Component
public class FailurePatternTool {

    private final EpisodeCacheService episodeCacheService;

    public FailurePatternTool(EpisodeCacheService episodeCacheService) {
        this.episodeCacheService = episodeCacheService;
    }

    @Tool(description = """
            搜索历史失败模式/经验教训。
            在执行任务前调用此工具，检查类似操作是否曾经频繁失败，
            用于避开已知的坑、调整执行策略。
            参数 query: 要执行的任务描述（如"打开番茄网站"）""")
    public String searchFailurePatterns(
            @ToolParam(description = "要执行的任务或操作描述") String query) {

        if (query == null || query.isBlank()) {
            return "【提示】请提供要查询的任务描述。";
        }

        List<EpisodeCacheService.FailureSearchResult> results =
                episodeCacheService.searchFailurePatterns(query, 3);

        if (results.isEmpty()) {
            return "【未找到】没有与「" + query + "」相关的历史失败模式。";
        }

        return results.stream()
                .map(r -> String.format(
                        "⚠️ [相似度: %.0f%%] %s\n  描述: %s\n  建议: %s\n  失败次数: %d, 涉及工具: %s",
                        r.score() * 100,
                        r.type(),
                        r.description(),
                        r.mitigation(),
                        r.count(),
                        String.join(", ", r.toolNames())
                ))
                .collect(Collectors.joining("\n\n---\n\n",
                        "📋 发现 " + results.size() + " 条相关失败模式:\n\n", ""));
    }
}
