package com.example.myhelper.novel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 质量门禁的纯逻辑测试，不需要模型、Neo4j 或 Qdrant。 */
class NovelQualityAssessmentTest {

    @Test
    void criticalIssueBlocksCommitEvenWhenModelReportsHighScore() {
        NovelQualityAssessment assessment = NovelQualityAssessment.reviewed(98, List.of(
                new NovelQualityAssessment.Issue(NovelQualityAssessment.Severity.CRITICAL,
                        "反泄露", "他的真实身份已经公开", "提前揭露了禁止信息", "删除泄露内容并改为保留悬念。")
        ), true, 82);

        assertFalse(assessment.approved());
        assertEquals(NovelQualityAssessment.Decision.REWRITE, assessment.decision());
        assertTrue(assessment.score() <= 60);
    }

    @Test
    void warningsReduceScoreButDoNotNecessarilyRejectAnOtherwiseGoodDraft() {
        NovelQualityAssessment assessment = NovelQualityAssessment.reviewed(96, List.of(
                new NovelQualityAssessment.Issue(NovelQualityAssessment.Severity.WARN,
                        "重复表达", "", "同一短语重复", "换一种说法。")
        ), true, 82);

        assertTrue(assessment.approved());
        assertEquals(88, assessment.score());
    }

    @Test
    void unavailableReviewNeverPassesAutomatically() {
        NovelQualityAssessment assessment = NovelQualityAssessment.reviewed(null, List.of(), false, 82);

        assertFalse(assessment.approved());
        assertEquals(NovelQualityAssessment.Decision.REVIEW_UNAVAILABLE, assessment.decision());
    }
}
