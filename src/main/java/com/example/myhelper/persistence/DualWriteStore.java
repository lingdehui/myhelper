package com.example.myhelper.persistence;

import com.example.myhelper.world.model.WorldEntity;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
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
        /** 按编号读取当前库保存的完整实体。 */
        Optional<WorldEntity> read(String id);
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
            // 沿原因链检查可能造成远端结果未知的连接异常。
            for (Throwable error = getCause(); error != null; error = error.getCause()) {
                // HTTP 通信中断或线程中断可能留下迟到请求。
                if (error instanceof IOException || error instanceof InterruptedException) {
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
