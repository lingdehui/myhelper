package com.example.myhelper.interaction.desktop;

import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Action;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Prop;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Slot;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Workspace;
import com.example.myhelper.interaction.desktop.behavior.DesktopBehavior.Display;
import com.example.myhelper.interaction.desktop.behavior.DesktopBehavior.View;
import com.example.myhelper.interaction.desktop.DesktopMascot.Frame;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.Timer;

/** 将已验证的行为变成 EDT 上的角色状态和有轮廓的虚拟道具。 */
final class DesktopBehaviorView implements View {
    /** 每步最多移动这一段，仅沿当前工作区水平移动。 */
    private static final int MOVE_STEP = 36;
    /** 看向道具时指示具体方向，任务阶段用姿势表达。 */
    private static final int GAZE_X = 30;
    private static final int GAZE_Y = 102;
    private static final int GAZE_SIZE = 17;
    /** 道具与玩偶及彼此之间留出的桌面区域。 */
    private static final int PROP_GAP = 8;

    /** 道具窗口随玩偶窗口管理，但位置独立。 */
    private final JFrame mascot;
    /** 沿用手动拖动所用的气泡和对话锚点更新。 */
    private final Consumer<Point> moveMascot;
    /** 当前会话内各槽位真正已显示的道具。 */
    private final EnumMap<Slot, DesktopProp> props = new EnumMap<>(Slot.class);
    /** 绘制当前帧的角色按钮和已缓存的动画素材。 */
    private JButton avatar;
    private DesktopMascot sprite;
    /** 已应用的原生窗口轮廓，等形帧无须重设。 */
    private Shape nativeOutline;
    /** 当前固定姿势或动作。 */
    private Mark mark = Mark.IDLE;
    /** 只在 EDT 播放，每次按当前帧的停留时长重新计时。 */
    private final Timer animationTimer = new Timer(0, this::advanceAnimation);
    /** 当前姿势的帧位置。 */
    private int frameIndex;
    /** 窗口显示时开始播放，销毁后停止。 */
    private boolean animationStarted;
    /** 本动作关注的道具，摆放位置与身份保持不变。 */
    private DesktopProp focused;
    /** 仅在第一次越过拖动阈值时通知播放控制器。 */
    private Runnable onDrag;
    /** 按压从鼠标按下起即可阻挡自动位移。 */
    private boolean pointerDown;
    /** 本次按压已开始手动拖动。 */
    private boolean movedByHand;
    /** 本桌面会话的道具身份序号。 */
    private long nextPropId;

    DesktopBehaviorView(JFrame mascot, Consumer<Point> moveMascot) {
        this.mascot = mascot;
        this.moveMascot = moveMascot;
        animationTimer.setRepeats(false);
    }

    void attachAvatar(JButton avatar, DesktopMascot sprite) {
        this.avatar = avatar;
        this.sprite = sprite;
        nativeOutline = sprite.currentOutline();
        avatar.getAccessibleContext().setAccessibleDescription(mark.label);
    }

    void startAnimation() {
        animationStarted = true;
        restartAnimation();
    }

    void onDrag(Runnable listener) {
        onDrag = listener;
    }

    void pointerPressed() {
        pointerDown = true;
        restartAnimation();
    }

    void dragStarted() {
        if (!movedByHand) {
            movedByHand = true;
            if (onDrag != null) {
                onDrag.run();
            }
        }
    }

    void pointerReleased() {
        pointerDown = false;
        movedByHand = false;
        restartAnimation();
    }

    @Override
    public boolean dragging() {
        return pointerDown;
    }

    @Override
    public Workspace workspace() {
        Rectangle bounds = DesktopWindow.workArea(mascot.getGraphicsConfiguration());
        return new Workspace(bounds.width, bounds.height, mascot.getX() - bounds.x, mascot.getY() - bounds.y);
    }

    @Override
    public Map<Slot, Prop> props() {
        EnumMap<Slot, Prop> snapshot = new EnumMap<>(Slot.class);
        for (var entry : props.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().appearance());
        }
        return Map.copyOf(snapshot);
    }

    @Override
    public boolean apply(Action action) {
        return switch (action.type()) {
            case POSE -> {
                showMark(switch (action.pose()) {
                    case REST -> Mark.REST;
                    case ALERT -> Mark.ALERT;
                    case THINK -> Mark.THINK;
                }, null);
                yield true;
            }
            case MOVE_TO_ANCHOR -> move(action.slot());
            case CREATE_PROP -> create(action.slot(), action.prop());
            case LOOK_AT_SLOT -> focus(action.slot(), true);
            case SHOW_PROP -> focus(action.slot(), false);
            case HIDE_PROP -> hide(action.slot());
        };
    }

    @Override
    public void show(Display display) {
        Mark next = switch (display) {
            case IDLE -> Mark.IDLE;
            case REST -> Mark.REST;
            case WORKING -> Mark.WORKING;
            case MODEL -> Mark.MODEL;
            case TOOL -> Mark.TOOL;
            case VERIFY -> Mark.VERIFY;
            case REVIEW -> Mark.REVIEW;
            case SETTLING -> Mark.SETTLING;
            case RESULT -> Mark.RESULT;
        };
        showMark(next, null);
    }

    private boolean move(Slot slot) {
        if (dragging()) {
            return false;
        }
        Rectangle work = DesktopWindow.workArea(mascot.getGraphicsConfiguration());
        Rectangle anchor = mascot.getBounds();
        Rectangle next = movementBounds(work, anchor, slot);
        showMark(Mark.IDLE, null);
        if (!next.getLocation().equals(anchor.getLocation())) {
            moveMascot.accept(next.getLocation());
        }
        return true;
    }

    static Rectangle movementBounds(Rectangle work, Rectangle anchor, Slot slot) {
        int target = switch (slot) {
            case LEFT -> work.x + PROP_GAP;
            case CENTER -> work.x + (work.width - anchor.width) / 2;
            case RIGHT -> work.x + work.width - anchor.width - PROP_GAP;
        };
        int x = anchor.x + Math.clamp(target - anchor.x, -MOVE_STEP, MOVE_STEP);
        return DesktopWindow.clamped(work, x, anchor.y, anchor.width, anchor.height);
    }

    private boolean create(Slot slot, Prop appearance) {
        if (props.containsKey(slot)) {
            return false;
        }
        GraphicsConfiguration screen = mascot.getGraphicsConfiguration();
        Rectangle work = DesktopWindow.workArea(screen);
        List<Rectangle> occupied = new ArrayList<>();
        for (DesktopProp prop : props.values()) {
            occupied.add(prop.bounds());
        }
        Rectangle bounds = propBounds(work, mascot.getBounds(), slot, occupied);
        if (bounds == null) {
            return false;
        }
        DesktopProp prop = new DesktopProp(mascot, screen, ++nextPropId, appearance, bounds);
        props.put(slot, prop);
        showMark(Mark.ALERT, prop);
        return true;
    }

    /** 首选槽位一侧。边缘或已有道具挡住时，在邻近的空桌面放置。 */
    static Rectangle propBounds(Rectangle work, Rectangle anchor, Slot slot, List<Rectangle> occupied) {
        int width = DesktopProp.WIDTH;
        int height = DesktopProp.HEIGHT;
        if (work.width < width || work.height < height) {
            return null;
        }
        int left = anchor.x - PROP_GAP - width;
        int center = anchor.x + (anchor.width - width) / 2;
        int right = anchor.x + anchor.width + PROP_GAP;
        int floor = anchor.y + anchor.height - height;
        int below = anchor.y + anchor.height + PROP_GAP;
        int above = anchor.y - height - PROP_GAP;
        Point preferred = switch (slot) {
            case LEFT -> new Point(left, floor);
            case CENTER -> new Point(center, below);
            case RIGHT -> new Point(right, floor);
        };
        List<Point> candidates = List.of(preferred, new Point(left, floor), new Point(right, floor),
                new Point(center, below), new Point(center, above), new Point(left, floor - height - PROP_GAP),
                new Point(right, floor - height - PROP_GAP), new Point(left - width - PROP_GAP, floor),
                new Point(right + width + PROP_GAP, floor), new Point(left, above), new Point(right, above));
        for (Point candidate : candidates) {
            Rectangle bounds = DesktopWindow.clamped(work, candidate.x, candidate.y, width, height);
            if (!bounds.intersects(anchor) && isAvailable(bounds, occupied)) {
                return bounds;
            }
        }
        return null;
    }

    private static boolean isAvailable(Rectangle bounds, List<Rectangle> occupied) {
        Rectangle withGap = new Rectangle(bounds);
        withGap.grow(PROP_GAP / 2, PROP_GAP / 2);
        for (Rectangle other : occupied) {
            if (withGap.intersects(other)) {
                return false;
            }
        }
        return true;
    }

    private boolean focus(Slot slot, boolean look) {
        DesktopProp prop = props.get(slot);
        if (prop == null) {
            return false;
        }
        showMark(look ? gaze(prop.bounds(), mascot.getBounds()) : Mark.ALERT, prop);
        return true;
    }

    private static Mark gaze(Rectangle prop, Rectangle anchor) {
        double deltaX = prop.getCenterX() - anchor.getCenterX();
        double deltaY = prop.getCenterY() - anchor.getCenterY();
        if (Math.abs(deltaY) > Math.abs(deltaX)) {
            return deltaY < 0 ? Mark.LOOK_UP : Mark.LOOK_DOWN;
        }
        return deltaX < 0 ? Mark.LOOK_LEFT : Mark.LOOK_RIGHT;
    }

    private boolean hide(Slot slot) {
        DesktopProp prop = props.remove(slot);
        if (prop == null) {
            return false;
        }
        showMark(Mark.IDLE, null);
        prop.close();
        return true;
    }

    private void showMark(Mark next, DesktopProp nextFocus) {
        if (nextFocus != focused) {
            if (focused != null) {
                focused.highlight(false);
            }
            focused = nextFocus;
            if (focused != null) {
                focused.highlight(true);
            }
        }
        if (mark != next) {
            mark = next;
            if (avatar != null) {
                avatar.getAccessibleContext().setAccessibleDescription(mark.label);
                restartAnimation();
                avatar.repaint();
            }
        }
    }

    private void restartAnimation() {
        animationTimer.stop();
        frameIndex = 0;
        displayFrame();
    }

    private void advanceAnimation(ActionEvent event) {
        if (animationStarted && !pointerDown) {
            frameIndex = (frameIndex + 1) % mark.animation.frames.size();
            displayFrame();
        }
    }

    private void displayFrame() {
        if (sprite == null) {
            return;
        }
        TimedFrame frame = mark.animation.frames.get(frameIndex);
        if (sprite.select(frame.frame())) {
            Shape outline = sprite.currentOutline();
            if (outline != nativeOutline) {
                mascot.setShape(outline);
                nativeOutline = outline;
            }
            avatar.repaint();
        }
        if (animationStarted && !pointerDown && frame.durationMs() > 0) {
            animationTimer.setInitialDelay(frame.durationMs());
            animationTimer.restart();
        }
    }

    void paintPose(Graphics graphics) {
        if (mark.direction == null) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(252, 247, 255));
            g.fillOval(GAZE_X, GAZE_Y, GAZE_SIZE, GAZE_SIZE);
            g.setColor(new Color(0x62449E));
            g.drawOval(GAZE_X, GAZE_Y, GAZE_SIZE - 1, GAZE_SIZE - 1);
            g.setFont(new Font(Font.DIALOG, Font.BOLD, 12));
            var metrics = g.getFontMetrics();
            int x = GAZE_X + (GAZE_SIZE - metrics.stringWidth(mark.direction)) / 2;
            int y = GAZE_Y + (GAZE_SIZE + metrics.getAscent() - metrics.getDescent()) / 2;
            g.drawString(mark.direction, x, y);
        } finally {
            g.dispose();
        }
    }

    void close() {
        animationStarted = false;
        animationTimer.stop();
        for (DesktopProp prop : props.values()) {
            prop.close();
        }
        props.clear();
        focused = null;
    }

    /** 每种状态只选用本地已有的动作序列。 */
    private enum Mark {
        IDLE("空闲", Animation.STANDING),
        REST("休息", Animation.RESTING),
        ALERT("注意", Animation.LOOKING_UP),
        THINK("思考", Animation.FOCUSING),
        WORKING("正在工作", Animation.FOCUSING),
        MODEL("等待模型", Animation.LOOKING_UP),
        TOOL("正在运行工具", Animation.FOCUSING),
        VERIFY("正在验收", Animation.CHECKING),
        REVIEW("结果待核对", Animation.FOCUSING),
        SETTLING("正在收尾", Animation.STANDING),
        RESULT("任务已完成", Animation.CHEERING),
        LOOK_LEFT("看向左侧道具", Animation.LOOKING_UP, "←"),
        LOOK_RIGHT("看向右侧道具", Animation.LOOKING_UP, "→"),
        LOOK_UP("看向上方道具", Animation.LOOKING_UP, "↑"),
        LOOK_DOWN("看向下方道具", Animation.LOOKING_UP, "↓");

        /** 供辅助技术读出当前姿势。 */
        private final String label;
        /** 本地动作的顺序与时长。 */
        private final Animation animation;
        /** 有明确道具方位时的方向提示。 */
        private final String direction;

        Mark(String label, Animation animation) {
            this(label, animation, null);
        }

        Mark(String label, Animation animation, String direction) {
            this.label = label;
            this.animation = animation;
            this.direction = direction;
        }
    }

    private enum Animation {
        STANDING(new TimedFrame(Frame.BASE, 3400), new TimedFrame(Frame.BLINK, 120),
                new TimedFrame(Frame.BASE, 4150), new TimedFrame(Frame.BLINK, 110)),
        RESTING(new TimedFrame(Frame.BLINK, 0)),
        LOOKING_UP(new TimedFrame(Frame.ALERT, 2500), new TimedFrame(Frame.BLINK, 110)),
        FOCUSING(new TimedFrame(Frame.THINK, 2800), new TimedFrame(Frame.BLINK, 100)),
        CHECKING(new TimedFrame(Frame.THINK, 750), new TimedFrame(Frame.ALERT, 1000),
                new TimedFrame(Frame.THINK, 1250), new TimedFrame(Frame.BLINK, 115)),
        CHEERING(new TimedFrame(Frame.HAPPY, 850), new TimedFrame(Frame.BASE, 0));

        /** 最后一帧时长为零时保持原姿势，其余序列循环。 */
        private final List<TimedFrame> frames;

        Animation(TimedFrame... frames) {
            this.frames = List.of(frames);
        }
    }

    private record TimedFrame(
            /** 对应缓存中的本地透明帧。 */
            Frame frame,
            /** 显示时长，零表示持续到新的状态。 */
            int durationMs) { }
}
