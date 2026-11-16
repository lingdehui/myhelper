package com.example.myhelper.orchestration;

import com.example.myhelper.llm.LlmTexts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证分叉归属与实际依赖一致，不能把数据边猜成父分支汇合。 */
class PlanBranchesTest {
    @Test
    void linearPlanAndRedundantDependencyKeepExistingBranch() {
        TaskPlan plan = new TaskPlan("线性", List.of(step("a"), step("b", "a"), step("c", "a", "b")));
        PlanBranches.Layout layout = PlanBranches.analyze(plan);
        assertTrue(layout.forks().isEmpty());
        assertEquals(PlanBranches.ROOT_BRANCH, layout.branch("a"));
        assertEquals(PlanBranches.ROOT_BRANCH, layout.branch("c"));
        assertNull(layout.forkForBranch(PlanBranches.ROOT_BRANCH));
    }

    @Test
    void nestedForksReturnToTheirOwnParentsAtLocalJoins() {
        TaskPlan plan = new TaskPlan("嵌套分叉", List.of(step("p"), step("b", "p"), step("c", "p"),
                step("d", "b"), step("e", "b"), step("bj", "d", "e"), step("aj", "bj", "c"), step("tail", "aj")));
        PlanBranches.Layout layout = PlanBranches.analyze(plan);
        assertEquals(2, layout.forks().size());
        PlanBranches.Fork outer = layout.forks().get(0);
        PlanBranches.Fork inner = layout.forks().get(1);
        assertEquals(PlanBranches.ROOT_BRANCH, outer.parentBranchId());
        assertEquals(layout.branch("b"), inner.parentBranchId());
        assertEquals(layout.branch("b"), layout.branch("bj"));
        assertEquals(PlanBranches.ROOT_BRANCH, layout.branch("aj"));
        assertEquals(PlanBranches.ROOT_BRANCH, layout.branch("tail"));
        assertNotEquals(layout.branch("d"), layout.branch("e"));
        assertEquals(List.of(inner), layout.closingAt("bj"));
        assertEquals(List.of(outer), layout.closingAt("aj"));
    }

    @Test
    void separateForksOfSameParentHaveDifferentIdentity() {
        TaskPlan plan = new TaskPlan("连续分叉", List.of(step("b"), step("c"), step("j", "b", "c"),
                step("d", "j"), step("e", "j"), step("k", "d", "e")));
        PlanBranches.Layout layout = PlanBranches.analyze(plan);
        assertEquals(2, layout.forks().size());
        assertNotEquals(layout.forks().get(0).id(), layout.forks().get(1).id());
        assertEquals(PlanBranches.ROOT_BRANCH, layout.forks().get(1).parentBranchId());
    }

    @Test
    void parallelRootsWithoutJoinCloseAtPlanEnd() {
        PlanBranches.Layout layout = PlanBranches.analyze(new TaskPlan("并行", List.of(step("b"), step("c"))));
        assertEquals(1, layout.forks().size());
        assertNull(layout.forks().getFirst().joinStepId());
    }

    @Test
    void crossedDataEdgesRequireExplicitControlOwnership() {
        List<TaskPlan.Step> steps = List.of(step("b0"), step("c0"), step("b1", "b0"),
                step("c1", "c0", "b0"), step("j", "b1", "c1"));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                PlanBranches.analyze(new TaskPlan("交叉依赖", steps));
            }
        });
        assertEquals(LlmTexts.AMBIGUOUS_ROUTING, missing.getMessage());
        PlanBranches.Routing routing = new PlanBranches.Routing(
                Map.of("b0", "b", "b1", "b", "c0", "c", "c1", "c", "j", PlanBranches.ROOT_BRANCH),
                List.of(new PlanBranches.Fork("f", PlanBranches.ROOT_BRANCH, List.of("b", "c"), "j")));
        PlanBranches.Layout layout = PlanBranches.analyze(new TaskPlan("交叉依赖", steps, routing));
        assertEquals("c", layout.branch("c1"));
        assertTrue(layout.closingAt("c1").isEmpty());
        assertEquals("f", layout.closingAt("j").getFirst().id());
    }

    @Test
    void childForkCannotOutliveParentsExplicitJoin() {
        List<TaskPlan.Step> steps = List.of(step("d"), step("e"), step("c"), step("j", "d", "e", "c"));
        PlanBranches.Routing routing = new PlanBranches.Routing(
                Map.of("d", "d", "e", "e", "c", "c", "j", PlanBranches.ROOT_BRANCH),
                List.of(new PlanBranches.Fork("outer", PlanBranches.ROOT_BRANCH, List.of("b", "c"), "j"),
                        new PlanBranches.Fork("inner", "b", List.of("d", "e"), null)));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                PlanBranches.analyze(new TaskPlan("过早释放", steps, routing));
            }
        });
        assertEquals(LlmTexts.INVALID_ROUTING_JOIN, failure.getMessage());
    }

    @Test
    void explicitJoinMustWaitForEveryChildStep() {
        PlanBranches.Routing routing = new PlanBranches.Routing(
                Map.of("b", "b", "c", "c", "j", PlanBranches.ROOT_BRANCH),
                List.of(new PlanBranches.Fork("f", PlanBranches.ROOT_BRANCH, List.of("b", "c"), "j")));
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                PlanBranches.analyze(new TaskPlan("缺失汇合依赖", List.of(step("b"), step("c"), step("j", "b")), routing));
            }
        });
    }

    @Test
    void reservedAnalysisNamesAreRejectedBeforeScheduling() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                PlanBranches.analyze(new TaskPlan("保留编号", List.of(step("$routing-entry"))));
            }
        });
    }

    @Test
    void existingJsonPlanStillDeserializesAndRoutingSchemaIsValid() throws Exception {
        ObjectMapper json = new ObjectMapper();
        TaskPlan plan = json.readValue("""
                {"goal":"线性","steps":[{"id":"a","goal":"a","kind":"TASK","arguments":{},"dependsOn":[],"bindings":{}}]}
                """, TaskPlan.class);
        assertNull(plan.routing());
        assertTrue(PlanBranches.analyze(plan).forks().isEmpty());
        assertTrue(json.readTree(LlmTexts.SUBMIT_PLAN_SCHEMA).path("properties").has("routing"));
    }

    private static TaskPlan.Step step(String id, String... dependencies) {
        return new TaskPlan.Step(id, id, TaskPlan.Kind.TASK, null, Map.of(), List.of(dependencies), Map.of());
    }
}
