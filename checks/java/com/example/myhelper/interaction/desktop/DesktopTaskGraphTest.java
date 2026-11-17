package com.example.myhelper.interaction.desktop;

import com.example.myhelper.orchestration.ExecutionTraceStore;
import com.example.myhelper.orchestration.ExecutionTraceStore.Edge;
import com.example.myhelper.orchestration.ExecutionTraceStore.EdgeKind;
import com.example.myhelper.orchestration.ExecutionTraceStore.NodeKind;
import com.example.myhelper.orchestration.ExecutionTraceStore.NodeStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopTaskGraphTest {
    @Test
    void projectsNestedTasksAndActiveDependenciesInCreationOrder() {
        ExecutionTraceStore traces = new ExecutionTraceStore(1, 10, 40);
        traces.open("root");
        traces.add("root", node("task", "run", null, NodeKind.TASK, "生成报告"));
        traces.add("root", node("read", "run", "task", NodeKind.STEP, "读取数据"));
        traces.add("root", node("report", "run", "task", NodeKind.STEP, "写报告"));
        traces.add("root", node("child", "child-run", "read", NodeKind.TASK, "查询\n数据"));
        traces.add("root", node("model", "child-run", null, NodeKind.MODEL, "查询数据"));
        traces.edge("root", new Edge("read", "report", EdgeKind.DEPENDS_ON, "SUCCESS"));
        traces.edge("root", new Edge("read", "report", EdgeKind.DATA_FROM, "result"));
        traces.edge("root", new Edge("child", "model", EdgeKind.SPAWNED, ""));

        List<DesktopTaskGraph.Node> steps = DesktopTaskGraph.project(traces.findSnapshot("root").orElseThrow());
        assertEquals(5, steps.size());
        assertEquals("task", steps.get(0).id());
        assertNull(steps.get(0).parentId());
        assertTrue(steps.get(0).isTask());
        assertEquals("read", steps.get(1).id());
        assertEquals("进行中", steps.get(1).statusLabel());
        DesktopTaskGraph.Node report = steps.get(2);
        assertEquals("report", report.id());
        assertEquals("task", report.parentId());
        assertEquals(List.of("read"), report.dependencies());
        assertEquals("等待依赖", report.statusLabel());
        DesktopTaskGraph.Node child = steps.get(3);
        assertEquals("read", child.parentId());
        assertEquals("child-run", child.runId());
        assertEquals("查询 数据", child.title());
        assertEquals("child", steps.get(4).parentId());
        assertEquals("模型 · 查询数据", steps.get(4).title());

        traces.finish("root", "read", NodeStatus.SUCCESS, "private result", null);
        traces.finish("root", "model", NodeStatus.COMPLETED, "private plan", null);
        traces.finish("root", "report", NodeStatus.FAILED, null, "private error");
        List<DesktopTaskGraph.Node> latest = DesktopTaskGraph.project(traces.findSnapshot("root").orElseThrow());
        assertEquals("等待依赖", report.statusLabel());
        assertEquals("成功", latest.get(1).statusLabel());
        assertEquals("失败", latest.get(2).statusLabel());
        assertEquals("已返回", latest.get(4).statusLabel());
    }

    @Test
    void collectedGraphRetainsLastProjectionAfterRemoval() {
        ExecutionTraceStore traces = new ExecutionTraceStore(1, 2, 4);
        DesktopRecentTraces recent = new DesktopRecentTraces(1);
        traces.open("root");
        String goal = "甲".repeat(79) + "🎉🌟";
        traces.add("root", node("task", "run", null, NodeKind.TASK, goal));
        traces.finish("root", "task", NodeStatus.NEEDS_INPUT, null, null);
        traces.close("root");
        var snapshot = traces.findSnapshot("root").orElseThrow();
        recent.afterClose("root", snapshot);
        traces.remove("root");

        assertTrue(traces.findSnapshot("root").isEmpty());
        DesktopTaskGraph.Node task = recent.find("root").orElseThrow().getFirst();
        assertEquals("甲".repeat(79) + "🎉…", task.title());
        assertEquals("需补充信息", task.statusLabel());

        traces.open("next");
        traces.add("next", node("task-2", "run-2", null, NodeKind.TASK, "第二个任务"));
        traces.finish("next", "task-2", NodeStatus.SUCCESS, "ok", null);
        traces.close("next");
        recent.afterClose("next", traces.findSnapshot("next").orElseThrow());
        traces.remove("next");
        assertTrue(recent.find("root").isEmpty());
        assertEquals("第二个任务", recent.find("next").orElseThrow().getFirst().title());
    }

    private static ExecutionTraceStore.Node node(String id, String run, String parent, NodeKind kind, String goal) {
        return new ExecutionTraceStore.Node(id, run, parent, kind, goal, 1, Map.of("secret", "hidden"),
                NodeStatus.RUNNING, null, null, Instant.now(), null);
    }
}
