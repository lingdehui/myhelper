package com.example.myhelper.exploration;

import java.util.List;

/**
 * 探索任务完成后的 AI 自我评估结果。
 */
public record ExplorationSelfAssessment(
        boolean goalAchieved,
        String summary,
        boolean worthStoring,
        String knowledgeSnippet,
        List<String> newCapabilities,
        String followUpSuggestion
) {}
