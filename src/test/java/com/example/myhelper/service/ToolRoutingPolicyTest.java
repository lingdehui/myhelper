package com.example.myhelper.service;

import com.example.myhelper.config.MyHelperProperties;
import com.example.myhelper.memory.vector.category.ToolCategoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 无模型、无数据库的快速路由边界回归测试。 */
class ToolRoutingPolicyTest {

    private static final MyHelperProperties.ToolPlanner.Routing ROUTING =
            new MyHelperProperties.ToolPlanner.Routing(true, 3, 0.45, 12, 0.45, 2, 32);

    @Test
    void requiresAtLeastOneCategoryAboveTheConfiguredConfidenceFloor() {
        ToolCategoryService.CategoryCandidateRecall noMatch = recall(List.of());
        ToolCategoryService.CategoryCandidateRecall lowScore = recall(List.of(
                new ToolCategoryService.CategoryMatch("desktop", "桌面操作", "桌面控制", 0.44)));
        ToolCategoryService.CategoryCandidateRecall confident = recall(List.of(
                new ToolCategoryService.CategoryMatch("desktop", "桌面操作", "桌面控制", 0.45)));

        assertFalse(ToolRoutingPolicy.hasConfidentCategory(noMatch, ROUTING));
        assertFalse(ToolRoutingPolicy.hasConfidentCategory(lowScore, ROUTING));
        assertTrue(ToolRoutingPolicy.hasConfidentCategory(confident, ROUTING));
    }

    @Test
    void onlyACompleteBoundedCandidateSetCanSkipLegacyBrowsing() {
        assertFalse(ToolRoutingPolicy.hasBoundedCandidateSet(1, ROUTING));
        assertTrue(ToolRoutingPolicy.hasBoundedCandidateSet(2, ROUTING));
        assertTrue(ToolRoutingPolicy.hasBoundedCandidateSet(32, ROUTING));
        assertFalse(ToolRoutingPolicy.hasBoundedCandidateSet(33, ROUTING));
    }

    private static ToolCategoryService.CategoryCandidateRecall recall(
            List<ToolCategoryService.CategoryMatch> matches) {
        return new ToolCategoryService.CategoryCandidateRecall(matches, List.of(), List.of());
    }
}
