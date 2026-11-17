package com.example.myhelper.interaction.desktop;

import com.example.myhelper.orchestration.ExecutionTraceStore;
import com.example.myhelper.orchestration.ExecutionTraceStore.NodeKind;
import com.example.myhelper.orchestration.ExecutionTraceStore.NodeStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** 把实时或归档执行图投影为桌面任务树所需的只读字段。 */
final class DesktopTaskGraph {
    /** 清除标题中的控制字符和换行。 */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
    /** 图的目标正文只在任务树中显示短标题。 */
    private static final int TITLE_LIMIT = 80;

    private DesktopTaskGraph() { }

    static List<Node> project(ExecutionTraceStore.Snapshot snapshot) {
        if (snapshot == null || snapshot.nodes().isEmpty()) {
            return List.of();
        }
        Map<String, String> parentsFromEdges = new HashMap<>();
        Map<String, Set<String>> predecessors = new HashMap<>();
        for (var edge : snapshot.edges()) {
            if (edge.from().equals(edge.to()) || !snapshot.nodes().containsKey(edge.from())
                    || !snapshot.nodes().containsKey(edge.to())) {
                continue;
            }
            switch (edge.kind()) {
                case SPAWNED -> parentsFromEdges.putIfAbsent(edge.to(), edge.from());
                case DEPENDS_ON -> {
                    Set<String> dependencyIds = predecessors.get(edge.to());
                    if (dependencyIds == null) {
                        dependencyIds = new LinkedHashSet<>();
                        predecessors.put(edge.to(), dependencyIds);
                    }
                    dependencyIds.add(edge.from());
                }
                case DATA_FROM -> { }
            }
        }
        // 快照节点 Map 没有遍历顺序。图内首次出现的序号保持计划和子任务的创建顺序。
        Set<String> nodeIds = new LinkedHashSet<>();
        for (var event : snapshot.events()) {
            if (snapshot.nodes().containsKey(event.nodeId())) {
                nodeIds.add(event.nodeId());
            }
        }
        if (nodeIds.size() < snapshot.nodes().size()) {
            nodeIds.addAll(new TreeSet<>(snapshot.nodes().keySet()));
        }

        List<Node> result = new ArrayList<>(nodeIds.size());
        for (String id : nodeIds) {
            var node = snapshot.nodes().get(id);
            String parent = node.parentId();
            if (parent == null || id.equals(parent) || !snapshot.nodes().containsKey(parent)) {
                parent = parentsFromEdges.get(id);
            }
            List<String> dependencies = List.copyOf(predecessors.getOrDefault(id, Set.of()));
            result.add(new Node(id, node.runId(), parent, node.kind(), title(node), node.status(),
                    dependencies, statusLabel(node.status(), dependencies, snapshot.nodes())));
        }
        return List.copyOf(result);
    }

    private static String title(ExecutionTraceStore.Node node) {
        String kind = switch (node.kind()) {
            case TASK -> "任务";
            case STEP -> "步骤";
            case MODEL -> "模型";
            case MODEL_SESSION -> "模型会话";
            case RECORD_QUERY -> "查询记录";
            case RECOVERY_REVIEW -> "恢复审核";
            case SEARCH -> "检索能力";
            case UNIT -> "执行能力";
            case TOOL -> "工具";
            case GATE -> "门禁";
            case VERIFY -> "验收";
        };
        if (node.goal() == null || node.goal().isBlank()) {
            return kind;
        }
        String goal = CONTROL.matcher(node.goal()).replaceAll(" ").strip();
        if (goal.codePointCount(0, goal.length()) > TITLE_LIMIT) {
            goal = goal.substring(0, goal.offsetByCodePoints(0, TITLE_LIMIT)) + "…";
        }
        if (goal.isEmpty()) {
            return kind;
        }
        return node.kind() == NodeKind.TASK || node.kind() == NodeKind.STEP ? goal : kind + " · " + goal;
    }

    private static String statusLabel(NodeStatus status, List<String> dependencies,
                                      Map<String, ExecutionTraceStore.Node> nodes) {
        return switch (status) {
            case RUNNING -> waitingForPredecessor(dependencies, nodes) ? "等待依赖" : "进行中";
            case COMPLETED -> "已返回";
            case SUCCESS -> "成功";
            case FAILED -> "失败";
            case UNKNOWN -> "待核对";
            case WAITING -> "等待接续";
            case CANCELLED -> "取消";
            case NEEDS_INPUT -> "需补充信息";
        };
    }

    private static boolean waitingForPredecessor(List<String> dependencies,
                                                  Map<String, ExecutionTraceStore.Node> nodes) {
        for (String id : dependencies) {
            if (nodes.get(id).status() == NodeStatus.RUNNING) {
                return true;
            }
        }
        return false;
    }

    record Node(
            /** 执行节点身份。 */
            String id,
            /** 所属递归任务身份。 */
            String runId,
            /** 任务树中的父节点；根节点为空。 */
            String parentId,
            /** 节点类别。 */
            NodeKind kind,
            /** 供界面显示的简短目标及类别。 */
            String title,
            /** 图中节点的原始状态。 */
            NodeStatus status,
            /** 此节点依赖的前驱执行节点身份。 */
            List<String> dependencies,
            /** 根据已知节点和依赖状态生成的展示文案。 */
            String statusLabel) {
        Node {
            dependencies = List.copyOf(dependencies);
        }

        boolean isTask() {
            return kind == NodeKind.TASK;
        }
    }
}
