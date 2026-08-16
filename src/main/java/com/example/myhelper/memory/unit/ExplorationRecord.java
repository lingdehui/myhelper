package com.example.myhelper.memory.unit;

/**
 * 探索模式操作记录（文档 15 v1.7 §3.1）。
 *
 * <p>探索模式单独选中一个 Unit 时，必须显式声明 {@link DeclareType}。
 * 每次声明执行后追加一条记录，仅作追溯用途，不单独触发删除/禁用。</p>
 */
public record ExplorationRecord(
        String recordId,
        DeclareType declareType,
        int validateCount,
        int optimizeCount,
        String lastValidateResult,
        String lastOptimizeResult,
        long timestamp
) {
    /** 探索声明类型 */
    public enum DeclareType {
        /** 验证：确认该 Unit 当前是否仍有效 */
        VALIDATE,
        /** 优化：尝试改进该 Unit */
        OPTIMIZE
    }
}
