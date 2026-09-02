package com.example.myhelper.world;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 当前环境状态的统一入口：内存保存实时真值，Neo4j 保存重要对象与事实。 */
@Service
public class EnvironmentStateService {
    private static final Logger log = LoggerFactory.getLogger(EnvironmentStateService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    private final WorldObjectRepository objectRepository;
    private final ContextUnitRepository contextRepository;
    private final ObjectMapper mapper;
    private final Map<String, WorldObject> objectCache = new ConcurrentHashMap<>();
    private final Map<String, ContextUnit> contextCache = new ConcurrentHashMap<>();

    public EnvironmentStateService(WorldObjectRepository objectRepository,
                                   ContextUnitRepository contextRepository,
                                   ObjectMapper mapper) {
        this.objectRepository = objectRepository;
        this.contextRepository = contextRepository;
        this.mapper = mapper;
    }

    public WorldObject upsertObject(WorldObject object, boolean persist) {
        WorldModelPolicy.requireValidObject(object);
        objectCache.put(object.id(), object);
        if (persist) safely(() -> objectRepository.save(toNode(object)), "保存世界对象 " + object.id());
        return object;
    }

    public ContextUnit upsertContext(ContextUnit unit, boolean persist) {
        WorldModelPolicy.requireValidContext(unit);
        contextCache.compute(unit.id(), (id, current) -> newerOf(current, unit));
        ContextUnit accepted = contextCache.get(unit.id());
        if (accepted != unit) return accepted;
        if (persist) safely(() -> contextRepository.save(toNode(unit)), "保存上下文单元 " + unit.id());
        return unit;
    }

    /** 获取或创建一个可复用的标准状态；相同 subject/predicate/operator/value 永远得到同一 ID。 */
    public ContextUnit ensureState(String subjectId, String predicate, ContextUnit.Operator operator,
                                   String objectId, Object literalValue, boolean persist) {
        String id = stateId(subjectId, predicate, operator, objectId, literalValue);
        ContextUnit.Operator stateOperator = objectId == null && literalValue == null
                ? ContextUnit.Operator.EXISTS : ContextUnit.Operator.EQ;
        return getContext(id).orElseGet(() -> upsertContext(new ContextUnit(id, ContextUnit.Role.STATE,
                subjectId, predicate, stateOperator, objectId, literalValue, id,
                "state-catalog", ContextUnit.Origin.SYSTEM_DEFINED, null, null,
                1.0, 0.0, 0, null, List.of()), persist));
    }

    /** 创建不可变历史快照，并让它指向合并后的标准状态。 */
    public ContextUnit observe(String subjectId, String predicate, ContextUnit.Operator operator,
                               String objectId, Object literalValue, String source,
                               ContextUnit.Origin origin, Instant observedAt, Instant validUntil,
                               double confidence, String refreshUnitId, boolean persist) {
        ContextUnit state = ensureState(subjectId, predicate, operator, objectId, literalValue, persist);
        ContextUnit snapshot = new ContextUnit("observation:" + UUID.randomUUID(), ContextUnit.Role.OBSERVATION,
                subjectId, predicate, operator, objectId, literalValue, state.id(), source, origin,
                observedAt, validUntil, confidence, 0.0, 0, refreshUnitId, List.of());
        return upsertContext(snapshot, persist);
    }

    public Optional<WorldObject> getObject(String id) {
        WorldObject cached = objectCache.get(id);
        if (cached != null) return Optional.of(cached);
        try {
            return objectRepository.findById(id).map(this::fromNode).map(value -> {
                objectCache.put(value.id(), value);
                return value;
            });
        } catch (Exception e) {
            log.debug("Neo4j 暂不可用，世界对象仅从内存读取: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<ContextUnit> getContext(String id) {
        ContextUnit cached = contextCache.get(id);
        if (cached != null) return Optional.of(cached);
        try {
            return contextRepository.findById(id).map(this::fromNode).map(value -> {
                contextCache.put(value.id(), value);
                return value;
            });
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public Optional<ContextUnit> findCurrentValue(String subjectId, String predicate) {
        Instant now = Instant.now();
        Optional<ContextUnit> cached = contextCache.values().stream()
                .filter(c -> !c.isExpired(now))
                .filter(ContextUnit::isWorldValue)
                .filter(c -> java.util.Objects.equals(subjectId, c.subjectId()))
                .filter(c -> java.util.Objects.equals(predicate, c.predicate()))
                .max(Comparator.comparing(c -> c.observedAt() == null ? Instant.EPOCH : c.observedAt()));
        try {
            Optional<ContextUnit> persisted = contextRepository
                    .findLatestEvidence(subjectId, predicate, now.toEpochMilli()).map(this::fromNode);
            if (persisted.isEmpty()) return cached;
            ContextUnit databaseValue = persisted.get();
            contextCache.put(databaseValue.id(), databaseValue);
            if (cached.isEmpty()) return persisted;
            Instant cacheTime = cached.get().observedAt() == null ? Instant.EPOCH : cached.get().observedAt();
            Instant dbTime = databaseValue.observedAt() == null ? Instant.EPOCH : databaseValue.observedAt();
            return Optional.of(dbTime.isAfter(cacheTime) ? databaseValue : cached.get());
        } catch (Exception e) {
            return cached;
        }
    }

    /** 当前证据指向的标准状态。 */
    public Optional<ContextUnit> findCurrentState(String subjectId, String predicate) {
        return findCurrentValue(subjectId, predicate)
                .flatMap(evidence -> getContext(evidence.stateId()))
                .filter(ContextUnit::isStateDefinition);
    }

    public Optional<ContextUnit> getState(String stateId) {
        return getContext(stateId).filter(ContextUnit::isStateDefinition);
    }

    public List<ContextUnit> currentContext(int limit) {
        Instant now = Instant.now();
        Map<String, ContextUnit> merged = new ConcurrentHashMap<>();
        contextCache.values().stream().filter(c -> !c.isExpired(now)).forEach(c -> merged.put(c.id(), c));
        try {
            contextRepository.findCurrent(now.toEpochMilli(), Math.max(limit, 1)).stream()
                    .map(this::fromNode).forEach(c -> merged.putIfAbsent(c.id(), c));
        } catch (Exception e) {
            log.debug("Neo4j 暂不可用，环境上下文仅使用内存快照: {}", e.getMessage());
        }
        return merged.values().stream()
                .sorted(Comparator.comparingDouble((ContextUnit c) -> c.effectiveConfidence(now)).reversed())
                .limit(Math.max(limit, 1)).toList();
    }

    public int evictExpired(Instant now) {
        int before = contextCache.size();
        contextCache.entrySet().removeIf(e -> e.getValue().isExpired(now));
        return before - contextCache.size();
    }

    public void evictContext(String contextId) { contextCache.remove(contextId); }

    public List<WorldObject> expand(String rootId, int maxDepth) {
        List<WorldObject> result = new ArrayList<>();
        expand(rootId, Math.max(0, maxDepth), ConcurrentHashMap.newKeySet(), result);
        return List.copyOf(result);
    }

    private void expand(String id, int remainingDepth, java.util.Set<String> visited,
                        List<WorldObject> result) {
        if (!visited.add(id)) return;
        Optional<WorldObject> found = getObject(id);
        if (found.isEmpty()) return;
        WorldObject object = found.get();
        result.add(object);
        if (remainingDepth == 0) return;
        object.componentIds().forEach(child -> expand(child, remainingDepth - 1, visited, result));
    }

    private WorldObjectNode toNode(WorldObject value) {
        WorldObjectNode node = new WorldObjectNode();
        node.setObjectId(value.id()); node.setObjectType(value.type()); node.setName(value.name());
        node.setDescription(value.description()); node.setParentId(value.parentId());
        node.setComponentIdsJson(write(value.componentIds())); node.setAttributesJson(write(value.attributes()));
        node.setCreatedAt(epoch(value.createdAt())); node.setUpdatedAt(epoch(value.updatedAt()));
        return node;
    }

    private WorldObject fromNode(WorldObjectNode node) {
        return new WorldObject(node.getObjectId(), node.getObjectType(), node.getName(), node.getDescription(),
                node.getParentId(), read(node.getComponentIdsJson(), LIST_TYPE, List.of()),
                read(node.getAttributesJson(), MAP_TYPE, Map.of()), instant(node.getCreatedAt()), instant(node.getUpdatedAt()));
    }

    private ContextUnitNode toNode(ContextUnit value) {
        ContextUnitNode node = new ContextUnitNode();
        node.setContextId(value.id()); node.setContextRole(value.role().name()); node.setSubjectId(value.subjectId());
        node.setPredicate(value.predicate()); node.setOperator(value.operator().name()); node.setObjectId(value.objectId());
        node.setLiteralValueJson(value.literalValue() == null ? null : write(value.literalValue()));
        node.setStateId(value.stateId());
        node.setSource(value.source()); node.setOrigin(value.origin().name());
        node.setObservedAt(epoch(value.observedAt())); node.setValidUntil(epoch(value.validUntil()));
        node.setConfidence(value.confidence()); node.setRequiredConfidence(value.requiredConfidence());
        node.setMaximumAgeMillis(value.maximumAgeMillis()); node.setRefreshUnitId(value.refreshUnitId());
        node.setEvidenceIdsJson(write(value.evidenceIds()));
        node.setActive(!value.isExpired(Instant.now())); node.setUpdatedAt(System.currentTimeMillis());
        return node;
    }

    private ContextUnit fromNode(ContextUnitNode node) {
        return new ContextUnit(node.getContextId(), ContextUnit.Role.valueOf(node.getContextRole()),
                node.getSubjectId(), node.getPredicate(), ContextUnit.Operator.valueOf(node.getOperator()),
                node.getObjectId(), readAny(node.getLiteralValueJson()),
                node.getStateId(),
                node.getSource(), ContextUnit.Origin.valueOf(node.getOrigin()), instant(node.getObservedAt()),
                instant(node.getValidUntil()), node.getConfidence(), node.getRequiredConfidence(),
                node.getMaximumAgeMillis(), node.getRefreshUnitId(),
                read(node.getEvidenceIdsJson(), LIST_TYPE, List.of()));
    }

    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("无法序列化世界模型数据", e); }
    }
    private Object readAny(String json) {
        if (json == null) return null;
        try { return mapper.readValue(json, Object.class); }
        catch (Exception e) { return json; }
    }
    private <T> T read(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) return fallback;
        try { return mapper.readValue(json, type); }
        catch (Exception e) { return fallback; }
    }
    private static Long epoch(Instant value) { return value == null ? null : value.toEpochMilli(); }
    private static Instant instant(Long value) { return value == null ? null : Instant.ofEpochMilli(value); }

    private ContextUnit newerOf(ContextUnit current, ContextUnit incoming) {
        if (current == null || current.observedAt() == null || incoming.observedAt() == null) return incoming;
        return incoming.observedAt().isBefore(current.observedAt()) ? current : incoming;
    }

    private String stateId(String subjectId, String predicate, ContextUnit.Operator operator,
                           String objectId, Object literalValue) {
        String raw = objectId != null ? "object:" + objectId
                : literalValue instanceof String s ? s : write(literalValue);
        String valuePart = raw.matches("[A-Za-z0-9_.-]{1,48}") ? raw : sha256(raw).substring(0, 16);
        return "state:" + subjectId + ":" + predicate + ":" + valuePart;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) { throw new IllegalStateException("无法生成状态 ID", e); }
    }
    private void safely(Runnable operation, String description) {
        try { operation.run(); }
        catch (Exception e) { log.warn("{}失败，已保留内存状态: {}", description, e.getMessage()); }
    }
}
