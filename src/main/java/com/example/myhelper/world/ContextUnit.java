package com.example.myhelper.world;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 关于世界对象的统一陈述。
 * objectId 与 literalValue 二选一，可表达关系，也可表达状态、人格与推断。
 */
public record ContextUnit(
        String id,
        Role role,
        String subjectId,
        String predicate,
        Operator operator,
        String objectId,
        Object literalValue,
        /** 指向标准 STATE ContextUnit；STATE 自身等于自己的 id。 */
        String stateId,
        String source,
        Origin origin,
        Instant observedAt,
        Instant validUntil,
        double confidence,
        double requiredConfidence,
        long maximumAgeMillis,
        String refreshUnitId,
        List<String> evidenceIds) {

    public enum Role { STATE, OBSERVATION, FACT, REQUIREMENT, EXPECTATION, INFERENCE, PERSONALITY }
    public enum Operator { EQ, NOT_EQ, EXISTS, IN, GREATER_THAN, LESS_THAN, SUPPORTS }
    public enum Origin { OBSERVED, INFERRED, USER_DECLARED, SYSTEM_DEFINED }

    public ContextUnit {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    public boolean isExpired(Instant now) {
        return validUntil != null && !validUntil.isAfter(now);
    }

    /** 按有效期线性衰减；永久事实不衰减。 */
    public double effectiveConfidence(Instant now) {
        if (observedAt == null || validUntil == null) return confidence;
        if (!now.isBefore(validUntil)) return 0.0;
        long lifetime = Math.max(1, Duration.between(observedAt, validUntil).toMillis());
        long elapsed = Math.max(0, Duration.between(observedAt, now).toMillis());
        return confidence * Math.max(0.0, 1.0 - (double) elapsed / lifetime);
    }

    public boolean isWorldValue() {
        return role == Role.OBSERVATION || role == Role.FACT || role == Role.INFERENCE || role == Role.PERSONALITY;
    }

    public boolean isStateDefinition() { return role == Role.STATE; }

    public boolean referencesState() { return stateId != null && !stateId.isBlank(); }
}
