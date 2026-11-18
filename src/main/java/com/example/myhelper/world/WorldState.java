package com.example.myhelper.world;

import com.example.myhelper.world.model.EntityRef;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 世界状态的业务输入与查询结果，持久化仍使用统一实体。 */
public final class WorldState {
    private WorldState() {
    }

    public record Key(
            /** 业务层分配的对象身份。 */
            String subjectId,
            /** 该对象的属性名称，不合并不同属性。 */
            String predicate) {
        public Key {
            requireText(subjectId, "subjectId");
            requireText(predicate, "predicate");
        }
    }

    public record Observation(
            /** 一次感知的稳定编号，重试沿用。 */
            String id,
            /** 感知的对象与属性。 */
            Key key,
            /** 带 JSON 类型的实际观测值。 */
            Object value,
            /** 真正测量的时刻，未知时为空。 */
            Instant observedAt,
            /** 观测有效期的上界，不含此时刻。 */
            Instant validUntil,
            /** 产生该观测的明确来源。 */
            String source,
            /** 精确适用范围。 */
            Map<String, Object> scope,
            /** 来源提供的置信度，未知时为空。 */
            Double confidence,
            /** 支持该观测的实体引用。 */
            List<EntityRef> evidence) {
        public Observation {
            requireText(id, "observation.id");
            requireText(source, "source");
            Objects.requireNonNull(key);
            value = WorldValues.freeze(value);
            scope = WorldValues.object(scope);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            checkConfidence(confidence);
            if (observedAt != null && validUntil != null && !validUntil.isAfter(observedAt)) {
                throw new IllegalArgumentException("validUntil 必须晚于 observedAt");
            }
        }
    }

    public record Policy(
            /** 本轮接受的实际来源，空集合表示允许有明确来源的记录。 */
            Set<String> trustedSources,
            /** 最低置信度，空值表示本次不以置信度筛选。 */
            Double minimumConfidence,
            /** 最长观测年龄，可与有效期共同约束。 */
            Duration maximumAge,
            /** 存储的单页条数。 */
            int pageSize,
            /** 本轮状态查询允许读取的页数。 */
            int maxPages,
            /** 业务层停止继续读取的耗时预算。 */
            Duration timeBudget) {
        public Policy {
            trustedSources = trustedSources == null ? Set.of() : Set.copyOf(trustedSources);
            checkConfidence(minimumConfidence);
            if (maximumAge != null && (maximumAge.isNegative() || maximumAge.isZero())) {
                throw new IllegalArgumentException("maximumAge 必须为正值");
            }
            if (pageSize < 1 || pageSize > 1000 || maxPages < 1) {
                throw new IllegalArgumentException("查询分页预算无效");
            }
            Objects.requireNonNull(timeBudget);
            if (timeBudget.isZero() || timeBudget.isNegative() || timeBudget.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("查询耗时预算须为正且至多五分钟");
            }
        }
    }

    public record Query(
            /** 当前查询的对象与属性。 */
            Key key,
            /** 由调用方授权的精确适用范围。 */
            Map<String, Object> scope,
            /** 取证截止时刻。 */
            Instant asOf,
            /** 本次时效、来源、置信与分页约束。 */
            Policy policy) {
        public Query {
            Objects.requireNonNull(key);
            Objects.requireNonNull(asOf);
            Objects.requireNonNull(policy);
            scope = WorldValues.object(scope);
        }
    }

    public enum Status {
        KNOWN, UNKNOWN, EXPIRED, CONFLICT, INCOMPLETE, REVISION_UNAVAILABLE
    }

    public record Evidence(
            /** 观测的精确修订。 */
            EntityRef observation,
            /** 该观测指向的标准状态修订。 */
            EntityRef state,
            /** 已恢复的规范 JSON 值。 */
            Object value,
            /** 真实观测时刻，未知时为空。 */
            Instant observedAt,
            /** 观测的明确失效时刻。 */
            Instant validUntil,
            /** 可追溯的实际来源。 */
            String source,
            /** 适用范围。 */
            Map<String, Object> scope,
            /** 感知给出的置信度，未知时为空。 */
            Double confidence) {
        public Evidence {
            value = WorldValues.freeze(value);
            scope = WorldValues.object(scope);
        }
    }

    public record Result(
            /** 当前证据裁决。 */
            Status status,
            /** 只有 KNOWN 才提供可用于条件判断的证据。 */
            Evidence current,
            /** 仅供参考的历史证据。 */
            Evidence lastKnown,
            /** 给出互斥结果的证据。 */
            List<Evidence> conflicting) {
        public Result {
            Objects.requireNonNull(status);
            conflicting = List.copyOf(conflicting);
            if ((status == Status.KNOWN) != (current != null)) {
                throw new IllegalArgumentException("只有 KNOWN 状态可以携带当前证据");
            }
        }
    }

    public record History(
            /** 预算内已读取全部候选且引用均可取得。 */
            boolean complete,
            /** 截止时点前可核验的独立感知记录。 */
            List<Evidence> observations) {
        public History {
            observations = List.copyOf(observations);
        }
    }

    static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
    }

    static void checkConfidence(Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > 1)) {
            throw new IllegalArgumentException("confidence 必须处于 0 至 1");
        }
    }
}
