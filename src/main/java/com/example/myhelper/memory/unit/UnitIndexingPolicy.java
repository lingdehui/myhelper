package com.example.myhelper.memory.unit;

/** Unit 是否可以作为语义检索入口的唯一状态判定。 */
final class UnitIndexingPolicy {

    private UnitIndexingPolicy() {}

    /** 只有 ACTIVE Unit 可以写入或保留在 Qdrant 检索索引中。 */
    static boolean isSearchable(String status) {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
