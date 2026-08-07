package com.example.desktopbrain.exploration;

import java.util.List;

/**
 * AI 自主探索决策结果。
 */
public record ExplorationDecision(
        String decision,          // LEARN | SKIP
        String reason,
        String learningGoal,
        String method,            // internal_tool_probing | web_research | download_and_learn | other
        List<String> steps,
        String expectedOutcome,
        String successCriteria,
        String priority           // HIGH | MEDIUM | LOW
) {}
