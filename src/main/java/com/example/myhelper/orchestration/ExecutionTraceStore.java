package com.example.myhelper.orchestration;

import com.example.myhelper.records.ExecutionTracePort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 有界内存执行图；所有更新串行化，消费者只取得不可变快照。尚不支持跨重启恢复。 */
public final class ExecutionTraceStore implements ExecutionTracePort {
    /** 节点创建事件的稳定名称，供事件消费者读取。 */
    private static final String NODE_CREATED_EVENT = "CREATED";

    /** 全局图数量限制，结束任务由调用方显式清理。 */
    private final int maxRuns;
    /** 每图节点上限。 */
    private final int maxNodes;
    /** 每图事件及关系的上限，防止持续轮询导致无限内存增长。 */
    private final int maxEvents;
    /** 根运行对应执行图，图内 Map 不暴露给消费者。 */
    private final Map<String, Graph> runs = new LinkedHashMap<>();

    // 设置内存图的容量保护，归档和清理由调用方负责。
    public ExecutionTraceStore(int maxRuns, int maxNodes, int maxEvents) {
        // 容量必须为正，事件容量至少容纳每个节点的创建记录。
        if (maxRuns < 1 || maxNodes < 1 || maxEvents < maxNodes) {
            // 抛出参数或协议错误，阻止无效输入继续进入执行。
            throw new IllegalArgumentException("无效链路容量");
        }
        // 保存最多同时留在内存中的运行图数量。
        this.maxRuns = maxRuns;
        // 保存每张图最多允许的执行节点数量。
        this.maxNodes = maxNodes;
        // 保存每张图的事件和关系数量保护上限。
        this.maxEvents = maxEvents;
    }

    // 原子创建根图；synchronized 避免多个线程同时修改集合。
    public synchronized void open(String root) {
        // 同一根身份不能重复创建图，防止覆盖运行记录。
        if (runs.containsKey(root)) {
            // 抛出参数或协议错误，阻止无效输入继续进入执行。
            throw new IllegalArgumentException("重复根运行");
        }
        // 容量已满则拒绝新根图，不自动删除仍有消费者需要的记录。
        if (runs.size() >= maxRuns) {
            // 明确报告当前状态不允许继续；不在这里自动重试。
            throw new IllegalStateException("执行图容量已满，请归档结束任务");
        }
        // 建立该根运行的空节点表、关系表和事件表。
        runs.put(root, new Graph());
    }

    // 登记新的执行节点，并追加创建事件。
    public synchronized void add(String root, Node node) {
        // 先找到已存在的根图，未知根身份明确报错。
        Graph graph = graph(root);
        // 节点 ID 不能重复，重复执行应建立新身份。
        if (graph.nodes.containsKey(node.id())) {
            // 抛出参数或协议错误，阻止无效输入继续进入执行。
            throw new IllegalArgumentException("重复执行节点");
        }
        // 达到节点预算时拒绝继续扩图。
        if (graph.nodes.size() >= maxNodes) {
            // 明确报告当前状态不允许继续；不在这里自动重试。
            throw new IllegalStateException("执行节点达到上限");
        }
        // 追加创建通知；事件名称来自命名常量。
        event(graph, node.id(), NODE_CREATED_EVENT);
        // 保存本次不可变节点快照。
        graph.nodes.put(node.id(), node);
    }

    // 登记节点关系；它不会调度动作或解释自然语言。
    public synchronized void edge(String root, Edge edge) {
        // 先找到已存在的根图，未知根身份明确报错。
        Graph graph = graph(root);
        // 边的两端都必须先有节点，避免出现悬空引用。
        if (!graph.nodes.containsKey(edge.from()) || !graph.nodes.containsKey(edge.to())) {
            // 抛出参数或协议错误，阻止无效输入继续进入执行。
            throw new IllegalArgumentException("关系端点不存在");
        }
        // 关系数量也有上限，防止图无界增长。
        if (graph.edges.size() >= maxEvents) {
            // 明确报告当前状态不允许继续；不在这里自动重试。
            throw new IllegalStateException("关系数达到上限");
        }
        // 事件仍用枚举名作为文本协议，消费者可按名称识别关系。
        event(graph, edge.to(), edge.kind().name());
        // 保存真实关系，不从完成时间推测依赖。
        graph.edges.add(edge);
    }

    // 结束一个节点，保留原身份和输入，只更新结果与结束时间。
    public synchronized void finish(String root, String id, NodeStatus status, Object output, String error) {
        // 先找到已存在的根图，未知根身份明确报错。
        Graph graph = graph(root);
        // 读取原节点，后续构建新快照而不修改旧快照。
        Node before = Objects.requireNonNull(graph.nodes.get(id), "节点不存在");
        // 记录状态变更事件；NodeStatus 防止任意状态拼写进入主接口。
        event(graph, id, status.name());
        // 用新快照替换当前节点，之前已读出的快照不受影响。
        graph.nodes.put(id, new Node(before.id(), before.runId(), before.parentId(), before.kind(), before.goal(),
                // 保留计划版本、输入和创建时间；保存新结果、状态和结束时间。
                before.planRevision(), before.input(), status, TaskPlan.immutable(output), error, before.startedAt(), Instant.now()));
    }

    // 在同一锁内复制图，避免消费者看到一半更新的节点与关系。
    public synchronized Snapshot snapshot(String root) {
        // 先找到已存在的根图，未知根身份明确报错。
        return copySnapshot(graph(root));
    }

    /** 展示端可按需读图；归档回收与读取用同一把锁，回收后返回空。 */
    public synchronized Optional<Snapshot> findSnapshot(String root) {
        Graph graph = runs.get(root);
        return graph == null ? Optional.empty() : Optional.of(copySnapshot(graph));
    }

    private static Snapshot copySnapshot(Graph graph) {
        // 仅返回只读集合；节点内部 JSON 也已深复制。
        return new Snapshot(Map.copyOf(graph.nodes), List.copyOf(graph.edges), List.copyOf(graph.events), graph.closed);
    }

    // 由编排器确认真实在途工作全部结束后关闭图。
    public synchronized void close(String root) {
        // 标记可被归档清理；取消返回并不自动设置此标志。
        graph(root).closed = true;
    }

    /** 调用方确认已消费/归档后释放；不允许删除活跃图。 */
    public synchronized void remove(String root) {
        // 未结束的图仍可能收到迟到结果，因此禁止删除。
        if (!graph(root).closed) {
            // 明确报告当前状态不允许继续；不在这里自动重试。
            throw new IllegalStateException("不能删除活跃执行图");
        }
        // 调用方确认消费/归档后释放内存，不代表已经写入数据库。
        runs.remove(root);
    }

    // 统一按根身份查图，避免静默创建错误运行。
    private Graph graph(String root) {
        // 未知根身份立即报错，不返回空图掩盖调用问题。
        return Objects.requireNonNull(runs.get(root), "未知根运行");
    }

    // 在图锁保护下追加轻量事件；当前事件不包含完整重放数据。
    private void event(Graph graph, String node, String type) {
        // 事件达到容量上限时明确拒绝追加。
        if (graph.events.size() >= maxEvents) {
            // 明确报告当前状态不允许继续；不在这里自动重试。
            throw new IllegalStateException("事件数达到上限");
        }
        // 序号按当前事件数量从 1 递增，同时记录发生时间。
        graph.events.add(new Event(graph.events.size() + 1L, node, type, Instant.now()));
    }


    /** 执行图节点类型；枚举只描述记录对象，不负责业务规划。 */
    public enum NodeKind {
        /** 一个根任务或递归子任务。 */
        TASK,
        /** 一次模型调用。 */
        MODEL,
        /** 模型会话生命周期异常，不计为实际模型请求。 */
        MODEL_SESSION,
        /** 从独立记录模块查询本轮进度与待处理事项。 */
        RECORD_QUERY,
        /** 模型提出恢复结论，由外部模块审核及更新状态。 */
        RECOVERY_REVIEW,
        /** 一次固化候选检索。 */
        SEARCH,
        /** 计划中的逻辑步骤。 */
        STEP,
        /** 一次固化能力执行。 */
        UNIT,
        /** 一次实际工具调用。 */
        TOOL,
        /** 执行前的资格检查。 */
        GATE,
        /** 执行后的结果验收。 */
        VERIFY
    }

    /** 图的关系类型，避免用任意字符串拼写依赖语义。 */
    public enum EdgeKind {
        /** 父节点创建了子节点。 */
        SPAWNED,
        /** 后继必须等待前驱成功。 */
        DEPENDS_ON,
        /** 后继参数读取前驱结果。 */
        DATA_FROM
    }

    /** 链路状态；COMPLETED 表示记录动作正常返回，SUCCESS 才表示业务结果成功。 */
    public enum NodeStatus {
        /** 已加入运行，当前也包含排队和等待依赖阶段。 */
        RUNNING,
        /** 模型/检索等动作正常返回；不代表整个任务成功。 */
        COMPLETED,
        /** 执行业务结果成功。 */
        SUCCESS,
        /** 明确失败。 */
        FAILED,
        /** 效果未知，需要核对。 */
        UNKNOWN,
        /** 已记录待办，等待模型、外部审核或其他接续条件。 */
        WAITING,
        /** 已请求取消。 */
        CANCELLED,
        /** 需要用户补充信息。 */
        NEEDS_INPUT
    }

    public record Node(
            /** 本次实际调用身份。 */
            String id,
            /** 所属任务身份。 */
            String runId,
            /** 创建者，仅代表父子归属。 */
            String parentId,
            /** 任务、模型、检索、步骤、固化、工具、门禁或验收节点。 */
            NodeKind kind,
            /** 执行目标/能力信息。 */
            String goal,
            /** 所属规划修订。 */
            long planRevision,
            /** 不可变输入，包括能力引用或参数来源。 */
            Map<String, Object> input,
            /** 当前状态。 */
            NodeStatus status,
            /** 不可变输出。 */
            Object output,
            /** 错误原因。 */
            String error,
            /** 开始时间。 */
            Instant startedAt,
            /** 结束时间，运行时为空。 */
            Instant endedAt) {
        // 节点输入输出必须是不可变 JSON，避免只读快照内仍藏可变集合。
        public Node {
            // 深复制输入参数及其嵌套对象。
            input = TaskPlan.immutableMap(input);
            // 深复制输出；空输出允许为 null。
            output = TaskPlan.immutable(output);
        }
    }

    public record Edge(
            /** 起点。 */
            String from,
            /** 终点。 */
            String to,
            /** SPAWNED、DEPENDS_ON、DATA_FROM。 */
            EdgeKind kind,
            /** 参数路径或关联说明。 */
            String detail) { }

    public record Event(
            /** 根图内递增序号。 */
            long sequence,
            /** 关联节点。 */
            String nodeId,
            /** 创建、关联或状态变更。 */
            String type,
            /** 事件发生时间。 */
            Instant at) { }
    public record Snapshot(
            /** 不可变节点 Map。 */
            Map<String, Node> nodes,
            /** 不可变执行关系。 */
            List<Edge> edges,
            /** 按序事件。 */
            List<Event> events,
            /** 是否已无在途工作。 */
            boolean closed) { }

    // 图的可变容器仅由本类持有，不直接暴露给外部模块。
    private static final class Graph {
        /** 节点主索引。 */
        private final Map<String, Node> nodes = new LinkedHashMap<>();
        /** 真实关系集合，不从时间顺序猜依赖。 */
        private final List<Edge> edges = new ArrayList<>();
        /** 追加事件，序号用于消费者定位。 */
        private final List<Event> events = new ArrayList<>();
        /** 根任务及在途调用是否已完成。 */
        private boolean closed;
    }
}
