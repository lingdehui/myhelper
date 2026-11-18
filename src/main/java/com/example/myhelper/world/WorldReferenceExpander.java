package com.example.myhelper.world;

import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.DualWriteStore.Database;
import com.example.myhelper.persistence.EntityJson;
import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.FieldBinding;
import com.example.myhelper.world.model.WorldEntity;
import com.fasterxml.jackson.core.JsonPointer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/** 按显式来源和本轮授权读取指定字段，保留直接值和同一字段的全部引用路径。 */
public final class WorldReferenceExpander {
    /** 递归按引用深度消耗调用栈，限制单次可申请的最大深度。 */
    private static final int MAX_DEPTH = 64;
    /** 世界模型使用的统一 JSON 格式，保留时间和引用字段的持久化表示。 */
    private final EntityJson entityJson = new EntityJson();
    /** 所有目标通过统一数据库入口按明确来源读取。 */
    private final DualWriteStore store;
    /** 目标来源由世界层映射给出，实体类型本身不决定选库。 */
    private final SourceResolver sources;
    /** 单调时钟，避免系统时间调整影响查询耗时预算。 */
    private final LongSupplier nanoTime;

    public WorldReferenceExpander(DualWriteStore store, SourceResolver sources) {
        this(store, sources, System::nanoTime);
    }

    WorldReferenceExpander(DualWriteStore store, SourceResolver sources, LongSupplier nanoTime) {
        this.store = Objects.requireNonNull(store);
        this.sources = Objects.requireNonNull(sources);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public interface SourceResolver {
        /** 确定这条引用的目标库，缺少位置映射时返回空结果。 */
        Optional<Database> resolve(LocatedRef owner, String slot, EntityRef target);
    }

    public interface ReadAccess {
        /**
         * slot 为空时核对实体及来源的读取资格，非空时授权读取选中字段及其子值。
         * 指定子字段时只返回该字段的数据，读取资格在目标确切修订上再次核对。
         */
        boolean allows(LocatedRef entity, String slot);
    }

    public record Budget(
            /** 允许从根实体走过的引用层数，零表示只取根字段。 */
            int maxDepth,
            /** 字段、引用和字段问题共用的返回条数上限。 */
            int maxItems,
            /** 本轮读取截止预算，底层同步查询仍由存储自身的超时控制。 */
            Duration timeBudget) {
        public Budget {
            Objects.requireNonNull(timeBudget);
            if (maxDepth < 0 || maxDepth > MAX_DEPTH || maxItems < 1 || timeBudget.isZero()
                    || timeBudget.isNegative() || timeBudget.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("引用展开深度、条数或耗时预算无效");
            }
        }
    }

    public record LocatedRef(
            /** 本次确定使用的物理来源。 */
            Database source,
            /** 目标身份及可选修订，读取成功后固定为实际修订。 */
            EntityRef ref) {
        public LocatedRef {
            Objects.requireNonNull(source);
            Objects.requireNonNull(ref);
        }
    }

    public record Hop(
            /** 引用发起实体，已固定修订。 */
            LocatedRef owner,
            /** 引用在发起实体中的 JSON Pointer。 */
            String slot,
            /** 该跳实际读到的确切目标。 */
            LocatedRef target) {}

    public record Field(
            /** 从请求根到字段持有者的引用路径。 */
            List<Hop> path,
            /** 字段持有实体的来源、身份和修订。 */
            LocatedRef owner,
            /** 此次读取的字段指针。 */
            String slot,
            /** 是否真的存在此字段，区别于字段存在但值为 null。 */
            boolean present,
            /** 实际值的只读 JSON 表示，可以为空。 */
            Object value) {}

    public record Link(
            /** 从请求根到发起实体的引用路径。 */
            List<Hop> path,
            /** 发起引用的确切实体。 */
            LocatedRef owner,
            /** 该条引用所在字段。 */
            String slot,
            /** 原始引用，保留调用方指定的确切修订或未指定修订。 */
            EntityRef requested,
            /** 通过读取授权并核对身份、类型、修订的确切目标。 */
            LocatedRef resolved,
            /** 这条引用读取或沿路径继续展开的结果。 */
            Status status) {}

    public record Problem(
            /** 出现问题的已取得路径。 */
            List<Hop> path,
            /** 发生问题的实体身份。 */
            LocatedRef owner,
            /** 涉及字段时保存准确指针。 */
            String slot,
            /** 无法完成该项的原因。 */
            Status status) {}

    public enum Status {
        RESOLVED, MISSING, SOURCE_UNAVAILABLE, ACCESS_DENIED, REVISION_UNAVAILABLE, TYPE_MISMATCH,
        CYCLE, DEPTH_LIMIT, ITEM_LIMIT, TIME_LIMIT, READ_FAILED, INVALID_DATA
    }

    public record Result(
            /** 依请求和绑定顺序返回的字段值。 */
            List<Field> fields,
            /** 每个保留路径上的已处理引用。 */
            List<Link> links,
            /** 根读取、字段和全局预算问题。 */
            List<Problem> problems) {
        public Result {
            fields = List.copyOf(fields);
            links = List.copyOf(links);
            problems = List.copyOf(problems);
        }

        /** 完整性同时考虑源引用失败、预算限制和未展开的循环。 */
        public boolean complete() {
            if (!problems.isEmpty()) {
                return false;
            }
            for (Link link : links) {
                if (link.status() != Status.RESOLVED) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 按路径指定每一跳要读取的 slot，例如 ["/data/device", "/data/name"]。
     * 只访问请求路径，路径末端字段关联的目标也核对身份、修订和实体访问权限。
     */
    public Result expand(Database source, EntityRef root, List<List<String>> slotPaths,
                         Budget budget, ReadAccess access) {
        Objects.requireNonNull(budget);
        LocatedRef requested = new LocatedRef(source, root);
        Traversal walk = new Traversal(budget, Objects.requireNonNull(access));
        Map<String, Selection> selection = select(slotPaths, walk, requested);
        if (selection.isEmpty() || !walk.problems.isEmpty()) {
            return walk.result();
        }
        Load first = walk.load(requested);
        if (first.status() == Status.RESOLVED) {
            walk.visit(first, selection, List.of(), new HashSet<>());
        } else {
            walk.problems.add(new Problem(List.of(), requested, null, first.status()));
        }
        return walk.result();
    }

    private static Map<String, Selection> select(List<List<String>> paths, Traversal walk, LocatedRef root) {
        Map<String, Selection> selected = new LinkedHashMap<>();
        Objects.requireNonNull(paths);
        for (List<String> path : paths) {
            if (!walk.withinTime()) {
                walk.problems.add(new Problem(List.of(), root, null, Status.TIME_LIMIT));
                break;
            }
            if (path.isEmpty()) {
                throw new IllegalArgumentException("引用展开路径必须指定字段");
            }
            Map<String, Selection> current = selected;
            for (String slot : path) {
                if (!walk.withinTime()) {
                    walk.problems.add(new Problem(List.of(), root, null, Status.TIME_LIMIT));
                    return selected;
                }
                if (slot == null || !slot.startsWith("/") || slot.matches("(?s).*~(?![01]).*")) {
                    throw new IllegalArgumentException("无效的字段 JSON Pointer");
                }
                Selection child = current.get(slot);
                if (child == null) {
                    child = new Selection(JsonPointer.compile(slot), new LinkedHashMap<>());
                    current.put(slot, child);
                }
                current = child.children();
            }
        }
        return selected;
    }

    private record Selection(
            /** 选中字段定位。 */
            JsonPointer pointer,
            /** 目标实体获授权后才读取的后续字段，以请求顺序保存。 */
            Map<String, Selection> children) {}

    private record Load(
            /** 精确读到的目标位置。 */
            LocatedRef location,
            /** 本次请求缓存使用的持久化实体。 */
            WorldEntity entity,
            /** 是否读取到合格的数据。 */
            Status status) {
        private static Load failed(Status status) {
            return new Load(null, null, status);
        }
    }

    /** 查询级缓存和预算仅在本次展开中有效。 */
    private final class Traversal {
        /** 当前查询的额度。 */
        private final Budget budget;
        /** 调用方提供的本轮读取授权。 */
        private final ReadAccess access;
        /** 预算起点包括选择器准备时间。 */
        private final long started = nanoTime.getAsLong();
        /** 已解析请求及其确切实体身份，跨分支共享读取结果。 */
        private final Map<LocatedRef, Load> loaded = new HashMap<>();
        /** 只读 JSON 节点快照，按确切来源、类型、ID 和修订缓存。 */
        private final Map<LocatedRef, Object> documents = new HashMap<>();
        /** 已读取的字段。 */
        private final List<Field> fields = new ArrayList<>();
        /** 已处理的引用。 */
        private final List<Link> links = new ArrayList<>();
        /** 字段和全局问题。 */
        private final List<Problem> problems = new ArrayList<>();
        /** 额度内已预留的返回条数。 */
        private int items;
        /** 全局额度用尽后停止遍历，仅保留首个截止位置。 */
        private boolean stopped;

        private Traversal(Budget budget, ReadAccess access) {
            this.budget = budget;
            this.access = access;
        }

        private boolean withinTime() {
            return nanoTime.getAsLong() - started < budget.timeBudget().toNanos();
        }

        private Result result() {
            return new Result(fields, links, problems);
        }

        private boolean reserve(List<Hop> path, LocatedRef owner, String slot) {
            if (stopped) {
                return false;
            }
            if (!withinTime()) {
                problems.add(new Problem(path, owner, slot, Status.TIME_LIMIT));
            } else if (items >= budget.maxItems()) {
                problems.add(new Problem(path, owner, slot, Status.ITEM_LIMIT));
            } else {
                items++;
                return true;
            }
            stopped = true;
            return false;
        }

        private Load load(LocatedRef requested) {
            if (!withinTime()) {
                return Load.failed(Status.TIME_LIMIT);
            }
            if (!access.allows(requested, null)) {
                return Load.failed(Status.ACCESS_DENIED);
            }
            if (!withinTime()) {
                return Load.failed(Status.TIME_LIMIT);
            }
            Load existing = loaded.get(requested);
            if (existing == null) {
                existing = read(requested);
                if (existing.status() != Status.TIME_LIMIT) {
                    loaded.put(requested, existing);
                }
            }
            if (existing.status() == Status.RESOLVED && !access.allows(existing.location(), null)) {
                return Load.failed(Status.ACCESS_DENIED);
            }
            if (!withinTime()) {
                return Load.failed(Status.TIME_LIMIT);
            }
            return existing;
        }

        private Load read(LocatedRef requested) {
            Optional<WorldEntity> result;
            try {
                result = store.read(requested.source(), requested.ref().id());
            } catch (RuntimeException failure) {
                return Load.failed(Status.READ_FAILED);
            }
            if (!withinTime()) {
                return Load.failed(Status.TIME_LIMIT);
            }
            EntityRef expected = requested.ref();
            if (result.isEmpty()) {
                if (expected.revision() != null) {
                    return Load.failed(Status.REVISION_UNAVAILABLE);
                }
                return Load.failed(Status.MISSING);
            }
            WorldEntity entity = result.get();
            EntityRef actual = new EntityRef(entity.id(), entity.revision(), entity.worldObjectType(),
                    entity.contextUnitType(), entity.unitType(), entity.semanticType());
            if (!matchesTypeAndIdentity(expected, actual)) {
                return Load.failed(Status.TYPE_MISMATCH);
            }
            LocatedRef fixed = new LocatedRef(requested.source(), actual);
            Load loadedEntity = new Load(fixed, entity, Status.RESOLVED);
            loaded.putIfAbsent(fixed, loadedEntity);
            EntityRef current = new EntityRef(actual.id(), null, actual.worldObjectType(),
                    actual.contextUnitType(), actual.unitType(), actual.semanticType());
            loaded.putIfAbsent(new LocatedRef(requested.source(), current), loaded.get(fixed));
            if (expected.revision() != null && !expected.revision().equals(actual.revision())) {
                return Load.failed(Status.REVISION_UNAVAILABLE);
            }
            return loaded.get(fixed);
        }

        private void visit(Load owner, Map<String, Selection> selected, List<Hop> path, Set<LocatedRef> ancestors) {
            ancestors.add(owner.location());
            try {
                for (var item : selected.entrySet()) {
                    if (!reserve(path, owner.location(), item.getKey())) {
                        return;
                    }
                    visitField(owner, item.getKey(), item.getValue(), path, ancestors);
                }
            } finally {
                ancestors.remove(owner.location());
            }
        }

        private void visitField(Load owner, String slot, Selection selection, List<Hop> path, Set<LocatedRef> ancestors) {
            if (!access.allows(owner.location(), slot)) {
                problems.add(new Problem(path, owner.location(), slot, Status.ACCESS_DENIED));
                return;
            }
            Object value;
            try {
                Object document = documents.get(owner.location());
                if (document == null) {
                    document = WorldValues.decode(entityJson.encode(owner.entity()));
                    documents.put(owner.location(), document);
                }
                value = fieldValue(document, selection.pointer());
            } catch (IllegalArgumentException failure) {
                problems.add(new Problem(path, owner.location(), slot, Status.INVALID_DATA));
                return;
            }
            if (!withinTime()) {
                problems.add(new Problem(path, owner.location(), slot, Status.TIME_LIMIT));
                stopped = true;
                return;
            }
            boolean present = value != Missing.VALUE;
            fields.add(new Field(path, owner.location(), slot, present, present ? value : null));
            boolean matchingBinding = false;
            for (FieldBinding binding : owner.entity().bindings()) {
                if (!withinTime()) {
                    problems.add(new Problem(path, owner.location(), slot, Status.TIME_LIMIT));
                    stopped = true;
                    return;
                }
                if (!slot.equals(binding.slot())) {
                    continue;
                }
                matchingBinding = true;
                if (!reserve(path, owner.location(), slot)) {
                    return;
                }
                visitLink(owner.location(), binding, selection, path, ancestors);
                if (stopped) {
                    return;
                }
            }
            if (!present && !matchingBinding) {
                fields.removeLast();
                problems.add(new Problem(path, owner.location(), slot, Status.MISSING));
            } else if (!matchingBinding && !selection.children().isEmpty() && reserve(path, owner.location(), slot)) {
                problems.add(new Problem(path, owner.location(), slot, Status.MISSING));
            }
        }

        private void visitLink(LocatedRef owner, FieldBinding binding, Selection selection, List<Hop> path,
                               Set<LocatedRef> ancestors) {
            Load target = loadTarget(owner, binding, path.size());
            Status status = target.status();
            if (status == Status.RESOLVED && ancestors.contains(target.location())) {
                status = Status.CYCLE;
            }
            links.add(new Link(path, owner, binding.slot(), binding.target(), target.location(), status));
            if (status == Status.TIME_LIMIT) {
                stopped = true;
            }
            if (status == Status.RESOLVED && !selection.children().isEmpty()) {
                List<Hop> next = new ArrayList<>(path);
                next.add(new Hop(owner, binding.slot(), target.location()));
                visit(target, selection.children(), List.copyOf(next), ancestors);
            }
        }

        private Load loadTarget(LocatedRef owner, FieldBinding binding, int depth) {
            if (depth >= budget.maxDepth()) {
                return Load.failed(Status.DEPTH_LIMIT);
            }
            Optional<Database> source;
            try {
                source = sources.resolve(owner, binding.slot(), binding.target());
            } catch (RuntimeException failure) {
                return Load.failed(Status.SOURCE_UNAVAILABLE);
            }
            if (!withinTime()) {
                return Load.failed(Status.TIME_LIMIT);
            }
            if (source.isEmpty()) {
                return Load.failed(Status.SOURCE_UNAVAILABLE);
            }
            return load(new LocatedRef(source.get(), binding.target()));
        }
    }

    private static boolean matchesTypeAndIdentity(EntityRef expected, EntityRef actual) {
        return expected.id().equals(actual.id()) && expected.worldObjectType() == actual.worldObjectType()
                && expected.contextUnitType() == actual.contextUnitType() && expected.unitType() == actual.unitType()
                && expected.semanticType() == actual.semanticType();
    }

    private static Object fieldValue(Object current, JsonPointer pointer) {
        while (!pointer.matches()) {
            String property = pointer.getMatchingProperty();
            if (current instanceof Map<?, ?> map && map.containsKey(property)) {
                current = map.get(property);
            } else if (current instanceof List<?> list) {
                int index = pointer.getMatchingIndex();
                if (index < 0 || index >= list.size()) {
                    return Missing.VALUE;
                }
                current = list.get(index);
            } else {
                return Missing.VALUE;
            }
            pointer = pointer.tail();
        }
        return current;
    }

    private enum Missing {
        VALUE
    }
}
