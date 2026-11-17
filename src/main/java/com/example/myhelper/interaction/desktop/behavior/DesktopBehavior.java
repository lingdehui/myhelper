package com.example.myhelper.interaction.desktop.behavior;

import com.example.myhelper.application.TaskActivity;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Action;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Context;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Outcome;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Prop;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Slot;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Workspace;
import java.awt.event.ActionEvent;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 桌面会话的空闲规划及受控播放。界面状态由 EDT 持有，后台线程只读取不可变输入。 */
public final class DesktopBehavior implements AutoCloseable {
    /** 独立请求失败只记录错误类别。 */
    private static final Logger LOG = LoggerFactory.getLogger(DesktopBehavior.class);
    /** 无法得到合规计划时，短暂使用固定休息姿势。 */
    private static final int REST_MILLIS = 900;

    /** 与根任务及启动扫描共用的空闲许可。 */
    private final TaskActivity activity;
    /** 只在后台线程调用的模型入口。 */
    private final BehaviorPlanner planner;
    /** 角色和道具的 EDT 操作。 */
    private final View view;
    /** 本机是否启用空闲模型。固定任务姿势始终可用。 */
    private final boolean enabled;
    /** 每次连续空闲等待的长度。 */
    private final long idleNanos;
    /** 同一桌面会话串行执行空闲计时和模型请求。 */
    private final ScheduledThreadPoolExecutor worker;
    /** 一次只为正在显示的动作计时。 */
    private final Timer stepTimer;
    /** 已经完成的有限动作历史，按先后顺序保存。 */
    private final ArrayDeque<Action> recent = new ArrayDeque<>();
    /** 正在等待、请求或播放的轮次。失效时后台停止交付结果。 */
    private volatile Cycle cycle;
    /** EDT 发布的最近工作区、道具和播放结果快照。 */
    private volatile Context latest;
    /** 当前空闲轮次的定时任务。 */
    private ScheduledFuture<?> scheduled;
    /** 整段校验通过后排队的动作。 */
    private final ArrayDeque<Action> remaining = new ArrayDeque<>();
    /** 计时结束后才加入最近完成历史。 */
    private Action playing;
    /** 本轮在 EDT 的最晚结束时刻。 */
    private long finishBy;
    /** 本轮正常播放完毕时记录的结果。 */
    private Outcome onFinish;
    /** 最近一次播放或规划结果。 */
    private Outcome last = Outcome.NONE;
    /** 避免关闭后重新安排计时。仅由 EDT 访问。 */
    private boolean closed;

    public DesktopBehavior(TaskActivity activity, BehaviorPlanner planner, View view,
                           boolean enabled, Duration idleDelay) {
        this.activity = Objects.requireNonNull(activity);
        this.planner = Objects.requireNonNull(planner);
        this.view = Objects.requireNonNull(view);
        this.enabled = enabled;
        this.idleNanos = idleDelay.toNanos();
        if (idleNanos <= 0) {
            throw new IllegalArgumentException("桌面行为空闲等待必须为正数");
        }
        worker = new ScheduledThreadPoolExecutor(1, DesktopBehavior::newWorker);
        worker.setRemoveOnCancelPolicy(true);
        stepTimer = new Timer(0, this::advance);
        stepTimer.setRepeats(false);
    }

    /** 从 EDT 同步展示阶段，并给后台更新本轮可使用的桌面信息。 */
    public void pulse(Display busyDisplay) {
        if (closed) { return; }
        var state = activity.snapshot();
        if (cycle != null && cycle.generation() != state.generation()) {
            stopCycle(Outcome.INTERRUPTED);
        }
        if (state.busy()) {
            // 刚进入受理时，视图可能仍保留上一任务成功后的短暂动作。
            view.show(busyDisplay == Display.RESULT ? Display.WORKING : busyDisplay);
            return;
        }
        if (view.dragging()) {
            interruptForDrag();
            return;
        }
        Display idleDisplay = busyDisplay == Display.RESULT ? Display.RESULT : Display.IDLE;
        if (!enabled) {
            view.show(idleDisplay);
            return;
        }
        latest = context();
        if (onFinish == null) { view.show(idleDisplay); }
        if (cycle == null) {
            Cycle next = new Cycle(state.generation());
            cycle = next;
            scheduled = worker.schedule(new Request(next), idleNanos, TimeUnit.NANOSECONDS);
        }
    }

    /** 手动移动发生在 EDT，立即撤销本轮尚未开始的动作和请求。 */
    public void interruptForDrag() {
        if (cycle != null) {
            stopCycle(Outcome.INTERRUPTED);
            if (!activity.snapshot().busy()) { view.show(Display.IDLE); }
        }
    }

    private Context context() {
        return new Context(view.workspace(), view.props(), List.copyOf(recent), last);
    }

    private boolean current(Cycle candidate) {
        return candidate != null && cycle == candidate && activity.currentIdle(candidate.generation());
    }

    private final class Request implements Runnable {
        /** 任务开始、手动拖动或关闭均可撤销该轮次。 */
        private final Cycle requested;

        Request(Cycle requested) { this.requested = requested; }

        @Override
        public void run() {
            if (!current(requested)) { return; }
            BehaviorPlan plan = null;
            try (var dispatch = new Dispatch(requested)) {
                plan = planner.plan(latest, dispatch::start);
            } catch (RuntimeException failure) {
                LOG.debug("桌面空闲规划未完成，类型={}", failure.getClass().getSimpleName());
            }
            if (current(requested)) {
                SwingUtilities.invokeLater(new Playback(requested, plan));
            }
        }
    }

    private final class Dispatch implements AutoCloseable {
        /** 实际模型派发前检查定时任务所属轮次。 */
        private final Cycle requested;
        /** 从确认派发到同步调用返回，桌面最多持有一个请求。 */
        private TaskActivity.IdleCall lease;

        Dispatch(Cycle requested) { this.requested = requested; }

        boolean start() {
            if (lease != null || !current(requested)) { return false; }
            lease = activity.tryStartIdleCall(requested.generation());
            return lease != null && current(requested);
        }

        @Override
        public void close() {
            if (lease != null) { lease.close(); }
        }
    }

    private final class Playback implements Runnable {
        /** EDT 开始播放前重新核对的许可版本。 */
        private final Cycle requested;
        /** 为空时使用固定休息姿势。 */
        private final BehaviorPlan plan;

        Playback(Cycle requested, BehaviorPlan plan) {
            this.requested = requested;
            this.plan = plan;
        }

        @Override
        public void run() {
            if (!current(requested)) { return; }
            if (view.dragging()) {
                interruptForDrag();
                return;
            }
            if (plan == null || !validPlan(plan)) {
                if (!current(requested)) { return; }
                onFinish = Outcome.FAILED;
                view.show(Display.REST);
                startTimer(REST_MILLIS);
                return;
            }
            onFinish = Outcome.COMPLETED;
            remaining.addAll(plan.actions());
            finishBy = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(BehaviorPlan.MAX_PLAN_DURATION_MILLIS);
            advance(null);
        }
    }

    private boolean validPlan(BehaviorPlan plan) {
        try {
            plan.validate(context());
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private void advance(ActionEvent ignored) {
        Cycle active = cycle;
        if (!current(active) || view.dragging()) {
            if (active != null) { stopCycle(Outcome.INTERRUPTED); }
            return;
        }
        if (playing != null) {
            if (recent.size() == BehaviorPlan.MAX_RECENT_ACTIONS) { recent.removeFirst(); }
            recent.addLast(playing);
            playing = null;
        }
        if (remaining.isEmpty()) {
            stopCycle(onFinish);
            if (activity.currentIdle(active.generation())) { view.show(Display.IDLE); }
            return;
        }
        long availableNanos = finishBy - System.nanoTime();
        if (availableNanos <= 0) {
            stopCycle(Outcome.INTERRUPTED);
            if (activity.currentIdle(active.generation())) { view.show(Display.IDLE); }
            return;
        }
        Action next = remaining.removeFirst();
        if (!current(active) || !apply(next)) {
            stopCycle(Outcome.INTERRUPTED);
            if (activity.currentIdle(active.generation())) { view.show(Display.IDLE); }
            return;
        }
        long nanosPerMillis = TimeUnit.MILLISECONDS.toNanos(1);
        long availableMillis = (availableNanos + nanosPerMillis - 1) / nanosPerMillis;
        if (next.durationMs() <= availableMillis) {
            playing = next;
        } else {
            remaining.clear();
            onFinish = Outcome.INTERRUPTED;
        }
        startTimer((int) Math.min(next.durationMs(), availableMillis));
    }

    private boolean apply(Action action) {
        try {
            return view.apply(action);
        } catch (RuntimeException failure) {
            LOG.debug("无法在当前桌面执行动作，类型={}", failure.getClass().getSimpleName());
            return false;
        }
    }

    private void startTimer(int durationMillis) {
        stepTimer.setInitialDelay(durationMillis);
        stepTimer.restart();
    }

    private void stopCycle(Outcome outcome) {
        cycle = null;
        if (scheduled != null) {
            scheduled.cancel(false);
            scheduled = null;
        }
        stepTimer.stop();
        remaining.clear();
        playing = null;
        onFinish = null;
        last = outcome;
    }

    @Override
    public void close() {
        closed = true;
        stopCycle(Outcome.INTERRUPTED);
        worker.shutdownNow();
    }

    private static Thread newWorker(Runnable work) {
        Thread thread = new Thread(work, "myhelper-desktop-behavior");
        thread.setDaemon(true);
        return thread;
    }

    private record Cycle(
            /** 任务或扫描活动发生时更新的版本。 */
            long generation) { }

    public enum Display { IDLE, REST, WORKING, MODEL, TOOL, VERIFY, REVIEW, SETTLING, RESULT }

    public interface View {
        Workspace workspace();
        Map<Slot, Prop> props();
        boolean dragging();
        boolean apply(Action action);
        void show(Display display);
    }
}
