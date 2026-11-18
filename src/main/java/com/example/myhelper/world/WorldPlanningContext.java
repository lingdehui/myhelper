package com.example.myhelper.world;

import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.orchestration.WorldContextPort;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.EntityJson;
import com.example.myhelper.world.model.EntityRef;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 将可信状态和明确选中的历史转换为本轮可序列化的只读证据。 */
public final class WorldPlanningContext implements WorldContextPort {
    /** 单轮最多投影的世界记录数，状态、问题、历史合用。 */
    private static final int MAX_ITEMS = 30;
    /** 世界数据序列化后的上限，留出 JSON 包装空间。 */
    private static final int MAX_BYTES = 32 * 1024;
    /** 单轮世界查询额外耗时上限。 */
    private static final Duration ROUND_BUDGET = Duration.ofSeconds(2);
    /** 由宿主按本轮任务和权限选择可见对象、属性和范围。 */
    private final Selector selector;
    /** 世界状态和历史读取服务。 */
    private final ContextService contexts;
    /** 只展开宿主本轮显式授权的字段路径。 */
    private final WorldReferenceExpander references;
    /** 一轮内共用的截止时钟。 */
    private final Clock clock;
    /** 将字段路径和精确引用转换为规划请求中的 JSON 结构。 */
    private final EntityJson json = new EntityJson();

    public WorldPlanningContext(ContextService contexts, WorldReferenceExpander references, Selector selector, Clock clock) {
        this.contexts = Objects.requireNonNull(contexts);
        this.references = Objects.requireNonNull(references);
        this.selector = Objects.requireNonNull(selector);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Map<String, Object> snapshot(AgentOrchestrator.PlanningContext context) {
        Instant asOf = clock.instant();
        var snapshot = new Snapshot(asOf);
        long started = System.nanoTime();
        List<Selection> selections = List.copyOf(selector.select(context));
        for (Selection selection : selections) {
            long remaining = ROUND_BUDGET.toNanos() - (System.nanoTime() - started);
            if (remaining <= 0 || snapshot.full()) {
                snapshot.truncated = true;
                break;
            }
            var policy = selection.policy();
            Duration available = Duration.ofNanos(remaining);
            if (policy.timeBudget().compareTo(available) > 0) {
                policy = new WorldState.Policy(policy.trustedSources(), policy.minimumConfidence(), policy.maximumAge(),
                        policy.pageSize(), policy.maxPages(), available);
            }
            var query = new WorldState.Query(selection.key(), selection.scope(), asOf, policy);
            if (selection.historical()) {
                addHistory(snapshot, query);
            } else {
                addCurrent(snapshot, query);
            }
        }
        addReferences(context, snapshot, started);
        return WorldValues.object(snapshot.data());
    }

    private void addReferences(AgentOrchestrator.PlanningContext context, Snapshot snapshot, long started) {
        for (ReferenceSelection selection : List.copyOf(selector.references(context))) {
            long remaining = ROUND_BUDGET.toNanos() - (System.nanoTime() - started);
            if (remaining <= 0 || snapshot.full()) {
                snapshot.truncated = true;
                return;
            }
            WorldReferenceExpander.Budget requested = selection.budget();
            var budget = new WorldReferenceExpander.Budget(
                    requested.maxDepth(), Math.min(requested.maxItems(), snapshot.remaining()),
                    Duration.ofNanos(Math.min(remaining, requested.timeBudget().toNanos())));
            var expanded = references.expand(selection.source(), selection.root(), selection.slotPaths(),
                    budget, selection.access());
            if (!expanded.complete()) {
                snapshot.truncated = true;
            }
            for (var field : expanded.fields()) {
                if (!snapshot.add("references", projection("FIELD", field))) {
                    return;
                }
            }
            for (var link : expanded.links()) {
                if (!snapshot.add("references", projection("LINK", link))) {
                    return;
                }
            }
            for (var problem : expanded.problems()) {
                if (!snapshot.add("references", projection("PROBLEM", problem))) {
                    return;
                }
            }
        }
    }

    private Map<String, Object> projection(String type, Object value) {
        return Map.of("use", "REFERENCE", "type", type, "detail", WorldValues.decode(json.encode(value)));
    }

    private void addHistory(Snapshot snapshot, WorldState.Query query) {
        var history = contexts.history(query);
        if (!history.complete()) {
            snapshot.add("unknown", problem(query, WorldState.Status.INCOMPLETE));
        }
        for (WorldState.Evidence row : history.observations()) {
            if (!snapshot.add("observations", observation(query, row, "HISTORICAL"))) {
                return;
            }
        }
    }

    private void addCurrent(Snapshot snapshot, WorldState.Query query) {
        var result = contexts.current(query);
        switch (result.status()) {
            case KNOWN -> snapshot.add("observations", observation(query, result.current(), "CURRENT"));
            case CONFLICT -> {
                Map<String, Object> conflict = problem(query, result.status());
                List<Map<String, Object>> alternatives = new ArrayList<>();
                // 一定数量的冲突证据足以说明当前不可裁决。
                for (var evidence : result.conflicting()) {
                    if (alternatives.size() == MAX_ITEMS) {
                        snapshot.truncated = true;
                        break;
                    }
                    alternatives.add(observation(query, evidence, "CONFLICTING"));
                }
                conflict.put("alternatives", alternatives);
                if (!snapshot.add("conflicts", conflict)) {
                    snapshot.add("conflicts", problem(query, result.status()));
                }
            }
            default -> {
                Map<String, Object> problem = problem(query, result.status());
                if (result.lastKnown() != null) {
                    problem.put("lastKnown", observation(query, result.lastKnown(), "HISTORICAL"));
                }
                if (!snapshot.add("unknown", problem)) {
                    snapshot.add("unknown", problem(query, result.status()));
                }
            }
        }
    }

    private static Map<String, Object> observation(WorldState.Query query, WorldState.Evidence row, String use) {
        var model = key(query);
        model.put("use", use);
        model.put("value", row.value());
        model.put("observedAt", row.observedAt() == null ? null : row.observedAt().toString());
        model.put("validUntil", row.validUntil() == null ? null : row.validUntil().toString());
        model.put("confidence", row.confidence());
        model.put("state", ref(row.state()));
        Map<String, Object> evidence = ref(row.observation());
        evidence.put("source", row.source());
        model.put("evidence", evidence);
        return model;
    }

    private static Map<String, Object> key(WorldState.Query query) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("subjectId", query.key().subjectId());
        item.put("predicate", query.key().predicate());
        item.put("scope", query.scope());
        return item;
    }

    private static Map<String, Object> problem(WorldState.Query query, WorldState.Status status) {
        var model = key(query);
        model.put("status", status.name());
        return model;
    }

    private static Map<String, Object> ref(EntityRef ref) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", ref.id());
        result.put("revision", ref.revision());
        result.put("contextUnitType", ref.contextUnitType().name());
        return result;
    }

    /** 只由应用提供，用任务身份和授权选择本轮可见信息。 */
    public interface Selector {
        List<Selection> select(AgentOrchestrator.PlanningContext context);

        /** 指定可作为背景证据发送的字段。默认不展开任何引用。 */
        default List<ReferenceSelection> references(AgentOrchestrator.PlanningContext context) {
            return List.of();
        }
    }

    public record ReferenceSelection(
            /** 明确选择的根实体存放位置。 */
            DualWriteStore.Database source,
            /** 要访问的实体，支持精确修订。 */
            EntityRef root,
            /** 每条都是从根开始的 JSON Pointer 逐跳路径。 */
            List<List<String>> slotPaths,
            /** 本次展开使用的深度、数量和耗时限制。 */
            WorldReferenceExpander.Budget budget,
            /** 对本轮实体与字段的读取授权。 */
            WorldReferenceExpander.ReadAccess access) {
        public ReferenceSelection {
            Objects.requireNonNull(source);
            Objects.requireNonNull(root);
            Objects.requireNonNull(budget);
            Objects.requireNonNull(access);
            List<List<String>> copy = new ArrayList<>();
            for (var path : slotPaths) {
                copy.add(List.copyOf(path));
            }
            slotPaths = List.copyOf(copy);
        }
    }

    public record Selection(
            /** 已获授权的对象和属性。 */
            WorldState.Key key,
            /** 本次查询的精确范围。 */
            Map<String, Object> scope,
            /** 本轮的数据可信及分页策略。 */
            WorldState.Policy policy,
            /** 为真时查询历史参考。 */
            boolean historical) {
        public Selection {
            Objects.requireNonNull(key);
            scope = WorldValues.object(scope);
            Objects.requireNonNull(policy);
        }
    }

    private static final class Snapshot {
        /** 每轮固定的快照编号。 */
        private final String id = UUID.randomUUID().toString();
        /** 统一取证时点。 */
        private final Instant asOf;
        /** 当前或标记为历史的状态证据。 */
        private final List<Map<String, Object>> observations = new ArrayList<>();
        /** 可信证据冲突。 */
        private final List<Map<String, Object>> conflicts = new ArrayList<>();
        /** 不能用于本次判断的状态。 */
        private final List<Map<String, Object>> unknown = new ArrayList<>();
        /** 本轮授权展开的字段、引用及读取问题。 */
        private final List<Map<String, Object>> references = new ArrayList<>();
        /** 触发预算限制时为真。 */
        private boolean truncated;

        Snapshot(Instant asOf) {
            this.asOf = asOf;
        }

        boolean full() {
            return remaining() == 0;
        }

        int remaining() {
            return MAX_ITEMS - observations.size() - conflicts.size() - unknown.size() - references.size();
        }

        boolean add(String column, Map<String, Object> entry) {
            if (full()) {
                truncated = true;
                return false;
            }
            var target = switch (column) {
                case "observations" -> observations;
                case "conflicts" -> conflicts;
                case "unknown" -> unknown;
                case "references" -> references;
                default -> throw new IllegalArgumentException("未知的世界上下文栏目");
            };
            target.add(entry);
            if (WorldValues.canonical(data()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES - 1) {
                target.removeLast();
                truncated = true;
                return false;
            }
            return true;
        }

        Map<String, Object> data() {
            return Map.of("snapshotId", id, "asOf", asOf.toString(), "observations", observations,
                    "conflicts", conflicts, "unknown", unknown, "references", references, "truncated", truncated);
        }
    }
}
