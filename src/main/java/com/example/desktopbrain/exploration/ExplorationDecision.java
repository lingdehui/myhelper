package com.example.desktopbrain.exploration;

import java.util.List;

/**
 * AI 自主探索决策结果。
 */
public record ExplorationDecision(
        String decision,          // LEARN | SKIP
        String reason,
        String learningGoal,
        String learningMethod,    // internal_tool_probing | web_research | download_and_learn | other
        List<String> toolCategories, // AI 从工具分类列表中选取的分类名
        List<String> steps,
        String expectedOutcome,
        String successCriteria,
        String priority           // HIGH | MEDIUM | LOW
) {}
