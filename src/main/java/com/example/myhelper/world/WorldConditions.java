package com.example.myhelper.world;

import com.example.myhelper.world.model.ContextUnitType;
import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.FieldBinding;
import com.example.myhelper.world.model.UnitType;
import com.example.myhelper.world.model.WorldEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 比较条件或预期与可信世界状态，提供证据和待感知请求。 */
public final class WorldConditions {
    /** 条件引用标准状态的位置。 */
    private static final String STATE_SLOT = "/data/state";
    /** 条件引用感知能力的位置。 */
    private static final String REFRESH_SLOT = "/data/refreshUnit";

    /** 按精确身份和修订读取条件、状态定义。 */
    private final WorldRepository repository;
    /** 用已配置的查询策略裁决当前可信证据。 */
    private final ContextService context;

    public WorldConditions(WorldRepository repository, ContextService context) {
        this.repository = Objects.requireNonNull(repository);
        this.context = Objects.requireNonNull(context);
    }

    /** 核对一次执行前条件，从 REQUIREMENT 的定义取得要查询的状态。 */
    public Decision requirement(Request request) {
        return evaluate(request, ContextUnitType.REQUIREMENT, null);
    }

    /** 只使用实际动作结束后、查询时点前的可信观测验收 EXPECTATION。 */
    public Decision verify(Request request, Instant completedAt) {
        Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isAfter(request.asOf())) {
            throw new IllegalArgumentException("验收查询时点不能早于执行结束");
        }
        return evaluate(request, ContextUnitType.EXPECTATION, completedAt);
    }

    private Decision evaluate(Request request, ContextUnitType phase, Instant completedAt) {
        Objects.requireNonNull(request);
        if (request.condition().contextUnitType() != phase) {
            return unknown(Reason.INVALID_CONDITION, null);
        }
        try {
            WorldEntity condition = repository.read(request.condition()).orElse(null);
            if (condition == null) {
                return unknown(missingReason(request.condition()), null);
            }
            if (!inScope(condition.scope(), request.scope())) {
                return unknown(Reason.OUTSIDE_SCOPE, null);
            }
            validateExactSlots(condition);

            EntityRef stateRef = expectedState(condition);
            WorldEntity state = null;
            if (stateRef != null) {
                state = repository.read(stateRef).orElse(null);
                if (state == null) {
                    return unknown(missingReason(stateRef), null);
                }
            }
            WorldState.Key key = conditionKey(condition, state);
            Operator operator = operator(condition, state != null);
            Object expected = operand(condition, state);
            validateOperand(operator, expected);
            RefreshCapability refresh = refreshCapability(condition);
            WorldState.Result result = context.current(new WorldState.Query(
                    key, request.scope(), request.asOf(), request.policy()));
            RefreshRequest pending = refreshRequest(request, condition, key, refresh);
            return compareResult(completedAt, stateRef, operator, expected, pending, result);
        } catch (IllegalArgumentException invalid) {
            return unknown(Reason.INVALID_CONDITION, null);
        } catch (RuntimeException unavailable) {
            return unknown(Reason.READ_FAILED, null);
        }
    }

    private static Decision compareResult(Instant completedAt, EntityRef state, Operator operator, Object expected,
                                          RefreshRequest pending, WorldState.Result result) {
        switch (result.status()) {
            case UNKNOWN, EXPIRED -> {
                return refreshOrUnknown(pending, Reason.STATE_NOT_CURRENT, result);
            }
            case CONFLICT -> {
                return unknown(Reason.CONFLICTING_EVIDENCE, result);
            }
            case INCOMPLETE -> {
                return unknown(Reason.INCOMPLETE_QUERY, result);
            }
            case REVISION_UNAVAILABLE -> {
                return unknown(Reason.REVISION_UNAVAILABLE, result);
            }
            case KNOWN -> {
                WorldState.Evidence evidence = result.current();
                if (completedAt != null && (evidence.observedAt() == null
                        || !evidence.observedAt().isAfter(completedAt))) {
                    return refreshOrUnknown(pending, Reason.POST_EXECUTION_EVIDENCE_MISSING, result);
                }
                Boolean matches = matches(operator, expected, state, evidence);
                if (matches == null) {
                    return unknown(Reason.INCOMPARABLE_VALUES, result);
                }
                if (matches) {
                    return new Decision(Status.PASS, Reason.MATCH, result, null);
                }
                return new Decision(Status.FAIL, Reason.MISMATCH, result, null);
            }
        }
        throw new IllegalStateException("未知的世界状态结果");
    }

    private static Boolean matches(Operator operator, Object expected, EntityRef state,
                                   WorldState.Evidence evidence) {
        if (state != null && evidence.state() == null) {
            return null;
        }
        Object actual = evidence.value();
        return switch (operator) {
            case EQ -> equalValue(expected, state, evidence);
            case NOT_EQ -> !equalValue(expected, state, evidence);
            case GT, GTE, LT, LTE -> ordered(operator, actual, expected);
            case IN, NOT_IN -> {
                List<?> values = (List<?>) expected;
                boolean included = values.contains(actual);
                yield operator == Operator.IN ? included : !included;
            }
        };
    }

    private static boolean equalValue(Object expected, EntityRef state, WorldState.Evidence evidence) {
        if (state != null) {
            return state.id().equals(evidence.state().id());
        }
        return Objects.equals(expected, evidence.value());
    }

    private static Boolean ordered(Operator operator, Object actual, Object expected) {
        if (!(actual instanceof BigDecimal actualNumber) || !(expected instanceof BigDecimal expectedNumber)) {
            return null;
        }
        int order = actualNumber.compareTo(expectedNumber);
        return switch (operator) {
            case GT -> order > 0;
            case GTE -> order >= 0;
            case LT -> order < 0;
            case LTE -> order <= 0;
            default -> throw new IllegalArgumentException("该操作符不比较数值顺序");
        };
    }

    private static EntityRef expectedState(WorldEntity condition) {
        EntityRef binding = singleBinding(condition, STATE_SLOT);
        String id = optionalText(condition.data().get("stateId"));
        if (binding == null && id != null) {
            return new EntityRef(id, null, null, ContextUnitType.STATE, null, null);
        }
        if (binding != null && (binding.contextUnitType() != ContextUnitType.STATE
                || id != null && !id.equals(binding.id()))) {
            throw new IllegalArgumentException("条件引用的状态类型或编号不一致");
        }
        return binding;
    }

    private static RefreshCapability refreshCapability(WorldEntity condition) {
        EntityRef binding = singleBinding(condition, REFRESH_SLOT);
        String id = optionalText(condition.data().get("refreshUnitId"));
        if (binding == null) {
            return id == null ? null : new RefreshCapability(id, null);
        }
        if (binding.unitType() == null || binding.unitType() == UnitType.INTENT
                || id != null && !id.equals(binding.id())) {
            throw new IllegalArgumentException("感知能力的引用类型或编号不一致");
        }
        return new RefreshCapability(binding.id(), binding);
    }

    private static void validateExactSlots(WorldEntity condition) {
        for (FieldBinding binding : condition.bindings()) {
            String slot = binding.slot();
            if (!slot.startsWith("/data/")) {
                continue;
            }
            // 强制比较的字段须有明确的类型和值，未定义的引用不能补齐执行条件。
            String field = slot.substring("/data/".length());
            int child = field.indexOf('/');
            if (child >= 0) {
                field = field.substring(0, child);
            }
            switch (field) {
                case "subjectId", "predicate", "operator", "value", "stateId", "refreshUnitId" ->
                        throw new IllegalArgumentException("强制条件的精确字段不接受字段绑定: " + slot);
                case "state", "refreshUnit" -> {
                    if (child >= 0) {
                        throw new IllegalArgumentException("强制条件必须引用完整目标: " + slot);
                    }
                }
                default -> { }
            }
        }
    }

    private static EntityRef singleBinding(WorldEntity entity, String slot) {
        EntityRef found = null;
        for (FieldBinding binding : entity.bindings()) {
            if (binding.slot().equals(slot)) {
                if (found != null) {
                    throw new IllegalArgumentException("强制条件的位置必须有唯一引用: " + slot);
                }
                found = binding.target();
            }
        }
        return found;
    }

    private static WorldState.Key conditionKey(WorldEntity condition, WorldEntity state) {
        String subject = optionalText(condition.data().get("subjectId"));
        String predicate = optionalText(condition.data().get("predicate"));
        if (state == null) {
            return new WorldState.Key(subject, predicate);
        }
        WorldState.Key key = new WorldState.Key(optionalText(state.data().get("subjectId")),
                optionalText(state.data().get("predicate")));
        if (subject != null && !subject.equals(key.subjectId())
                || predicate != null && !predicate.equals(key.predicate())) {
            throw new IllegalArgumentException("条件对象与标准状态不一致");
        }
        return key;
    }

    private static Operator operator(WorldEntity condition, boolean hasState) {
        String name = optionalText(condition.data().get("operator"));
        if (name == null && hasState) {
            return Operator.EQ;
        }
        if (name == null) {
            throw new IllegalArgumentException("比较值条件须指定 operator");
        }
        return Operator.valueOf(name);
    }

    private static Object operand(WorldEntity condition, WorldEntity state) {
        if (state == null && !condition.data().containsKey("value")) {
            throw new IllegalArgumentException("条件须指定状态引用或比较值");
        }
        Object literal = WorldValues.freeze(condition.data().get("value"));
        if (state == null) {
            return literal;
        }
        Object normalized = WorldRepository.stateValue(state);
        if (condition.data().containsKey("value") && !Objects.equals(literal, normalized)) {
            throw new IllegalArgumentException("直接值与引用的标准状态不一致");
        }
        return normalized;
    }

    private static void validateOperand(Operator operator, Object expected) {
        switch (operator) {
            case GT, GTE, LT, LTE -> {
                if (!(expected instanceof BigDecimal)) {
                    throw new IllegalArgumentException("范围比较须提供 JSON 数字");
                }
            }
            case IN, NOT_IN -> {
                if (!(expected instanceof List<?>)) {
                    throw new IllegalArgumentException("集合比较须提供 JSON 数组");
                }
            }
            case EQ, NOT_EQ -> { }
        }
    }

    private static boolean inScope(Map<String, Object> conditionScope, Map<String, Object> requestedScope) {
        Map<String, Object> condition = WorldValues.object(conditionScope);
        for (var field : condition.entrySet()) {
            if (!requestedScope.containsKey(field.getKey())
                    || !Objects.equals(requestedScope.get(field.getKey()), field.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new IllegalArgumentException("条件标识字段必须是非空字符串");
    }

    private static Reason missingReason(EntityRef reference) {
        if (reference.revision() != null) {
            return Reason.REVISION_UNAVAILABLE;
        }
        return Reason.REFERENCE_UNAVAILABLE;
    }

    private static RefreshRequest refreshRequest(Request request, WorldEntity condition, WorldState.Key key,
                                                RefreshCapability refresh) {
        if (refresh == null || request.refreshAlreadyAttempted()) {
            return null;
        }
        return new RefreshRequest(WorldRepository.reference(condition), key, request.scope(),
                refresh.id(), refresh.reference());
    }

    private static Decision refreshOrUnknown(RefreshRequest pending, Reason reason, WorldState.Result result) {
        if (pending != null) {
            return new Decision(Status.NEEDS_REFRESH, reason, result, pending);
        }
        return unknown(reason, result);
    }

    private static Decision unknown(Reason reason, WorldState.Result result) {
        return new Decision(Status.UNKNOWN, reason, result, null);
    }

    /** 标准状态使用精确身份比较，范围和集合使用规范 JSON 值。 */
    public enum Operator {
        EQ, NOT_EQ, GT, GTE, LT, LTE, IN, NOT_IN
    }

    /** 本次世界依据的结论，由调用方结合门禁和其他证据决策。 */
    public enum Status {
        PASS, FAIL, UNKNOWN, NEEDS_REFRESH
    }

    /** 可用于保留原因、调度感知或判断证据是否齐备。 */
    public enum Reason {
        MATCH, MISMATCH, INVALID_CONDITION, OUTSIDE_SCOPE, REFERENCE_UNAVAILABLE, REVISION_UNAVAILABLE,
        READ_FAILED, STATE_NOT_CURRENT, CONFLICTING_EVIDENCE, INCOMPLETE_QUERY, INCOMPARABLE_VALUES,
        POST_EXECUTION_EVIDENCE_MISSING
    }

    public record Request(
            /** 待核对的 REQUIREMENT 或 EXPECTATION，可固定实体修订。 */
            EntityRef condition,
            /** 当前请求已获准的精确适用范围。 */
            Map<String, Object> scope,
            /** 本次判断的证据截止时间。 */
            Instant asOf,
            /** 本次接受的来源、时效、置信度及查询预算。 */
            WorldState.Policy policy,
            /** 同一条件在本轮是否已执行过一次感知；由编排器在重新核对时传入。 */
            boolean refreshAlreadyAttempted) {
        public Request {
            Objects.requireNonNull(condition, "condition");
            scope = WorldValues.object(scope);
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(policy, "policy");
        }

        /** 以尚未感知的首个判断请求开始。 */
        public Request(EntityRef condition, Map<String, Object> scope, Instant asOf, WorldState.Policy policy) {
            this(condition, scope, asOf, policy, false);
        }
    }

    public record Decision(
            /** 本次条件比较的结论。 */
            Status status,
            /** 结论原因，区分不符合、未知、过期证据和查询缺失。 */
            Reason reason,
            /** 当前状态与历史依据。条件本身不可核对时为空。 */
            WorldState.Result result,
            /** 需要感知时的请求，其余结果为空。 */
            RefreshRequest refresh) {
    }

    /** 由编排器按通常工具授权及预算执行的感知请求。 */
    public record RefreshRequest(
            /** 触发此请求的原条件。 */
            EntityRef condition,
            /** 期望感知的对象和属性。 */
            WorldState.Key key,
            /** 感知和追加观测需使用的请求范围。 */
            Map<String, Object> scope,
            /** 感知能力的稳定编号。 */
            String unitId,
            /** 明确保存的感知能力引用；仅有编号时为空，由执行方精确解析。 */
            EntityRef unit) {
        public RefreshRequest {
            scope = WorldValues.object(scope);
        }
    }

    private record RefreshCapability(
            /** 实体的稳定编号。 */
            String id,
            /** 条件保存的感知能力引用。 */
            EntityRef reference) {
    }
}
