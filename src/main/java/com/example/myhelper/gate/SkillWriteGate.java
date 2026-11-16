package com.example.myhelper.gate;

import com.example.myhelper.skill.SkillConsolidationService.Candidate;
import com.example.myhelper.skill.SkillConsolidationService.Review;

/** 确定性的固化写入门禁。异常表示拒绝写入。 */
public final class SkillWriteGate {
    public void checkCandidate(Candidate candidate) {
        required(candidate, "candidate");
        var evidence = required(candidate.evidence(), "evidence");
        var source = required(evidence.source(), "evidence.source");
        text(source.rootRunId(), "evidence.source.rootRunId");
        text(source.sliceId(), "evidence.source.sliceId");
        if (source.sequence() < 1) {
            throw new IllegalArgumentException("evidence.source.sequence 必须为正数");
        }
        text(evidence.acceptanceRef(), "evidence.acceptanceRef");
        text(evidence.verifiedScope(), "evidence.verifiedScope");
        required(evidence.plan(), "evidence.plan");
        var description = required(candidate.description(), "description");
        text(description.name(), "description.name");
        text(description.summary(), "description.summary");
        text(description.limitations(), "description.limitations");
    }

    public void checkReview(Candidate candidate, Review review) {
        checkCandidate(candidate);
        required(review, "review");
        text(review.reviewer(), "review.reviewer");
        text(review.reason(), "review.reason");
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    private static void text(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
