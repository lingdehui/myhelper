package com.example.myhelper.memory.unit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防止归档或禁用经验重新进入语义索引的回归测试。 */
class UnitIndexingPolicyTest {

    @Test
    void onlyActiveUnitsCanBeSemanticRetrievalEntries() {
        assertTrue(UnitIndexingPolicy.isSearchable("ACTIVE"));
        assertTrue(UnitIndexingPolicy.isSearchable("active"));
        assertFalse(UnitIndexingPolicy.isSearchable("ARCHIVED"));
        assertFalse(UnitIndexingPolicy.isSearchable("DISABLED"));
        assertFalse(UnitIndexingPolicy.isSearchable(null));
    }
}
