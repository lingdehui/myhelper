package com.example.myhelper.novel;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 质量门禁的结构化结果。
 *
 * <p>分数仅用于排序和阈值判断；严重设定、逻辑和剧透问题始终阻止提交，
 * 不会因为模型给出高分而被掩盖。</p>
 */
public record NovelQualityAssessment(
        int score,
        Decision decision,
        boolean reviewAvailable,
        List<Issue> issues) {

    public enum Decision { PASS, REWRITE, REVIEW_UNAVAILABLE }
    public enum Severity { CRITICAL, WARN, INFO }

    public record Issue(Severity severity, String category, String quote,
                        String detail, String suggestion) {
        public Issue {
            severity = severity == null ? Severity.INFO : severity;
            category = textOr(category, "其他");
            quote = quote == null ? "" : quote.trim();
            detail = textOr(detail, "未说明的问题");
            suggestion = suggestion == null ? "" : suggestion.trim();
        }
    }

    public NovelQualityAssessment {
        score = Math.max(0, Math.min(100, score));
        decision = decision == null ? Decision.REVIEW_UNAVAILABLE : decision;
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /**
     * 由模型审阅和本地重复检查共同生成门禁结果。
     * 模型分数只能高估时被收紧，不能覆盖已识别出的严重问题。
     */
    public static NovelQualityAssessment reviewed(Integer reportedScore, List<Issue> issues,
                                                   boolean reviewAvailable, int minScore) {
        List<Issue> safeIssues = issues == null ? List.of() : List.copyOf(issues);
        if (!reviewAvailable) {
            return new NovelQualityAssessment(0, Decision.REVIEW_UNAVAILABLE, false, safeIssues);
        }
        int severityCap = 100;
        for (Issue issue : safeIssues) {
            if (issue.severity() == Severity.CRITICAL) severityCap -= 40;
            else if (issue.severity() == Severity.WARN) severityCap -= 12;
        }
        int score = reportedScore == null ? severityCap : Math.min(reportedScore, severityCap);
        boolean hasCritical = safeIssues.stream().anyMatch(issue -> issue.severity() == Severity.CRITICAL);
        Decision decision = !hasCritical && score >= minScore ? Decision.PASS : Decision.REWRITE;
        return new NovelQualityAssessment(score, decision, true, safeIssues);
    }

    public boolean approved() {
        return decision == Decision.PASS;
    }

    /** 只保留需要作者模型执行的修改项，避免重写时被一堆 INFO 干扰。 */
    public String revisionBrief() {
        List<Issue> actionable = issues.stream()
                .filter(issue -> issue.severity() != Severity.INFO)
                .sorted(Comparator.comparing((Issue issue) -> issue.severity() == Severity.CRITICAL ? 0 : 1))
                .limit(8)
                .toList();
        if (actionable.isEmpty()) {
            return "整体质量未达到门槛。保持章节计划不变，增强具体事件、人物反应和章节推进。";
        }
        StringBuilder brief = new StringBuilder();
        for (int index = 0; index < actionable.size(); index++) {
            Issue issue = actionable.get(index);
            brief.append(index + 1).append(". [").append(issue.category()).append("] ")
                    .append(issue.detail());
            if (!issue.quote().isBlank()) brief.append(" 原文定位：\"").append(issue.quote()).append("\"");
            if (!issue.suggestion().isBlank()) brief.append(" 修改：").append(issue.suggestion());
            brief.append("\n");
        }
        return brief.toString();
    }

    public String asReport(String novelName, int chapterNumber) {
        StringBuilder report = new StringBuilder("【章节质量门禁】《")
                .append(novelName).append("》第").append(chapterNumber).append("章\n")
                .append("评分：").append(score).append("/100；结论：");
        if (decision == Decision.PASS) report.append("✅ 可提交");
        else if (decision == Decision.REWRITE) report.append("⚠️ 需定向修订");
        else report.append("❌ 审阅不可用，未自动放行");
        report.append("\n");
        if (issues.isEmpty()) return report.append("未发现需要处理的问题。\n").toString();
        for (Issue issue : issues) {
            String icon = issue.severity() == Severity.CRITICAL ? "❌" : issue.severity() == Severity.WARN ? "⚠️" : "ℹ️";
            report.append(icon).append(" [").append(issue.category()).append("|")
                    .append(issue.severity()).append("] ").append(issue.detail());
            if (!issue.quote().isBlank()) report.append(" 原文：\"").append(issue.quote()).append("\"");
            if (!issue.suggestion().isBlank()) report.append(" → ").append(issue.suggestion());
            report.append("\n");
        }
        return report.toString();
    }

    private static String textOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
