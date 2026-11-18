package com.example.myhelper.world;

import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.world.model.ContextUnitType;
import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.FieldBinding;
import com.example.myhelper.world.model.WorldEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** 通过显式选择的统一存储保存世界状态和独立观测。 */
public final class WorldRepository {
    /** 世界观测标记，与任务记录和技能使用记录区分。 */
    public static final String OBSERVATION_KIND = "world-state-observation";
    /** 标准世界状态标记。 */
    private static final String STATE_KIND = "world-state";
    /** 观测到标准状态的精确引用位置。 */
    private static final String STATE_SLOT = "/data/state";
    /** 统一实体存储。 */
    private final DualWriteStore store;
    /** 本仓储的明确读取来源和写入目标。 */
    private final DualWriteStore.Database database;
    /** 记录实体元数据的时间来源。 */
    private final Clock clock;

    public WorldRepository(DualWriteStore store, DualWriteStore.Database database, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 固定修订不可取得时为空，查询当前内容不会代替历史修订。 */
    public Optional<WorldEntity> read(EntityRef ref) {
        return store.read(database, ref);
    }

    /** 对相同的主体、属性和规范 JSON 值产生相同的 v1 身份。 */
    public static String stateId(WorldState.Key key, Object value) {
        Objects.requireNonNull(key);
        String tuple = WorldValues.canonical(Arrays.asList(key.subjectId(), key.predicate(), value));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(tuple.getBytes(StandardCharsets.UTF_8));
            return "state:v1:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法生成标准状态编号", error);
        }
    }

    /** 按明确提供的事件 ID 幂等追加观测，并返回实际使用的两个精确修订。 */
    public Written append(WorldState.Observation event) {
        Objects.requireNonNull(event);
        if (event.id().startsWith("state:v1:")) {
            throw new IllegalArgumentException("观测编号不能占用标准状态前缀");
        }
        Map<String, Object> stateFields = fields(STATE_KIND, event.key());
        stateFields.put("value", event.value());
        // 精确规范文本在通用 JSON 的数字解码后仍可无损读取。
        stateFields.put("valueCanonical", WorldValues.canonical(event.value()));
        String id = stateId(event.key(), event.value());
        Instant created = clock.instant();
        WorldEntity definition = entity(id, ContextUnitType.STATE, stateFields, List.of(),
                null, List.of(), Map.of(), created);
        Optional<WorldEntity> previous = store.read(database, event.id());
        if (previous.isPresent()) {
            EntityRef target = stateReference(previous.get(), id);
            if (target == null) {
                throw new IllegalStateException("观测编号已存在，原状态修订不可核对");
            }
            Optional<WorldEntity> originalState = read(target);
            if (originalState.isEmpty()) {
                throw new IllegalStateException("观测编号已存在，原状态修订不可核对");
            }
            WorldEntity stored = originalState.get();
            verifyDefinition(stored, event.key(), id);
            sameObservation(previous.get(), observation(event, target, created));
            return new Written(reference(previous.get()), target);
        }
        if (!store.writeIfAbsent(database, definition)) {
            definition = store.read(database, id).orElseThrow();
        }
        verifyDefinition(definition, event.key(), id);
        EntityRef target = reference(definition);
        WorldEntity observation = observation(event, target, created);
        if (!store.writeIfAbsent(database, observation)) {
            WorldEntity stored = store.read(database, event.id()).orElseThrow();
            sameObservation(stored, observation);
            observation = stored;
        }
        return new Written(reference(observation), target);
    }

    /** 读取固定 STATE 的精确 JSON 值，并核对内容与身份一致。 */
    public static Object stateValue(WorldEntity entity) {
        if (entity.contextUnitType() != ContextUnitType.STATE || !STATE_KIND.equals(entity.data().get("kind"))
                || !(entity.data().get("valueCanonical") instanceof String canonical)) {
            throw new IllegalArgumentException("引用目标缺少标准世界状态合同");
        }
        WorldState.Key key = new WorldState.Key(string(entity.data().get("subjectId")), string(entity.data().get("predicate")));
        Object value = WorldValues.decode(canonical);
        if (!entity.data().containsKey("value") || !stateId(key, value).equals(entity.id())) {
            throw new IllegalArgumentException("标准世界状态的值与身份不一致");
        }
        return value;
    }

    /** 在共享预算内读取一个精确范围的独立观测，按完整性向裁决器报告。 */
    Scan scan(WorldState.Query request, Budget budget) {
        String scope = WorldValues.canonical(request.scope());
        var filter = new StorageQuery.Filter(List.of(
                match("/contextUnitType", ContextUnitType.OBSERVATION.name()),
                match("/data/kind", OBSERVATION_KIND),
                match("/data/subjectId", request.key().subjectId()),
                match("/data/predicate", request.key().predicate()),
                match("/data/scopeKey", scope)));
        List<WorldEntity> result = new ArrayList<>();
        Set<StorageQuery.Cursor> seen = new HashSet<>();
        StorageQuery.Cursor cursor = null;
        for (int page = 0; page < request.policy().maxPages() && !budget.exhausted(); page++) {
            var loaded = store.query(database, new StorageQuery.PageRequest(filter, request.policy().pageSize(), cursor));
            for (WorldEntity row : loaded.entities()) {
                if (isObservation(row, request.key(), scope)) {
                    result.add(row);
                }
            }
            cursor = loaded.nextCursor();
            if (cursor == null) {
                return new Scan(!budget.exhausted(), result);
            }
            if (!seen.add(cursor)) {
                break;
            }
        }
        return new Scan(false, result);
    }

    Optional<WorldState.Evidence> resolve(WorldEntity row, WorldState.Query request) {
        EntityRef ref = stateReference(row, string(row.data().get("stateId")));
        if (ref == null) {
            return Optional.empty();
        }
        var state = read(ref);
        if (state.isEmpty() || !sameKey(state.get(), request.key())) {
            return Optional.empty();
        }
        try {
            return Optional.of(new WorldState.Evidence(reference(row), ref, stateValue(state.get()),
                    timestamp(row.data().get("observedAt")), timestamp(row.data().get("validUntil")),
                    row.source(), request.scope(), confidence(row)));
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private static WorldEntity observation(WorldState.Observation event, EntityRef target, Instant created) {
        Map<String, Object> data = fields(OBSERVATION_KIND, event.key());
        data.put("stateId", target.id());
        data.put("state", null);
        data.put("scopeKey", WorldValues.canonical(event.scope()));
        data.put("observedAt", iso(event.observedAt()));
        data.put("validUntil", iso(event.validUntil()));
        data.put("confidence", event.confidence());
        return entity(event.id(), ContextUnitType.OBSERVATION, data,
                List.of(new FieldBinding(STATE_SLOT, target)), event.source(), event.evidence(), event.scope(), created);
    }

    private static void sameObservation(WorldEntity stored, WorldEntity expected) {
        if (stored.contextUnitType() != ContextUnitType.OBSERVATION || stored.revision() != 1
                || !WorldValues.canonical(stored.data()).equals(WorldValues.canonical(expected.data()))
                || !Objects.equals(stored.source(), expected.source()) || !stored.bindings().equals(expected.bindings())
                || !stored.evidence().equals(expected.evidence())) {
            throw new IllegalStateException("观测编号已被不同事件使用");
        }
    }

    private static void verifyDefinition(WorldEntity entity, WorldState.Key key, String id) {
        if (!sameKey(entity, key) || !entity.id().equals(id)) {
            throw new IllegalStateException("标准状态编号已被不同内容使用");
        }
        stateValue(entity);
    }

    private static EntityRef stateReference(WorldEntity entity, String id) {
        EntityRef found = null;
        for (FieldBinding binding : entity.bindings()) {
            if (STATE_SLOT.equals(binding.slot())) {
                EntityRef ref = binding.target();
                if (found != null || ref.contextUnitType() != ContextUnitType.STATE
                        || ref.revision() == null || !ref.id().equals(id)) {
                    return null;
                }
                found = ref;
            }
        }
        return found;
    }

    private static boolean isObservation(WorldEntity row, WorldState.Key key, String scope) {
        return row.contextUnitType() == ContextUnitType.OBSERVATION
                && OBSERVATION_KIND.equals(row.data().get("kind"))
                && sameKey(row, key) && scope.equals(row.data().get("scopeKey"));
    }

    private static boolean sameKey(WorldEntity row, WorldState.Key key) {
        return key.subjectId().equals(row.data().get("subjectId")) && key.predicate().equals(row.data().get("predicate"));
    }

    private static Map<String, Object> fields(String kind, WorldState.Key key) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", kind);
        fields.put("subjectId", key.subjectId());
        fields.put("predicate", key.predicate());
        return fields;
    }

    private static WorldEntity entity(String id, ContextUnitType type, Map<String, Object> fields,
                                      List<FieldBinding> bindings, String source, List<EntityRef> evidence,
                                      Map<String, Object> scope, Instant created) {
        return new WorldEntity(id, null, type, null, null, null, null, fields,
                bindings, null, 1, source, evidence, scope, created, created);
    }

    private static StorageQuery.Match match(String pointer, String value) {
        return new StorageQuery.Match(pointer, List.of(value));
    }

    static EntityRef reference(WorldEntity entity) {
        return new EntityRef(entity.id(), entity.revision(), entity.worldObjectType(), entity.contextUnitType(),
                entity.unitType(), entity.semanticType());
    }

    static Double confidence(WorldEntity row) {
        if (row.data().get("confidence") instanceof Number number) {
            Double confidence = number.doubleValue();
            WorldState.checkConfidence(confidence);
            return confidence;
        }
        return null;
    }

    static Instant timestamp(Object value) {
        if (value instanceof String text) {
            try {
                return Instant.parse(text);
            } catch (java.time.format.DateTimeParseException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String iso(Instant value) {
        return value == null ? null : value.toString();
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    public record Written(
            /** 写入或已经存在的观测修订。 */
            EntityRef observation,
            /** 该观测实际引用的标准状态修订。 */
            EntityRef state) {
    }

    record Scan(
            /** 是否读完全部候选页。 */
            boolean complete,
            /** 通过精确键和范围过滤的实体。 */
            List<WorldEntity> rows) {
        Scan {
            rows = List.copyOf(rows);
        }
    }

    static final class Budget {
        /** 单调时钟的本次查询起点。 */
        private final long start = System.nanoTime();
        /** 允许读取的最长耗时。 */
        private final long nanos;

        Budget(Duration time) {
            nanos = time.toNanos();
        }

        boolean exhausted() {
            return Thread.currentThread().isInterrupted() || System.nanoTime() - start >= nanos;
        }
    }
}
