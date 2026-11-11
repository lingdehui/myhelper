package com.example.myhelper.persistence;

import com.example.myhelper.world.model.WorldEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 基于写前快照的同步补偿；prepare 只读取，写入异常也保留恢复入口。 */
public abstract class SnapshotParticipant implements DualWriteStore.Participant {
    /** 为一个明确指定的数据库批次创建独立的操作状态。 */
    @Override
    public final DualWriteStore.Change open(String operationId, List<WorldEntity> entities) {
        // 固定本次实体列表，避免调用方增删列表影响已打开的操作。
        List<WorldEntity> batch = List.copyOf(entities);
        // 每次 open 创建新的句柄，快照不会被下一次操作复用。
        return new DualWriteStore.Change() {
            /** 全部写前快照；完整取得后才允许提交。 */
            private Map<String, Optional<WorldEntity>> before;
            /** 在远端写入前置位，覆盖服务端成功但响应丢失的情况。 */
            private boolean attempted;

            /** 在当前数据库读取本次涉及实体的旧内容。 */
            @Override
            public void prepare() {
                // 使用临时快照，避免部分读取成功就发布不完整恢复资料。
                Map<String, Optional<WorldEntity>> snapshot = new LinkedHashMap<>();
                // 只读取明确提交的实体自身，不展开引用。
                for (WorldEntity entity : batch) {
                    // Optional.empty 表示该编号在写入前没有实体内容。
                    snapshot.put(entity.id(), read(entity.id()));
                }
                // 所有读取完成后一次性发布快照。
                before = snapshot;
            }

            /** 执行该库的实际写入。 */
            @Override
            public void commit() {
                // 没有完整快照时不能开始写入。
                if (before == null) {
                    // 拒绝错误的调用顺序。
                    throw new IllegalStateException("必须先 prepare");
                }
                // 必须在远端调用前置位，不能只在收到成功响应后置位。
                attempted = true;
                // 子类负责 Neo4j 或 Qdrant 的实际请求。
                save(batch);
            }

            /** 恢复所有写前状态，一条失败不阻止恢复其余条目。 */
            @Override
            public void rollback() {
                // 尚未尝试写入的分支无需恢复。
                if (!attempted) {
                    // prepare 失败也可安全调用此方法。
                    return;
                }
                // 汇总错误，避免只报告最后一个恢复失败。
                RuntimeException failure = null;
                // 按快照依次恢复每个实体。
                for (var entry : before.entrySet()) {
                    // 每条快照独立尝试恢复。
                    try {
                        // 原先存在的实体须恢复旧内容，不能直接删除。
                        if (entry.getValue().isPresent()) {
                            // 通过同一适配器完整恢复旧实体及引用。
                            save(List.of(entry.getValue().get()));
                        // 原先不存在的实体只撤销本次新增内容。
                        } else {
                            // 子类删除本模块保存的实体内容。
                            remove(entry.getKey());
                        }
                    // 记录错误后继续恢复其他条目。
                    } catch (RuntimeException error) {
                        // 首次出错时创建汇总异常。
                        if (failure == null) {
                            // 明确表示恢复尚未完成。
                            failure = new IllegalStateException("快照恢复未完成");
                        }
                        // 保留每条失败的具体原因。
                        failure.addSuppressed(error);
                    }
                }
                // 部分恢复失败不能将句柄标成已恢复。
                if (failure != null) {
                    // 将全部恢复错误交给协调器。
                    throw failure;
                }
                // 成功恢复后，重复 rollback 不再执行写入。
                attempted = false;
            }
        };
    }

    /** 实际写入允许部分成功，失败后由快照补偿；不解释引用业务含义。 */
    protected abstract void save(List<WorldEntity> entities);

    /** 幂等移除本模块保存的指定实体内容，供新增实体补偿使用。 */
    protected abstract void remove(String id);
}
