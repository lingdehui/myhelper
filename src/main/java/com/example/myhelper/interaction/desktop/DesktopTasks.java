package com.example.myhelper.interaction.desktop;

import com.example.myhelper.interaction.desktop.DesktopWindow.Event;
import com.example.myhelper.interaction.desktop.DesktopWindow.Step;
import com.example.myhelper.interaction.desktop.DesktopWindow.TaskView;
import com.example.myhelper.interaction.desktop.behavior.DesktopBehavior.Display;
import com.example.myhelper.orchestration.AgentOrchestrator.Handle;
import com.example.myhelper.orchestration.AgentOrchestrator.Outcome;
import com.example.myhelper.records.RunArchivePort.ArchivedRun;
import com.example.myhelper.tasklog.TaskLogPort;
import com.example.myhelper.tasklog.TaskLogPort.Kind;
import com.example.myhelper.tasklog.TaskLogProjection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

/** 后台线程拥有的展示状态，以稳定任务 ID 聚合不同运行。 */
final class DesktopTasks {
    /** 保留最近已结束的任务；活跃运行始终保留。 */
    private static final int RECENT_TASKS = 24;
    /** 每次运行保留的实时事件数。 */
    private static final int LIVE_EVENTS = 160;
    /** 当前会话内可回看的近期运行数。 */
    private static final int RECENT_RUNS = 4;
    /** 收到任务结果后短暂显示固定完成标记。 */
    private static final long RESULT_MILLIS = 1_250;

    /** 插入顺序用于保持并发更新时的气泡位置。 */
    private final LinkedHashMap<String, Task> tasks = new LinkedHashMap<>();

    void accept(TaskLogPort.Event event) {
        var context = event.context();
        if (context == null || context.taskId() == null || context.rootRunId() == null) { return; }
        Task task = tasks.computeIfAbsent(context.taskId(), Task::new);
        Run run = task.run(context.rootRunId());
        if (task.current == null || event.kind() == Kind.RUN_STARTED) {
            task.current = run;
        }
        run.accept(event);
    }

    void attach(Handle handle, String input) {
        Task task = tasks.computeIfAbsent(handle.taskId(), Task::new);
        boolean known = task.runs.containsKey(handle.rootRunId());
        Run run = task.run(handle.rootRunId());
        if (task.current == null || (!known && task.current.ended)) {
            task.current = run;
        }
        if (run.handle == null || input != null) {
            run.handle = handle;
            run.ended = false;
        }
        run.refreshOutcome();
        if (input != null) { task.input = input; }
    }

    Run current(String taskId) {
        Task task = tasks.get(taskId);
        return task == null ? null : task.current;
    }

    List<TaskView> views(Instant now, boolean archiveConfigured) {
        int recent = 0;
        List<String> evict = new ArrayList<>();
        for (Task task : tasks.reversed().values()) {
            if (task.current != null && task.current.ended && ++recent > RECENT_TASKS) {
                evict.add(task.id);
            }
        }
        for (String id : evict) { tasks.remove(id); }
        List<TaskView> views = new ArrayList<>(tasks.size());
        for (Task task : tasks.values()) {
            if (task.current != null) { views.add(task.view(now, archiveConfigured)); }
        }
        return List.copyOf(views);
    }

    Iterable<String> ids() {
        return tasks.keySet();
    }

    Display behaviorDisplay(Instant now) {
        Display selected = Display.WORKING;
        boolean hasRunningTask = false;
        Run latestSettlement = null;
        for (Task task : tasks.values()) {
            Run run = task.current;
            if (run == null) { continue; }
            if (run.ended) {
                if (run.settledAt != null && (latestSettlement == null
                        || !run.settledAt.isBefore(latestSettlement.settledAt))) {
                    latestSettlement = run;
                }
                continue;
            }
            hasRunningTask = true;
            Display candidate = run.behaviorDisplay();
            if (displayPriority(candidate) > displayPriority(selected)) { selected = candidate; }
        }
        return !hasRunningTask && latestSettlement != null && latestSettlement.hasRecentSuccess(now)
                ? Display.RESULT : selected;
    }

    private static int displayPriority(Display display) {
        return switch (display) {
            case REVIEW -> 6;
            case VERIFY -> 5;
            case TOOL -> 4;
            case MODEL -> 3;
            case SETTLING -> 2;
            default -> 0;
        };
    }

    void markReplayGap() {
        for (Task task : tasks.values()) {
            for (Run run : task.runs.values()) {
                run.pendingModels.clear();
                if (run.latest != null && run.latest.kind() == Kind.MODEL_STARTED) { run.latest = null; }
            }
        }
    }

    /** 任务输入跨运行保留，图和时间线按各自的运行保存。 */
    private static final class Task {
        /** 稳定任务身份。 */
        private final String id;
        /** 最近的运行，键为根运行身份。 */
        private final LinkedHashMap<String, Run> runs = new LinkedHashMap<>();
        /** 当前运行，旧运行的迟到事件仍归入各自时间线。 */
        private Run current;
        /** 从当前桌面会话提交的原始输入。 */
        private String input;

        Task(String id) { this.id = id; }

        Run run(String root) {
            Run run = runs.computeIfAbsent(root, Run::new);
            if (runs.size() > RECENT_RUNS) {
                Iterator<Run> iterator = runs.values().iterator();
                while (iterator.hasNext()) {
                    Run old = iterator.next();
                    if (old != current && old != run) {
                        iterator.remove();
                        break;
                    }
                }
            }
            return run;
        }

        TaskView view(Instant now, boolean archiveConfigured) {
            List<Event> events = new ArrayList<>();
            for (Run run : runs.values()) {
                if (run != current) { run.appendTimeline(events, "先前运行 · "); }
            }
            current.appendTimeline(events, "");
            List<Step> steps = new ArrayList<>(current.nodes.size());
            Map<String, String> titles = new LinkedHashMap<>();
            for (var node : current.nodes) { titles.put(node.id(), node.title()); }
            String goal = input;
            for (var node : current.nodes) {
                if (goal == null && node.parentId() == null && node.isTask()) {
                    goal = node.title();
                }
                String status = node.statusLabel();
                List<String> dependencies = new ArrayList<>();
                for (String dependency : node.dependencies()) {
                    String title = titles.get(dependency);
                    if (title != null) { dependencies.add(title); }
                }
                if (!dependencies.isEmpty()) { status += " · 依赖 " + String.join("、", dependencies); }
                steps.add(new Step(node.id(), node.parentId(), node.title(), status));
            }
            if (goal == null) { goal = "任务 " + id.substring(0, Math.min(id.length(), 8)); }
            String outcome = current.reply;
            if (current.ended && current.nodes.isEmpty()) {
                String note = archiveConfigured && !current.archiveRequested
                        ? "打开详情可查询本次归档" : "部分执行图暂不可用";
                outcome = append(outcome, note);
            }
            if (current.archiveNote != null) { outcome = append(outcome, current.archiveNote); }
            if (current.cancelError != null) { outcome = append(outcome, current.cancelError); }
            return new TaskView(id, title(goal), input, current.phase(now), outcome, !current.ended,
                    current.handle != null && !current.handle.result().isDone() && !current.ended && !current.cancelling,
                    current.cancelling && !current.ended, List.copyOf(steps), List.copyOf(events));
        }
    }

    static final class Run {
        /** 图和事件使用的根运行身份。 */
        final String root;
        /** 仅进程内运行持有的句柄。 */
        Handle handle;
        /** 最后一次可用的展示快照。 */
        List<DesktopTaskGraph.Node> nodes = List.of();
        /** 实时缓冲区之外的视图时间线。 */
        private final ArrayDeque<TaskLogPort.Event> events = new ArrayDeque<>();
        /** 按调用 ID 区分仍在等待的并发模型请求。 */
        private final LinkedHashMap<String, TaskLogPort.Event> pendingModels = new LinkedHashMap<>();
        /** 已查询归档时保存的事实，顺序由记录层给出。 */
        private List<TaskLogPort.Event> archived = List.of();
        /** 最近的实时阶段。 */
        private TaskLogPort.Event latest;
        /** 已读到对外结果或结果异常。 */
        private boolean resultRead;
        /** 对外结果的状态标签。 */
        private String resultStatus;
        /** 确认实际收尾的时间，用于成功后的短暂表现。 */
        private Instant settledAt;
        /** 对话中展示的对外结果或问题。 */
        private String reply;
        /** 已收到实际收尾信号或已确认的运行结束事件。 */
        boolean ended;
        /** 已发起的取消请求。 */
        boolean cancelling;
        /** 无法发出取消请求的界面反馈。 */
        String cancelError;
        /** 是否已发起本次运行的归档读取。 */
        boolean archiveRequested;
        /** 归档中的证据缺口或读取失败。 */
        String archiveNote;

        Run(String root) { this.root = root; }

        void accept(TaskLogPort.Event event) {
            events.addLast(event);
            if (events.size() > LIVE_EVENTS) { events.removeFirst(); }
            latest = event;
            switch (event.kind()) {
                case MODEL_STARTED -> pendingModels.put(event.correlationId(), event);
                case MODEL_RETURNED, MODEL_FAILED -> pendingModels.remove(event.correlationId());
                case STOP_REQUESTED, RUN_INTERRUPTION_REQUESTED -> {
                    if (handle == null) { cancelling = true; }
                }
                case RUN_ENDED -> {
                    if (handle == null && !ended) {
                        ended = true;
                        settledAt = event.at();
                    }
                    if (resultStatus == null) {
                        String prefix = "运行收尾 status=";
                        if (event.summary().startsWith(prefix)) {
                            resultStatus = label(event.summary().substring(prefix.length()));
                        }
                    }
                }
                default -> { }
            }
        }

        void refreshOutcome() {
            if (handle == null) { return; }
            if (!resultRead && handle.result().isDone()) {
                try {
                    Outcome outcome = handle.result().join();
                    resultStatus = label(outcome.status().name());
                    reply = reply(outcome.value(), outcome.error());
                } catch (CompletionException | CancellationException failure) {
                    resultStatus = "执行异常";
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    reply = "结果读取失败：" + cause.getClass().getSimpleName();
                }
                resultRead = true;
            }
            if (!ended && handle.settled().isDone()) {
                ended = true;
                settledAt = Instant.now();
                if (handle.settled().isCompletedExceptionally()) {
                    reply = append(reply, "收尾异常，部分记录暂不可用");
                }
            }
        }

        void applyArchive(ArchivedRun archive) {
            archiveNote = null;
            if (archive.graph() != null) { nodes = DesktopTaskGraph.project(archive.graph()); }
            List<TaskLogPort.Event> history = new ArrayList<>();
            for (var event : archive.events()) { history.add(TaskLogProjection.from(event)); }
            archived = List.copyOf(history);
            if (!archive.graphComplete() || !archive.evidenceComplete()) {
                archiveNote = "归档包含执行图或证据缺口";
            }
            if (!resultRead && archive.endEvent().details().get("outcome") instanceof Map<?, ?> outcome) {
                resultStatus = label(Objects.toString(outcome.get("status"), ""));
                reply = reply(outcome.get("value"), Objects.toString(outcome.get("error"), null));
            }
        }

        String phase(Instant now) {
            if (ended) { return resultStatus == null ? "已收尾" : resultStatus; }
            if (cancelling || resultRead) { return "正在收尾"; }
            if (!pendingModels.isEmpty()) {
                var pending = pendingModels.lastEntry().getValue();
                long seconds = pending.at() == null ? 0 : Math.max(0, Duration.between(pending.at(), now).toSeconds());
                return "等待模型 · " + seconds + " 秒";
            }
            return latest == null ? "进行中" : summary(latest);
        }

        boolean hasRecentSuccess(Instant now) {
            return settledAt != null && "已完成".equals(resultStatus)
                    && (handle == null || !handle.settled().isCompletedExceptionally())
                    && now.isBefore(settledAt.plusMillis(RESULT_MILLIS));
        }

        Display behaviorDisplay() {
            if ("结果待核对".equals(resultStatus) || (latest != null && latest.kind() == Kind.RECORD_UNCONFIRMED)) {
                return Display.REVIEW;
            }
            if (cancelling || resultRead) { return Display.SETTLING; }
            if (latest != null) {
                switch (latest.kind()) {
                    case VERIFY_STARTED, VERIFIED -> { return Display.VERIFY; }
                    case TOOL_INTENT -> { return Display.TOOL; }
                    case PLAN_ASSIGNED, PLAN_CALL_PENDING, MODEL_STARTED -> { return Display.MODEL; }
                    default -> { }
                }
            }
            return pendingModels.isEmpty() ? Display.WORKING : Display.MODEL;
        }

        void appendTimeline(List<Event> target, String prefix) {
            Map<String, String> nodeIdsByRun = new HashMap<>();
            Set<String> nodeIds = new HashSet<>();
            for (var node : nodes) {
                nodeIds.add(node.id());
                if (node.isTask()) {
                    nodeIdsByRun.put(node.runId(), node.id());
                    if (node.parentId() == null) { nodeIdsByRun.putIfAbsent(root, node.id()); }
                }
            }
            Map<String, TaskLogPort.Event> facts = new HashMap<>();
            for (var event : archived) {
                if (event.correlationId() != null) { facts.put(event.correlationId(), event); }
            }
            ArrayDeque<TaskLogPort.Event> realtime = new ArrayDeque<>();
            for (var event : events) {
                var recorded = facts.get(event.correlationId());
                if (recorded == null || recorded.kind() != event.kind()) { realtime.addLast(event); }
            }
            for (var event : archived) {
                while (!realtime.isEmpty() && earlier(realtime.getFirst(), event)) {
                    target.add(display(realtime.removeFirst(), prefix, nodeIds, nodeIdsByRun));
                }
                target.add(display(event, prefix, nodeIds, nodeIdsByRun));
            }
            for (var event : realtime) {
                target.add(display(event, prefix, nodeIds, nodeIdsByRun));
            }
        }

        private boolean earlier(TaskLogPort.Event candidate, TaskLogPort.Event recorded) {
            return candidate.at() != null && recorded.at() != null && candidate.at().isBefore(recorded.at());
        }

        private Event display(TaskLogPort.Event event, String prefix, Set<String> nodeIds,
                              Map<String, String> nodeIdsByRun) {
            String nodeId = event.context().nodeId();
            if (!nodeIds.contains(nodeId)) { nodeId = nodeIdsByRun.getOrDefault(event.context().runId(), nodeId); }
            String source = switch (event.kind()) {
                case PLAN_ASSIGNED, PLAN_CALL_PENDING, PLAN_RETURNED, PLAN_APPLIED, PLAN_FAILED,
                        MODEL_STARTED, MODEL_RETURNED, MODEL_FAILED -> "模型处理";
                case VERIFY_STARTED, VERIFIED -> "验收";
                case TOOL_INTENT, TOOL_RETURNED -> "工具";
                default -> "运行";
            };
            return new Event(nodeId, event.at(), prefix + source, summary(event));
        }
    }

    private static String summary(TaskLogPort.Event event) {
        return switch (event.kind()) {
            case MODEL_STARTED -> "正在请求模型 · " + event.summary();
            case MODEL_RETURNED -> "模型已返回 · " + event.summary();
            case MODEL_FAILED -> "模型调用失败 · " + event.summary();
            default -> event.summary();
        };
    }

    private static String label(String status) {
        return switch (status) {
            case "SUCCESS" -> "已完成";
            case "FAILED" -> "失败";
            case "UNKNOWN" -> "结果待核对";
            case "WAITING" -> "等待接续";
            case "CANCELLED" -> "已停止";
            case "NEEDS_INPUT" -> "需要补充";
            default -> "已收尾";
        };
    }

    private static String reply(Object value, String error) {
        String text = value == null ? null : value.toString();
        return error == null ? text : append(text, error);
    }

    private static String append(String first, String second) {
        return first == null || first.isBlank() ? second : first + "\n" + second;
    }

    private static String title(String goal) {
        String line = goal.strip().replaceAll("\\s+", " ");
        int length = line.codePointCount(0, line.length());
        return length <= 48 ? line : line.substring(0, line.offsetByCodePoints(0, 48)) + "…";
    }
}
