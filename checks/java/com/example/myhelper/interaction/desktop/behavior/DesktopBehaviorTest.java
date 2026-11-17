package com.example.myhelper.interaction.desktop.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.myhelper.application.TaskActivity;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Action;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Color;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Context;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Pattern;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Prop;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Slot;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Style;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Type;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Workspace;
import com.example.myhelper.interaction.desktop.behavior.DesktopBehavior.Display;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class DesktopBehaviorTest {
    @Test
    void fixedDisplayAndNoCallUntilLastOccupationEnds() throws Exception {
        var planner = new Probe(plan(Slot.LEFT, 35), false, false);
        try (var harness = new Harness(planner);
             var first = harness.activity.occupy(); var second = harness.activity.occupy()) {
            harness.pulse(Display.TOOL);
            assertEquals(Display.TOOL, harness.view.display);
            first.close();
            harness.pulse(Display.VERIFY);
            assertEquals(Display.VERIFY, harness.view.display);
            assertFalse(planner.preparing.await(100, TimeUnit.MILLISECONDS));
            second.close();
            harness.pulse(Display.WORKING);
            await(harness.view.twoActions);
            assertEquals(1, planner.calls.get());
            assertEquals(2, harness.view.applied.get());
        }
    }

    @Test
    void taskAdmissionBetweenTimerAndDispatchDeniesTheModelCall() throws Exception {
        var planner = new Probe(plan(Slot.LEFT, 40), true, false);
        try (var harness = new Harness(planner)) {
            harness.pulse(Display.WORKING);
            await(planner.preparing);
            try (var occupation = harness.activity.occupy()) {
                planner.prepare.countDown();
                await(planner.returning);
                harness.pulse(Display.MODEL);
                assertEquals(0, planner.calls.get());
                assertEquals(0, harness.view.applied.get());
            }
        }
    }

    @Test
    void evenAnUnobservedShortTaskInvalidatesAnInFlightPlan() throws Exception {
        var planner = new Probe(plan(Slot.LEFT, 40), false, true);
        try (var harness = new Harness(planner)) {
            harness.pulse(Display.WORKING);
            await(planner.dispatched);
            harness.activity.occupy().close();
            planner.reply.countDown();
            await(planner.returning);
            assertFalse(harness.view.firstAction.await(150, TimeUnit.MILLISECONDS));
            assertEquals(1, planner.calls.get());
        }
    }

    @Test
    void manualDragInterruptsRemainingActionsAndRetainsPlacedProp() throws Exception {
        var planner = new Probe(plan(Slot.LEFT, 500), false, false);
        try (var harness = new Harness(planner)) {
            harness.pulse(Display.WORKING);
            await(harness.view.firstAction);
            SwingUtilities.invokeAndWait(harness.behavior::interruptForDrag);
            assertFalse(harness.view.twoActions.await(650, TimeUnit.MILLISECONDS));
            SwingUtilities.invokeAndWait(harness::checkPlacedProp);
        }
    }

    @Test
    void invalidSuffixFallsBackWithoutApplyingAnyPrefix() throws Exception {
        var planner = new Probe(plan(Slot.RIGHT, 40), false, false);
        try (var harness = new Harness(planner)) {
            harness.pulse(Display.WORKING);
            await(harness.view.resting);
            assertEquals(0, harness.view.applied.get());
        }
    }

    @Test
    void closingDiscardsARequestAlreadyInFlight() throws Exception {
        var planner = new Probe(plan(Slot.LEFT, 40), false, true);
        try (var harness = new Harness(planner)) {
            harness.pulse(Display.WORKING);
            await(planner.dispatched);
            harness.close();
            planner.reply.countDown();
            await(planner.returning);
            assertFalse(harness.view.firstAction.await(150, TimeUnit.MILLISECONDS));
        }
    }

    private static BehaviorPlan plan(Slot lookSlot, int firstMillis) {
        var cup = new Prop(Style.CUP, Color.LAVENDER, Pattern.PLAIN);
        return new BehaviorPlan(List.of(new Action(Type.CREATE_PROP, null, Slot.LEFT, cup, firstMillis),
                new Action(Type.LOOK_AT_SLOT, null, lookSlot, null, 20)));
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(4, TimeUnit.SECONDS));
    }

    private static final class Harness implements AutoCloseable {
        /** 测试与控制器共用的任务占用。 */
        final TaskActivity activity = new TaskActivity();
        /** 测试不创建本机窗口。 */
        final TestView view = new TestView();
        /** 使用真实 EDT 和后台计划计时。 */
        final DesktopBehavior behavior;

        Harness(Probe planner) {
            behavior = new DesktopBehavior(activity, planner, view, true, Duration.ofMillis(20));
        }

        void pulse(Display display) throws Exception {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() { behavior.pulse(display); }
            });
        }

        void checkPlacedProp() { assertEquals(Style.CUP, view.props.get(Slot.LEFT).style()); }

        @Override
        public void close() throws Exception { SwingUtilities.invokeAndWait(behavior::close); }
    }

    private static final class TestView implements DesktopBehavior.View {
        /** 只在 EDT 读写的虚拟道具。 */
        final Map<Slot, Prop> props = new EnumMap<>(Slot.class);
        /** 便于测试等待每个动作开始。 */
        final CountDownLatch firstAction = new CountDownLatch(1);
        /** 验证后续动作是否已开始。 */
        final CountDownLatch twoActions = new CountDownLatch(2);
        /** 等待错误回退姿态。 */
        final CountDownLatch resting = new CountDownLatch(1);
        /** 控制器实际开始的动作数量。 */
        final AtomicInteger applied = new AtomicInteger();
        /** 最近一次固定展示。 */
        volatile Display display;

        @Override
        public Workspace workspace() { return new Workspace(1920, 1080, 100, 100); }

        @Override
        public Map<Slot, Prop> props() { return props; }

        @Override
        public boolean dragging() { return false; }

        @Override
        public boolean apply(Action action) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            if (action.type() == Type.CREATE_PROP) { props.put(action.slot(), action.prop()); }
            applied.incrementAndGet();
            firstAction.countDown();
            twoActions.countDown();
            return true;
        }

        @Override
        public void show(Display display) {
            this.display = display;
            if (display == Display.REST) { resting.countDown(); }
        }
    }

    private static final class Probe implements BehaviorPlanner {
        /** 模拟固定模型响应。 */
        final BehaviorPlan response;
        /** 即将检查派发许可。 */
        final CountDownLatch preparing = new CountDownLatch(1);
        /** 精确控制模型准备期间的竞态。 */
        final CountDownLatch prepare;
        /** 只有真正取得许可才发出这个信号。 */
        final CountDownLatch dispatched = new CountDownLatch(1);
        /** 模拟同步远端请求在途。 */
        final CountDownLatch reply;
        /** 模型调用已走到完成分支。 */
        final CountDownLatch returning = new CountDownLatch(1);
        /** 真正获准的请求次数。 */
        final AtomicInteger calls = new AtomicInteger();

        Probe(BehaviorPlan response, boolean pausePrepare, boolean pauseReply) {
            this.response = response;
            prepare = new CountDownLatch(pausePrepare ? 1 : 0);
            reply = new CountDownLatch(pauseReply ? 1 : 0);
        }

        @Override
        public BehaviorPlan plan(Context context, BooleanSupplier permit) {
            preparing.countDown();
            try {
                await(prepare);
                if (!permit.getAsBoolean()) { return null; }
                calls.incrementAndGet();
                dispatched.countDown();
                await(reply);
                return response;
            } catch (InterruptedException shutdown) {
                Thread.currentThread().interrupt();
                return response;
            } finally {
                returning.countDown();
            }
        }
    }
}
