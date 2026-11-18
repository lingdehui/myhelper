package com.example.myhelper.skill;

import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.world.model.ContextUnitType;
import com.example.myhelper.world.model.FailureCategory;
import com.example.myhelper.world.model.UnitType;
import com.example.myhelper.world.model.WorldEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** 锁定固化链修订的归档验收事实及批准范围。评分由事实派生。 */
final class SkillChainExecutionStore {
    /** 独立于发布定义及原子工具执行的归档用途。 */
    private static final String KIND = "skill-chain-execution";
    /** 查询批准范围内事实的单页大小。 */
    private static final int PAGE_SIZE = 200;
    /** 共享双库协调器。 */
    private final DualWriteStore storage;
    /** 当前发布链定义的权威来源。 */
    private final DualWriteStore.Database authority;
    /** 规范化完整入参与不可变事实。 */
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    SkillChainExecutionStore(DualWriteStore storage, DualWriteStore.Database authority) {
        this.storage = Objects.requireNonNull(storage);
        this.authority = Objects.requireNonNull(authority);
    }

    Optional<Range> approvedRange(ChainRef chain) {
        WorldEntity publication = storage.read(authority, chain.id()).orElse(null);
        if (publication == null || publication.revision() != chain.revision()
                || publication.unitType() != UnitType.PLAN_STEP
                || !"PUBLISHED".equals(publication.data().get("publicationStatus"))
                || publication.data().get("executionPlan") == null) {
            return Optional.empty();
        }
        Object scope = publication.scope().get("verifiedScope");
        Map<String, Object> constraints;
        if (scope instanceof String description && !description.isBlank()) {
            constraints = Map.of("description", description);
        } else if (scope instanceof Map<?, ?> structured && !structured.isEmpty()) {
            constraints = json.convertValue(structured, new TypeReference<Map<String, Object>>() { });
        } else {
            return Optional.empty();
        }
        return Optional.of(new Range(key(constraints), TaskPlan.immutableMap(constraints)));
    }

    /** 即使模型不可用，实际验收事实仍已保存。 */
    Optional<String> record(Fact fact) {
        String id = factId(fact);
        Map<String, Object> data = Map.of("kind", KIND, "skillId", fact.chain().id(), "skillRevision", fact.chain().revision(),
                "fact", factData(fact));
        WorldEntity observation = observation(id, 1, source(fact), data);
        if (storage.writeIfAbsent(DualWriteStore.Database.NEO4J, observation)) {
            return Optional.empty();
        }
        WorldEntity stored = requireFact(id, fact);
        return Optional.ofNullable((String) stored.data().get("approvedRangeId"));
    }

    /** 关联由模型确认的范围，与同一执行身份使用同一事实记录。 */
    void associate(Fact fact, Range range) {
        String id = factId(fact);
        WorldEntity old = requireFact(id, fact);
        if (old.data().get("approvedRangeId") instanceof String approved) {
            requireSameRange(range, approved);
            return;
        }
        var data = new LinkedHashMap<>(old.data());
        data.put("approvedRangeId", range.id());
        WorldEntity next = observation(id, Math.incrementExact(old.revision()), old.source(), data);
        if (!storage.compareAndSet(DualWriteStore.Database.NEO4J, old, next)) {
            WorldEntity latest = requireFact(id, fact);
            requireSameRange(range, latest.data().get("approvedRangeId"));
        }
    }

    void forEach(ChainRef chain, Range range, Consumer<Fact> consumer) {
        var filter = new StorageQuery.Filter(List.of(
                new StorageQuery.Match("/data/kind", List.of(KIND)),
                new StorageQuery.Match("/data/skillId", List.of(chain.id())),
                new StorageQuery.Match("/data/skillRevision", List.of(chain.revision())),
                new StorageQuery.Match("/data/approvedRangeId", List.of(range.id()))));
        StorageQuery.Cursor cursor = null;
        do {
            StorageQuery.Page page = storage.query(DualWriteStore.Database.NEO4J,
                    new StorageQuery.PageRequest(filter, PAGE_SIZE, cursor));
            for (WorldEntity row : page.entities()) {
                Fact fact = json.convertValue(row.data().get("fact"), Fact.class);
                if (row.contextUnitType() != ContextUnitType.OBSERVATION || !chain.equals(fact.chain())
                        || !range.id().equals(row.data().get("approvedRangeId"))) {
                    throw new IllegalStateException("固化链验收事实的修订或范围不一致");
                }
                consumer.accept(fact);
            }
            cursor = page.nextCursor();
        } while (cursor != null);
    }

    String canonical(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("无法编码固化链验收事实", failure);
        }
    }

    private WorldEntity requireFact(String id, Fact fact) {
        WorldEntity row = storage.read(DualWriteStore.Database.NEO4J, id).orElse(null);
        if (row == null) {
            throw new IllegalStateException("固化链验收事实尚未保存");
        }
        if (row.contextUnitType() != ContextUnitType.OBSERVATION
                || !canonical(row.data().get("fact")).equals(canonical(factData(fact)))) {
            throw new IllegalStateException("同一次固化链执行已有不同验收事实");
        }
        return row;
    }

    private static void requireSameRange(Range range, Object approved) {
        if (!range.id().equals(approved)) {
            throw new IllegalStateException("固化链验收事实的范围关联发生并发变化");
        }
    }

    private Map<String, Object> factData(Fact fact) {
        return json.convertValue(fact, new TypeReference<Map<String, Object>>() { });
    }

    private String factId(Fact fact) {
        return KIND + ":" + key(List.of(fact.taskId(), fact.rootRunId(), fact.nodeId()));
    }

    private String key(Object value) {
        return UUID.nameUUIDFromBytes(canonical(value).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String source(Fact fact) {
        return fact.taskId() + ":" + fact.rootRunId() + ":" + fact.evidenceId();
    }

    private static WorldEntity observation(String id, long revision, String source, Map<String, Object> data) {
        return new WorldEntity(id, null, ContextUnitType.OBSERVATION, null, null, null, null, data,
                List.of(), null, revision, source, List.of(), Map.of(), null, null);
    }

    record ChainRef(
            /** 权威发布实体 ID。 */
            String id,
            /** 确切发布修订。 */
            long revision) {
        ChainRef {
            TaskPlan.requireText(id, "skillId");
            if (revision < 1) {
                throw new IllegalArgumentException("固化链修订必须从 1 开始");
            }
        }
    }

    record Range(
            /** 由批准范围内容派生并与链修订共同定位。 */
            String id,
            /** 已审核的范围约束。 */
            Map<String, Object> constraints) {
    }

    record Fact(
            /** 本次实际使用的链及发布修订。 */
            ChainRef chain,
            /** 权威归档任务。 */
            String taskId,
            /** 权威归档根运行。 */
            String rootRunId,
            /** 该链在执行图中的具体使用位置。 */
            String nodeId,
            /** 本次实际验收的事件 ID。 */
            String evidenceId,
            /** 实际绑定给固化链的完整输入。 */
            Map<String, Object> arguments,
            /** 已确认的业务结果。 */
            boolean success,
            /** 权威验收给出的失败描述，允许为空。 */
            String error,
            /** 已确认失败的归因，成功时为空。 */
            FailureCategory failureCategory) {
        Fact {
            Objects.requireNonNull(chain);
            TaskPlan.requireText(taskId, "taskId");
            TaskPlan.requireText(rootRunId, "rootRunId");
            TaskPlan.requireText(nodeId, "nodeId");
            TaskPlan.requireText(evidenceId, "evidenceId");
            arguments = TaskPlan.immutableMap(Objects.requireNonNull(arguments));
            if (success == (failureCategory != null)) {
                throw new IllegalArgumentException("固化链成功结果不能带失败归因，失败须保留归因");
            }
        }
    }
}
