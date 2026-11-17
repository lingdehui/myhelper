package com.example.myhelper.interaction.desktop;

import com.example.myhelper.application.TaskActivity;
import com.example.myhelper.application.TaskActivity.Occupation;
import com.example.myhelper.interaction.TaskEntry;
import com.example.myhelper.interaction.desktop.DesktopTasks.Run;
import com.example.myhelper.interaction.desktop.DesktopWindow.SubmissionView;
import com.example.myhelper.interaction.desktop.DesktopWindow.TaskView;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlanner;
import com.example.myhelper.interaction.desktop.behavior.DesktopBehavior;
import com.example.myhelper.interaction.desktop.behavior.DesktopBehavior.Display;
import com.example.myhelper.orchestration.AgentOrchestrator.Handle;
import com.example.myhelper.orchestration.ExecutionTraceStore;
import com.example.myhelper.records.RunArchivePort;
import com.example.myhelper.records.RunArchivePort.ArchivedRun;
import com.example.myhelper.records.RunArchivePort.ArchiveRef;
import com.example.myhelper.tasklog.TaskLogBuffer;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;

/** 将异步应用运行时装配到 Swing。窗口只消费不可变展示数据。 */
public final class DesktopSession implements AutoCloseable {
    /** 观测异常记录在应用日志，定时刷新在下次计时继续。 */
    private static final Logger LOG = LoggerFactory.getLogger(DesktopSession.class);
    /** 后台观测和窗口重绘的周期。 */
    private static final int REFRESH_MILLIS = 250;
    /** 提交队列和已显示回执的会话内上限。 */
    private static final int MAX_SUBMISSIONS = 16;
    /** 桌面行为配置只在桌面模式中读取。 */
    private static final String BEHAVIOR_PREFIX = "myhelper.desktop.behavior.";

    /** 三种线程分别负责观测、任务命令和可能阻塞的归档查询。 */
    private final ScheduledExecutorService observer = Executors.newSingleThreadScheduledExecutor(
            new DesktopThreadFactory("observer"));
    /** 提交和取消均可能进入权威记录端口。 */
    private final ExecutorService commands = Executors.newSingleThreadExecutor(new DesktopThreadFactory("command"));
    /** 归档只在点开已结束的任务时读。 */
    private final ExecutorService history = Executors.newSingleThreadExecutor(new DesktopThreadFactory("history"));
    /** 主线程在窗口退出后继续关闭 Spring 容器。 */
    private final CountDownLatch done = new CountDownLatch(1);
    /** 异步任务入口。 */
    private final TaskEntry entry;
    /** 编排器维护的实时执行图。 */
    private final ExecutionTraceStore traces;
    /** 应用启动时即就绪的实时日志缓冲。 */
    private final TaskLogBuffer log;
    /** 短任务在首次轮询前结束时也可以取得最终只读任务树。 */
    private final DesktopRecentTraces recentTraces;
    /** 可选权威归档。 */
    private final RunArchivePort archives;
    /** 根运行、启动扫描与桌面受理队列共用的活动计数。 */
    private final TaskActivity activity;
    /** 空闲时使用的独立模型请求。 */
    private final BehaviorPlanner planner;
    /** 是否启用空闲模型。 */
    private final boolean idlePlanning;
    /** 空闲触发及后续轮次的间隔。 */
    private final Duration idleDelay;
    /** 用户点击发送到编排受理完成期间保持占用。 */
    private final Set<Occupation> pendingSubmissions = ConcurrentHashMap.newKeySet();
    /** 仅在观测线程访问的任务聚合。 */
    private final DesktopTasks tasks = new DesktopTasks();
    /** 仅在观测线程更新的提交回执。 */
    private final Map<Long, SubmissionView> submissions = new LinkedHashMap<>();
    /** 模型和工具事件的回放位点。 */
    private long cursor;
    /** 记录实时端报告过的缺口。 */
    private boolean missedEvents;
    /** 上次观察到的上游投递丢弃数。 */
    private long droppedCount;
    /** 单调提交编号用于关联并发回执。 */
    private long nextSubmission;
    /** 停机时拒绝新桌面命令。 */
    private volatile boolean closed;
    /** 由后台发布，窗口按帧读取。 */
    private volatile View latest = new View(List.of(), List.of(), null, Display.WORKING);
    /** 以下窗口对象只由 Swing 事件线程访问。 */
    private DesktopWindow window;
    /** 桌面行为的窗口操作和动作序列由 EDT 驱动。 */
    private DesktopBehavior behavior;
    /** 事件线程只读取 latest，不处理任务数据。 */
    private Timer renderer;

    private DesktopSession(TaskEntry entry, ExecutionTraceStore traces, TaskLogBuffer log,
                           DesktopRecentTraces recentTraces, RunArchivePort archives, TaskActivity activity,
                           BehaviorPlanner planner, boolean idlePlanning, Duration idleDelay) {
        this.entry = entry;
        this.traces = traces;
        this.log = log;
        this.recentTraces = recentTraces;
        this.archives = archives;
        this.activity = activity;
        this.planner = planner;
        this.idlePlanning = idlePlanning;
        this.idleDelay = idleDelay;
    }

    public static void run(ConfigurableApplicationContext context) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("桌面模式需要可用的图形会话。请在桌面登录后使用 --myhelper.ui=desktop 启动。");
        }
        var environment = context.getEnvironment();
        try (var session = new DesktopSession(context.getBean(TaskEntry.class),
                context.getBean(ExecutionTraceStore.class), context.getBean(TaskLogBuffer.class),
                context.getBean(DesktopRecentTraces.class),
                context.getBeanProvider(RunArchivePort.class).getIfAvailable(),
                context.getBean(TaskActivity.class), context.getBean(BehaviorPlanner.class),
                environment.getProperty(BEHAVIOR_PREFIX + "enabled", Boolean.class, true),
                environment.getProperty(BEHAVIOR_PREFIX + "idle-delay", Duration.class, Duration.ofMinutes(2)))) {
            SwingUtilities.invokeAndWait(session::openWindow);
            session.observer.scheduleWithFixedDelay(session::refresh, 0, REFRESH_MILLIS, TimeUnit.MILLISECONDS);
            session.done.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("无法打开桌面调试界面", failure.getCause());
        }
    }

    private void openWindow() {
        window = new DesktopWindow(this::submit, this::cancel, this::inspect, this::exitWindow);
        var behaviorView = window.behaviorView();
        behavior = new DesktopBehavior(activity, planner, behaviorView, idlePlanning, idleDelay);
        behaviorView.onDrag(behavior::interruptForDrag);
        renderer = new Timer(REFRESH_MILLIS, eventListener());
        window.show();
        behavior.pulse(Display.WORKING);
        renderer.start();
    }

    private java.awt.event.ActionListener eventListener() {
        return new java.awt.event.ActionListener() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                View view = latest;
                window.render(view.tasks(), view.submissions(), view.notice());
                behavior.pulse(view.display());
            }
        };
    }

    private void refresh() {
        try {
            var batch = log.readAfter(cursor);
            boolean gap = batch.historyGap() || batch.droppedCount() > droppedCount;
            missedEvents |= gap;
            droppedCount = batch.droppedCount();
            for (var item : batch.entries()) {
                tasks.accept(item.event());
                cursor = item.sequence();
            }
            cursor = batch.lastSequence();
            if (gap) { tasks.markReplayGap(); }
            for (Handle handle : entry.activeHandles()) { tasks.attach(handle, null); }
            for (String taskId : tasks.ids()) {
                Run run = tasks.current(taskId);
                if (run != null) { refreshRun(run); }
            }
            publishView();
        } catch (RuntimeException failure) {
            LOG.warn("桌面视图刷新失败", failure);
        }
    }

    private void refreshRun(Run run) {
        if (!run.ended) {
            var snapshot = traces.findSnapshot(run.root).orElse(null);
            if (snapshot != null) { run.nodes = DesktopTaskGraph.project(snapshot); }
            run.refreshOutcome();
        }
        var finalized = recentTraces.find(run.root).orElse(null);
        if (finalized != null && !run.archiveRequested) {
            run.nodes = finalized;
            if (run.handle == null) { run.ended = true; }
        }
    }

    private void publishView() {
        String notice = missedEvents ? "部分实时事件已丢失，界面已刷新可用状态" : null;
        Instant now = Instant.now();
        latest = new View(tasks.views(now, archives != null), List.copyOf(submissions.values()), notice,
                tasks.behaviorDisplay(now));
    }

    private void submit(String input) {
        if (closed) { return; }
        Occupation pending = activity.occupy();
        pendingSubmissions.add(pending);
        enqueue(observer, new Runnable() {
            @Override
            public void run() {
                long id = ++nextSubmission;
                if (!makeSubmissionSpace()) {
                    // 保留被拒绝的原文，也不挤掉仍在提交的条目。
                    submissions.put(0L, new SubmissionView(input, "提交失败：等待受理的任务过多", null));
                    releaseSubmission(pending);
                    publishView();
                    return;
                }
                submissions.put(id, new SubmissionView(input, "正在提交", null));
                publishView();
                enqueue(commands, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            submitInBackground(id, input);
                        } finally {
                            releaseSubmission(pending);
                        }
                    }
                });
            }
        });
    }

    private void releaseSubmission(Occupation pending) {
        pending.close();
        pendingSubmissions.remove(pending);
    }

    private boolean makeSubmissionSpace() {
        if (submissions.size() < MAX_SUBMISSIONS) { return true; }
        var iterator = submissions.values().iterator();
        while (iterator.hasNext()) {
            if (!"正在提交".equals(iterator.next().status())) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }

    private void submitInBackground(long id, String input) {
        try {
            Handle handle = entry.submit(input);
            enqueue(observer, new Runnable() {
                @Override
                public void run() {
                    tasks.attach(handle, input);
                    submissions.remove(id);
                    refresh();
                }
            });
        } catch (RuntimeException failure) {
            enqueue(observer, new Runnable() {
                @Override
                public void run() {
                    submissions.put(id, new SubmissionView(input, "提交失败：" + reason(failure), null));
                    publishView();
                }
            });
        }
    }

    private void cancel(String taskId) {
        enqueue(observer, new Runnable() {
            @Override
            public void run() {
                Run run = tasks.current(taskId);
                if (run == null || run.handle == null || run.cancelling || run.handle.result().isDone()) { return; }
                Handle handle = run.handle;
                run.cancelling = true;
                run.cancelError = null;
                publishView();
                enqueue(commands, new Runnable() {
                    @Override
                    public void run() { requestCancel(run, handle); }
                });
            }
        });
    }

    private void requestCancel(Run run, Handle handle) {
        try {
            handle.cancel().run();
        } catch (RuntimeException failure) {
            enqueue(observer, new Runnable() {
                @Override
                public void run() {
                    run.cancelling = false;
                    run.cancelError = "取消请求失败：" + reason(failure);
                    publishView();
                }
            });
        }
    }

    private void inspect(String taskId) {
        if (archives == null) { return; }
        enqueue(observer, new Runnable() {
            @Override
            public void run() {
                Run run = tasks.current(taskId);
                if (run == null || !run.ended || run.archiveRequested) { return; }
                run.archiveRequested = true;
                ArchiveRef ref = new ArchiveRef(taskId, run.root);
                enqueue(history, new Runnable() {
                    @Override
                    public void run() { loadArchive(run, ref); }
                });
            }
        });
    }

    private void loadArchive(Run run, ArchiveRef ref) {
        try {
            ArchivedRun archive = archives.readArchive(ref).orElse(null);
            enqueue(observer, new Runnable() {
                @Override
                public void run() {
                    if (archive == null) {
                        run.archiveNote = "部分历史记录暂不可用";
                        run.archiveRequested = false;
                    } else {
                        run.applyArchive(archive);
                    }
                    publishView();
                }
            });
        } catch (RuntimeException failure) {
            enqueue(observer, new Runnable() {
                @Override
                public void run() {
                    run.archiveRequested = false;
                    run.archiveNote = "归档读取失败：" + failure.getClass().getSimpleName();
                    publishView();
                }
            });
        }
    }

    private void enqueue(ExecutorService worker, Runnable action) {
        if (closed) { return; }
        try {
            worker.execute(action);
        } catch (RejectedExecutionException shutdown) {
            if (!closed) { throw shutdown; }
        }
    }

    private static String reason(RuntimeException failure) {
        if (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException) {
            return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        }
        return failure.getClass().getSimpleName();
    }

    private void exitWindow() {
        closed = true;
        try {
            if (renderer != null) { renderer.stop(); }
            if (behavior != null) { behavior.close(); }
            if (window != null) { window.close(); }
        } finally {
            for (Occupation pending : pendingSubmissions) { releaseSubmission(pending); }
            done.countDown();
        }
    }

    @Override
    public void close() {
        closed = true;
        observer.shutdownNow();
        commands.shutdownNow();
        history.shutdownNow();
        if (done.getCount() != 0) { SwingUtilities.invokeLater(this::exitWindow); }
    }

    private record View(
            /** 本会话的任务气泡和最近任务。 */
            List<TaskView> tasks,
            /** 正在提交和提交失败的输入。 */
            List<SubmissionView> submissions,
            /** 观测缺口。 */
            String notice,
            /** 从日志和可用句柄投影的固定角色状态。 */
            Display display) { }

    private record DesktopThreadFactory(
            /** 从线程名区分提交、观测和读取归档。 */
            String purpose) implements ThreadFactory {
        @Override
        public Thread newThread(Runnable action) {
            Thread thread = new Thread(action, "myhelper-desktop-" + purpose);
            thread.setDaemon(true);
            return thread;
        }
    }
}
