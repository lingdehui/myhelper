package com.example.myhelper.novel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 章节计划必须在模型漏字段时仍然能提供可用的写作约束。 */
class NovelChapterPlanTest {

    @Test
    void appliesSafeDefaultsAndRemovesDuplicateCast() {
        NovelChapterPlan plan = new NovelChapterPlan(12, "", "", "", "", "",
                List.of("主角", "主角", "", "配角"), List.of("伏笔A", "伏笔A"), "");

        assertEquals("未命名章节", plan.title());
        assertEquals(List.of("主角", "配角"), plan.cast());
        assertEquals(List.of("伏笔A"), plan.plotThreads());
        assertFalse(plan.asPrompt().contains("null"));
        assertTrue(plan.asPrompt().contains("第12章"));
    }
}
