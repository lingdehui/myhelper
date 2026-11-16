package com.example.myhelper.skill;

import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.world.model.ContextUnitType;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import com.example.myhelper.world.model.FailureCause;
import com.example.myhelper.world.model.WorldEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** 已执行参数的权威事实仓储。每次执行在一个实体内保存，分数随事实派生。 */
public final class ToolParameterStore {
    /** Neo4j 中用于区分本模块观测的标识。 */
    private static final String OBSERVATION_KIND = "skill-tool-execution";
    /** 统一数据库入口。 */
    private final DualWriteStore storage;
    /** 在业务记录和实体 JSON 之间转换。 */
    private final ObjectMapper json = new ObjectMapper();

    public ToolParameterStore(DualWriteStore storage) {
        this.storage = Objects.requireNonNull(storage);
    }

    /** 幂等保存真实观测并返回已有的范围关联。冲突及写入失败向调用方报告。 */
    public Optional<String> record(Execution execution) {
        Scope scope = execution.scope();
        String id = executionEntityId(execution);

        Map<String, Object> data = Map.of(
                "kind", OBSERVATION_KIND,
                "toolName", scope.tool().toolName(),
                "sourceInstance", scope.tool().sourceInstance(),
                "implementationVersion", scope.tool().implementationVersion(),
                "rangeId", scope.rangeId(),
                "execution", json.convertValue(execution, new TypeReference<Map<String, Object>>() {}));
        WorldEntity observation = new WorldEntity(id, null, ContextUnitType.OBSERVATION, null, null,
                null, null, data, List.of(), null, 1, execution.source(),
                List.of(), Map.of(), null, null);
        if (storage.writeIfAbsent(DualWriteStore.Database.NEO4J, observation)) {
            return Optional.empty();
        }
        WorldEntity saved = storage.read(DualWriteStore.Database.NEO4J, id).orElse(null);
        if (saved == null) {
            throw new IllegalStateException("条件写冲突后找不到工具执行证据 " + id);
        }
        if (!saved.evidence().equals(observation.evidence())
                || !Objects.equals(saved.source(), observation.source())
                || !canonicalJson(saved.data().get("execution")).equals(canonicalJson(data.get("execution")))) {
            throw new IllegalStateException("同一次工具执行已保存不同的参数或结果");
        }
        return Optional.ofNullable((String) saved.data().get("approvedRangeId"));
    }

    private String executionEntityId(Execution execution) {
        String key = json.valueToTree(List.of(execution.scope().tool(), execution.executionId())).toString();
        return OBSERVATION_KIND + ":" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    /** 将已保存的事实关联到当前已批准范围，原始参数和结果保持可读。 */
    public void associateApprovedRange(Execution execution, String rangeId) {
        String id = executionEntityId(execution);
        WorldEntity old = storage.read(DualWriteStore.Database.NEO4J, id).orElse(null);
        if (old == null) {
            throw new IllegalStateException("关联范围前必须保存工具执行证据");
        }
        if (old.data().get("approvedRangeId") instanceof String previous) {
            if (previous.equals(rangeId)) {
                return;
            }
            throw new IllegalStateException("同一次工具执行已关联其他验证范围");
        }
        var data = new java.util.LinkedHashMap<>(old.data());
        data.put("approvedRangeId", rangeId);
        WorldEntity replacement = new WorldEntity(id, null, ContextUnitType.OBSERVATION, null, null,
                null, null, data, List.of(), null, Math.incrementExact(old.revision()), old.source(),
                old.evidence(), Map.of(), null, null);
        if (!storage.compareAndSet(DualWriteStore.Database.NEO4J, old, replacement)) {
            throw new IllegalStateException("参数范围关联并发变化，保留事实等待重试");
        }
    }

    /** 精确样本范围的编号，按完整参数生成，忽略 JSON 对象键顺序。 */
    public String exactRangeId(Map<String, Object> arguments) {
        return "exact:" + UUID.nameUUIDFromBytes(canonicalJson(arguments).getBytes(StandardCharsets.UTF_8));
    }

    private String canonicalJson(Object value) {
        try {
            return json.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("无法编码工具执行证据", failure);
        }
    }

    /** 分页读取同一实现修订和适用范围的事实。 */
    public void forEach(ToolRef ref, Consumer<Execution> consumer) {
        forEach(ref, approvedRanges(ref), consumer);
    }

    public void forEach(ToolRef ref, List<ApprovedRange> approved, Consumer<Execution> consumer) {
        var active = new java.util.HashSet<String>();
        for (ApprovedRange range : approved) {
            active.add(range.id());
        }
        StorageQuery.Filter filter = new StorageQuery.Filter(List.of(
                new StorageQuery.Match("/data/kind", List.of(OBSERVATION_KIND)),
                new StorageQuery.Match("/data/toolName", List.of(ref.toolName())),
                new StorageQuery.Match("/data/sourceInstance", List.of(ref.sourceInstance())),
                new StorageQuery.Match("/data/implementationVersion", List.of(ref.implementationVersion()))));
        StorageQuery.Cursor cursor = null;
        do {
            StorageQuery.Page page = storage.query(DualWriteStore.Database.NEO4J,
                    new StorageQuery.PageRequest(filter, 200, cursor));
            for (WorldEntity row : page.entities()) {
                Execution execution = json.convertValue(row.data().get("execution"), Execution.class);
                if (!ref.equals(execution.scope().tool()) || row.contextUnitType() != ContextUnitType.OBSERVATION) {
                    throw new IllegalStateException("工具执行证据的范围或类型不一致");
                }
                if (row.data().get("approvedRangeId") instanceof String id && active.contains(id)) {
                    execution = new Execution(new Scope(ref, id), execution.executionId(), execution.source(),
                            execution.arguments(), execution.outcome(), execution.result(),
                            execution.error(), execution.failureCause());
                }
                consumer.accept(execution);
            }
            cursor = page.nextCursor();
        } while (cursor != null);
    }

    /** 读取当前批准范围。范围内容只能由独立验证与审核流程提交。 */
    public List<ApprovedRange> approvedRanges(ToolRef ref) {
        WorldEntity index = storage.read(DualWriteStore.Database.NEO4J, rangeIndexId(ref)).orElse(null);
        if (index == null) {
            return List.of();
        }
        return decodeRanges(ref, index);
    }

    /** 原子替换同一工具版本的批准范围集合。期望值过期时由业务方重新读取。 */
    public boolean replaceApprovedRanges(ToolRef ref, List<ApprovedRange> expected, List<ApprovedRange> replacement) {
        Objects.requireNonNull(expected);
        List<ApprovedRange> fixed = List.copyOf(replacement);
        var ids = new java.util.HashSet<String>();
        for (ApprovedRange range : fixed) {
            if (!ids.add(range.id())) {
                throw new IllegalArgumentException("同一工具版本的范围编号重复");
            }
            for (ApprovedRange previous : expected) {
                if (range.id().equals(previous.id()) && !range.equals(previous)) {
                    throw new IllegalArgumentException("修改已验证范围需要新编号");
                }
            }
        }
        String id = rangeIndexId(ref);
        WorldEntity previous = storage.read(DualWriteStore.Database.NEO4J, id).orElse(null);
        List<ApprovedRange> current = previous == null ? List.of() : decodeRanges(ref, previous);
        if (!current.equals(expected)) {
            return false;
        }
        var data = json.convertValue(Map.of("tool", ref, "ranges", fixed),
                new TypeReference<Map<String, Object>>() {});
        long revision = previous == null ? 1 : Math.incrementExact(previous.revision());
        WorldEntity next = new WorldEntity(id, null, ContextUnitType.FACT, null, null,
                null, null, data, List.of(), null, revision, null, List.of(), Map.of(), null, null);
        if (previous == null) {
            return storage.writeIfAbsent(DualWriteStore.Database.NEO4J, next);
        }
        return storage.compareAndSet(DualWriteStore.Database.NEO4J, previous, next);
    }

    private List<ApprovedRange> decodeRanges(ToolRef ref, WorldEntity index) {
        if (index.contextUnitType() != ContextUnitType.FACT
                || !ref.equals(json.convertValue(index.data().get("tool"), ToolRef.class))) {
            throw new IllegalStateException("验证范围与工具版本不一致");
        }
        return List.copyOf(json.convertValue(index.data().get("ranges"), new TypeReference<List<ApprovedRange>>() {}));
    }

    private String rangeIndexId(ToolRef ref) {
        String key = json.valueToTree(ref).toString();
        return "skill-tool-ranges:" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public record ApprovedRange(
            /** 在同一实现版本内标识一组固定约束及其证据。 */
            String id,
            /** 已验证的完整参数和组合约束。 */
            Map<String, Object> constraints,
            /** 独立范围验证与审核证据的引用。 */
            String verificationRef) {
        public ApprovedRange {
            required(id);
            required(verificationRef);
            constraints = TaskPlan.immutableMap(Objects.requireNonNull(constraints));
            if (constraints.isEmpty()) {
                throw new IllegalArgumentException("已验证范围必须包含约束");
            }
        }
    }

    public record Scope(
            /** 固定到调用名、接入实例及不可变实现版本。 */
            ToolRef tool,
            /** 当前实现内可比较统计的适用范围身份。 */
            String rangeId) {
        public Scope {
            Objects.requireNonNull(tool);
            required(rangeId);
        }
    }

    public enum Outcome {
        SUCCESS, FAILURE, UNKNOWN
    }

    public record Execution(
            /** 统计及匹配所用的精确范围。 */
            Scope scope,
            /** 此次实际执行的稳定唯一身份。 */
            String executionId,
            /** 权威执行与验收记录的出处。 */
            String source,
            /** 实际派发的完整参数。 */
            Map<String, Object> arguments,
            /** 验收后的已确认结果或未知状态。 */
            Outcome outcome,
            /** 实际返回值，可为空。 */
            Object result,
            /** 原始错误，可为空。 */
            String error,
            /** 确认失败时的归因，可使用 UNKNOWN 分类。 */
            FailureCause failureCause) {
        public Execution {
            Objects.requireNonNull(scope);
            required(executionId);
            required(source);
            arguments = TaskPlan.immutableMap(Objects.requireNonNull(arguments));
            Objects.requireNonNull(outcome);
            result = TaskPlan.immutable(result);
            if (outcome == Outcome.FAILURE) {
                Objects.requireNonNull(failureCause, "确认失败必须提供失败归因");
            }
        }
    }

    private static void required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("工具执行证据缺少必填标识");
        }
    }

}
