package com.example.myhelper.persistence;

import com.example.myhelper.world.model.WorldEntity;
import com.example.myhelper.world.model.EntityRef;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.neo4j.driver.exceptions.ServiceUnavailableException;
import org.neo4j.driver.exceptions.SessionExpiredException;

/**
 * 统一数据库读写协调器。来源和目标由调用方指定，不根据实体类型选库或展开引用。
 * 每次批量写入是一个提交单元，仅非空目标分支参与准备、提交及失败补偿。
 * 当前采用同步快照补偿，不提供跨库原子事务或进程崩溃后的自动恢复。
 */
public final class DualWriteStore {
    /** 物理数据库标识，不是实体分类。 */
    public enum Database {
        /** Neo4j 图数据库。 */
        NEO4J,
        /** Qdrant 向量及实体内容存储。 */
        QDRANT
    }

    /** Neo4j 读写适配器。 */
    private final Participant graph;
    /** Qdrant 读写适配器。 */
    private final Participant vector;

    /** 注入两个适配器；构造过程不访问数据库。 */
    public DualWriteStore(Participant graph, Participant vector) {
        // 图适配器必须存在，但对应数据库不必在构造时在线。
        this.graph = Objects.requireNonNull(graph);
        // 向量适配器必须存在，实际使用由本次调用参数决定。
        this.vector = Objects.requireNonNull(vector);
    }

    /** 向明确指定的库保存一个实体，不递归保存引用目标。 */
    public void write(Database target, WorldEntity entity) {
        // 单条写入复用批量写入的校验与补偿流程。
        writeBatch(target, List.of(entity));
    }

    /** 向指定单库写入一批实体，不访问另一个库。 */
    public void writeBatch(Database target, List<WorldEntity> entities) {
        // 拒绝空目标，避免隐式选择数据库。
        Objects.requireNonNull(target, "target 不能为空");
        // 转成只包含一个目标的提交计划。
        writeBatch(Map.of(target, entities));
    }

    /**
     * 根据显式目标列表统一提交；同一实体写两库时须分别列入两个目标。
     * 空列表不参与操作，同一目标内禁止重复 ID，两库可分别保存同一 ID。
     * synchronized 仅串行化本实例；调用方须避免其他写入者并发覆盖同一实体。
     */
    public synchronized void writeBatch(Map<Database, List<WorldEntity>> targets) {
        // 在任何数据库访问前检查计划本身。
        Objects.requireNonNull(targets, "targets 不能为空");
        // EnumMap 使参与分支按 NEO4J、QDRANT 的固定顺序执行。
        Map<Database, List<WorldEntity>> batches = new EnumMap<>(Database.class);
        // 先检查全部目标，避免后一分支非法时前一分支已被访问。
        for (var entry : targets.entrySet()) {
            // 目标必须由调用方明确提供。
            Database target = Objects.requireNonNull(entry.getKey(), "目标库不能为空");
            // 复制列表，同时拒绝空列表引用或空实体元素。
            List<WorldEntity> batch = List.copyOf(entry.getValue());
            // 每个目标独立检查重复编号，允许相同编号出现在另一库。
            Set<String> ids = new HashSet<>();
            // 扫描当前目标的全部实体。
            for (WorldEntity entity : batch) {
                // 同库重复 ID 会造成同批次覆盖歧义，直接拒绝。
                if (!ids.add(entity.id())) {
                    // 明确指出重复发生在哪个目标库。
                    throw new IllegalArgumentException(target + " 批次存在重复实体 ID: " + entity.id());
                }
            }
            // 空分支不会准备、提交或补偿。
            if (!batch.isEmpty()) {
                // 保存本次需要实际执行的分支。
                batches.put(target, batch);
            }
        }
        // 空计划是无副作用操作。
        if (batches.isEmpty()) {
            // 没有任何数据库调用。
            return;
        }
        // 两个参与分支共用一个编号，便于定位本次失败。
        String operationId = UUID.randomUUID().toString();
        // 保存已打开的操作句柄，失败后按逆序补偿。
        List<Change> changes = new ArrayList<>();
        // 捕获准备或提交阶段的运行异常。
        try {
            // open 不得修改远端资源，必须先取得所有补偿入口。
            for (var entry : batches.entrySet()) {
                // 只分派调用方指定的目标，不检查实体类型。
                Participant adapter = participant(entry.getKey());
                // 打开当前库的操作句柄并拒绝适配器返回空值。
                changes.add(Objects.requireNonNull(adapter.open(operationId, entry.getValue())));
            }
            // 两库都先准备好快照，才进入提交阶段。
            for (Change change : changes) {
                // 准备当前分支的写前状态。
                change.prepare();
            }
            // 按固定顺序提交实际参与的分支。
            for (Change change : changes) {
                // 单库成功不代表整个批次成功，后续失败仍会补偿。
                change.commit();
            }
        // 包括远端可能部分成功的异常也进入补偿流程。
        } catch (RuntimeException failure) {
            // 保留操作编号和原始失败原因。
            WriteFailure result = new WriteFailure(operationId, failure);
            // 逆序补偿，包括抛出异常的分支。
            for (int i = changes.size() - 1; i >= 0; i--) {
                // 某一分支补偿失败不能阻止其他分支恢复。
                try {
                    // 适配器自行处理未开始写入时的无操作补偿。
                    changes.get(i).rollback();
                // 补偿错误作为附加异常保留。
                } catch (RuntimeException rollbackFailure) {
                    // 不覆盖原始提交错误。
                    result.addSuppressed(rollbackFailure);
                }
            }
            // 让调用方获得完整的失败与补偿结果。
            throw result;
        }
    }

    /**
     * 仅在目标 ID 尚无实体内容时创建；true 为本次提交成功，false 表示已经存在。
     * 当前只有 Neo4j 提供数据库事务级条件写，Qdrant 明确拒绝。
     */
    public synchronized boolean writeIfAbsent(Database target, WorldEntity entity) {
        // 校验目标及实体，禁止隐式改写存储来源。
        requireConditionalTarget(target, entity);
        // null 前置快照只在参与者内部表示预期不存在。
        return participant(target).writeConditionally(null, entity);
    }

    /**
     * 完整旧快照仍一致才替换，包含统计与 data，不只比较业务 revision。
     * false 时由上层重新读取及决定重算；不在事务里调用业务回调或外部服务。
     */
    public synchronized boolean compareAndSet(Database target, WorldEntity expected, WorldEntity replacement) {
        // 目标必须具备实际事务级实现。
        requireConditionalTarget(target, replacement);
        // 更新操作必须提供实际读到的旧实体。
        Objects.requireNonNull(expected, "expected 不能为空");
        // 条件只能作用于同一实体，不能借此移动身份。
        if (!expected.id().equals(replacement.id())) {
            // 参数错误在接触数据库前报告。
            throw new IllegalArgumentException("条件更新前后必须使用同一实体 ID");
        }
        // 当前协调实例与普通批次写共用锁，数据库内部另持有节点写锁。
        return participant(target).writeConditionally(expected, replacement);
    }

    /** 不支持条件写的数据库不能退化为先查再覆盖。 */
    private void requireConditionalTarget(Database target, WorldEntity entity) {
        // 明确目标，不能从实体类型推断。
        Objects.requireNonNull(target, "target 不能为空");
        // 空实体不能进入数据库事务。
        Objects.requireNonNull(entity, "entity 不能为空");
        // 本轮不伪造 Qdrant 或跨库原子比较能力。
        if (target != Database.NEO4J) {
            // 调用方自行决定业务权威数据的存储归属。
            throw new UnsupportedOperationException("当前仅 NEO4J 支持条件写入");
        }
    }

    /** 从指定来源读取当前实体，缺失返回空，不回退到另一个库或展开引用。 */
    public Optional<WorldEntity> read(Database source, String id) {
        // 来源必须显式提供。
        Objects.requireNonNull(source, "source 不能为空");
        // 避免向数据库发送空编号查询。
        if (id == null || id.isBlank()) {
            // 将无效调用作为参数错误报告。
            throw new IllegalArgumentException("id 不能为空");
        }
        // 将读取请求交给明确指定的适配器。
        return participant(source).read(id);
    }

    /** 按显式来源读取引用目标本身，核对类型及可选修订，不展开下一层引用。 */
    public Optional<WorldEntity> read(Database source, EntityRef ref) {
        // 引用只描述目标，不能代替物理来源参数。
        Objects.requireNonNull(ref, "ref 不能为空");
        // 复用单实体读取，不猜测另一个库中是否有数据。
        Optional<WorldEntity> result = read(source, ref.id());
        // 当前实体不存在时保持缺失语义。
        if (result.isEmpty()) {
            // 不伪造历史修订或引用占位实体。
            return result;
        }
        // 仅校验返回实体是否符合调用方传入的引用身份。
        WorldEntity entity = result.get();
        // 类型或指定修订不符时，当前库中没有该目标版本。
        if (ref.worldObjectType() != entity.worldObjectType() || ref.contextUnitType() != entity.contextUnitType()
                || ref.unitType() != entity.unitType() || ref.semanticType() != entity.semanticType()
                || (ref.revision() != null && ref.revision() != entity.revision())) {
            // 不返回同 ID 的其他类型或较新版本冒充匹配。
            return Optional.empty();
        }
        // 原样返回完整实体及其引用。
        return result;
    }

    /** 一次按多个 ID 读取；按输入首次出现顺序返回，缺失 ID 不进入结果。 */
    public Map<String, WorldEntity> readBatch(Database source, List<String> ids) {
        // 即使列表为空也要求调用方明确来源。
        Objects.requireNonNull(source, "source 不能为空");
        // 复制输入，拒绝 null 列表及元素。
        List<String> requested = List.copyOf(ids);
        // 不为一个空批次访问数据库。
        if (requested.isEmpty()) {
            // 空结果与所有实体缺失具有相同形状。
            return Map.of();
        }
        // 约束传入数量，避免巨型 IN 查询或 HTTP 请求。
        StorageQuery.limit(requested.size());
        // 在任何查询前检查所有 ID。
        for (String id : requested) {
            // 不允许部分查完才发现后续参数错误。
            StorageQuery.text(id, "id");
        }
        // 去重保留顺序，实际适配器按批次访问数据库。
        List<String> unique = List.copyOf(new LinkedHashSet<>(requested));
        // 不把后端返回顺序作为公共合同。
        Map<String, WorldEntity> loaded = participant(source).readBatch(unique);
        // 按调用方输入顺序形成只读结果。
        Map<String, WorldEntity> ordered = new LinkedHashMap<>();
        // 缺失项省略，不填 null 实体。
        for (String id : unique) {
            // 从本次批量结果中取值。
            WorldEntity entity = loaded.get(id);
            // 仅收集真实存在的实体。
            if (entity != null) {
                // 适配器不得把别的实体挂到请求 ID 下。
                if (!id.equals(entity.id())) {
                    // 协议故障不能假装读取成功。
                    throw new IllegalStateException("批量读取返回的实体 ID 不匹配");
                }
                // 保留原始实体，不合并引用或重写内容。
                ordered.put(id, entity);
            }
        }
        // 对外不暴露可变的结果映射。
        return Collections.unmodifiableMap(ordered);
    }

    /** 两库均支持字段条件分页；条件不决定库，读取不自动遍历全部页面。 */
    public StorageQuery.Page query(Database source, StorageQuery.PageRequest request) {
        // 明确查询来源。
        Objects.requireNonNull(source, "source 不能为空");
        // 请求包含已经校验的数量和字段条件。
        Objects.requireNonNull(request, "request 不能为空");
        // 拒绝把另一个数据库的分页位置用于当前查询。
        if (request.cursor() != null && request.cursor().source() != source) {
            // 参数错误在访问数据库前返回。
            throw new IllegalArgumentException("分页游标来源不匹配");
        }
        // 按物理来源执行后端查询。
        return participant(source).query(request);
    }

    /** 向量候选查询；当前只有 Qdrant 实现，不能静默切换调用方指定的来源。 */
    public List<StorageQuery.Hit> search(Database source, StorageQuery.VectorRequest request) {
        // 来源不可由向量字段自动推断。
        Objects.requireNonNull(source, "source 不能为空");
        // 非法查询对象不能进入适配器。
        Objects.requireNonNull(request, "request 不能为空");
        // 不支持的来源明确失败，而不是偷偷改查另一个库。
        if (source != Database.QDRANT) {
            // 图端当前未建立向量索引。
            throw new UnsupportedOperationException("当前仅 QDRANT 支持向量查询");
        }
        // 同步只读调用不持有写入协调锁，允许不同分支并发查询。
        return participant(source).search(request);
    }

    /** 只按物理库标识分派，业务存储映射由上层决定。 */
    private Participant participant(Database database) {
        // 两个分支都由调用参数直接选择。
        return switch (database) {
            // 图数据库请求使用图适配器。
            case NEO4J -> graph;
            // Qdrant 请求使用向量及实体适配器。
            case QDRANT -> vector;
        };
    }

    /** 两库均完整保存实体与引用；open 必须无副作用并返回可补偿句柄。 */
    public interface Participant {
        /** 创建当前批次的操作句柄，不写入数据库。 */
        Change open(String operationId, List<WorldEntity> entities);
        /** 数据库本地事务内比较完整旧快照再保存；expected 为 null 表示预期不存在。 */
        default boolean writeConditionally(WorldEntity expected, WorldEntity replacement) {
            // 不用进程内检查冒充数据库原子条件写。
            throw new UnsupportedOperationException("参与者未实现条件写入");
        }
        /** 按编号读取当前库保存的完整实体。 */
        Optional<WorldEntity> read(String id);
        /** 兼容简单参与者；生产适配器覆盖为一次数据库批量请求。 */
        default Map<String, WorldEntity> readBatch(List<String> ids) {
            // 默认实现仍明确限定为当前参与者。
            Map<String, WorldEntity> result = new LinkedHashMap<>();
            // 替身或自定义参与者可复用原有单条读取能力。
            for (String id : ids) {
                // 不自动读取其他实体或引用。
                Optional<WorldEntity> entity = read(id);
                // 缺失项不写入返回映射。
                if (entity.isPresent()) {
                    // 保持输入顺序。
                    result.put(id, entity.get());
                }
            }
            // 返回不可修改的快照。
            return Collections.unmodifiableMap(result);
        }
        /** 实现必须在数据库侧过滤并限制页大小；不支持时显式报错。 */
        default StorageQuery.Page query(StorageQuery.PageRequest request) {
            // 避免把未实现的查询误当成空库。
            throw new UnsupportedOperationException("参与者未实现条件查询");
        }
        /** 向量后端返回分数与完整实体，不判断业务资格。 */
        default List<StorageQuery.Hit> search(StorageQuery.VectorRequest request) {
            // 不以空候选掩盖未接入的检索能力。
            throw new UnsupportedOperationException("参与者未实现向量查询");
        }
    }

    /** 分支保存写前快照，支持准备失败、部分提交及提交后的幂等补偿。 */
    public interface Change {
        /** 取得写前状态，尚不提交业务数据。 */
        void prepare();
        /** 执行当前分支的实际写入。 */
        void commit();
        /** 恢复旧值或移除本次新增内容，不覆盖其他写入者的新值。 */
        void rollback();
    }

    /** 写请求已经发出，但没有可靠回执确认最终结果。 */
    static final class WriteOutcomeUnknown extends IllegalStateException {
        WriteOutcomeUnknown(String message, RuntimeException cause) {
            super(message, cause);
        }
    }

    /** 汇总操作编号、原始错误及补偿错误，不把结果未知误报为回滚完成。 */
    public static final class WriteFailure extends RuntimeException {
        /** 本次操作的追踪编号。 */
        private final String operationId;

        /** 构造带原始原因的统一存储异常。 */
        private WriteFailure(String operationId, RuntimeException cause) {
            // 原始异常保留为 cause，补偿异常另存为 suppressed。
            super("双写失败，操作编号: " + operationId, cause);
            // 记录编号供上层定位操作。
            this.operationId = operationId;
        }

        /** 返回发生失败的操作编号。 */
        public String operationId() {
            // 该编号不代表持久化事务日志。
            return operationId;
        }

        /** 传输结果未知时，补偿请求成功也不能排除原请求迟到。 */
        public boolean rollbackCompleted() {
            // 任一补偿分支报告错误，都不能认定恢复完成。
            if (getSuppressed().length != 0) {
                // 由调用方查看附加异常。
                return false;
            }
            // 沿原因链检查未确认的写入回执和远端连接异常。
            for (Throwable error = getCause(); error != null; error = error.getCause()) {
                // HTTP 通信中断或线程中断可能留下迟到请求。
                if (error instanceof WriteOutcomeUnknown || error instanceof IOException || error instanceof InterruptedException) {
                    // 无法证明原始操作不会继续生效。
                    return false;
                }
                // Neo4j 连接断开或会话过期也可能丢失提交响应。
                if (error instanceof ServiceUnavailableException || error instanceof SessionExpiredException) {
                    // 需要核对实际数据库状态。
                    return false;
                }
            }
            // 所有同步补偿均无错误，且未发现已识别的未知结果情形。
            return true;
        }
    }
}
