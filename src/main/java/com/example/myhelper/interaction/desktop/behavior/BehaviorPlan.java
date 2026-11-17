package com.example.myhelper.interaction.desktop.behavior;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 已转换为本地动作类型的短期计划。道具引用要结合规划快照整体校验。 */
public record BehaviorPlan(
        /** 按顺序播放的动作。 */
        List<Action> actions) {
    /** 每次规划最多生成的动作数量。 */
    public static final int MAX_ACTIONS = 3;
    /** 整段动作及任一单独动作的最大时长。 */
    public static final int MAX_PLAN_DURATION_MILLIS = 15_000;
    /** 发送模型的近期动作数量上限。 */
    public static final int MAX_RECENT_ACTIONS = 6;

    public BehaviorPlan {
        actions = List.copyOf(actions);
        if (actions.isEmpty() || actions.size() > MAX_ACTIONS) {
            throw new IllegalArgumentException("短期计划动作数量无效");
        }
        int total = 0;
        for (Action action : actions) {
            total += action.durationMs();
        }
        if (total > MAX_PLAN_DURATION_MILLIS) {
            throw new IllegalArgumentException("短期计划总时长超限");
        }
    }

    /** 按执行顺序模拟槽位变动，失败时实际桌面状态尚未修改。 */
    public void validate(Context context) {
        EnumSet<Slot> occupied = EnumSet.noneOf(Slot.class);
        occupied.addAll(context.props().keySet());
        for (Action action : actions) {
            Slot slot = action.slot();
            switch (action.type()) {
                case CREATE_PROP -> {
                    if (!occupied.add(slot)) {
                        throw new IllegalArgumentException("道具槽位已占用");
                    }
                }
                case HIDE_PROP -> {
                    if (!occupied.remove(slot)) {
                        throw new IllegalArgumentException("槽位没有可收起的道具");
                    }
                }
                case SHOW_PROP, LOOK_AT_SLOT -> {
                    if (!occupied.contains(slot)) {
                        throw new IllegalArgumentException("动作引用了空道具槽位");
                    }
                }
                case POSE, MOVE_TO_ANCHOR -> { }
            }
        }
    }

    public enum Type { POSE, MOVE_TO_ANCHOR, CREATE_PROP, LOOK_AT_SLOT, SHOW_PROP, HIDE_PROP }

    public enum Slot { LEFT, CENTER, RIGHT }

    public enum Pose { REST, ALERT, THINK }

    public enum Style { CUP, BOOK, STAR }

    public enum Color { LAVENDER, PINK, BLUE }

    public enum Pattern { PLAIN, DOTS, STRIPES }

    public enum Outcome { NONE, COMPLETED, FAILED, INTERRUPTED }

    public record Action(
            /** 动作类型。 */
            Type type,
            /** 定姿动作使用的姿势。 */
            Pose pose,
            /** 其余动作对应的工作区锚点或道具槽位。 */
            Slot slot,
            /** 创建道具时采用的本地样式。 */
            Prop prop,
            /** 动作从开始到结束的时间，单位毫秒。 */
            int durationMs) {
        public Action {
            Objects.requireNonNull(type);
            boolean argumentsValid = switch (type) {
                case POSE -> pose != null && slot == null && prop == null;
                case CREATE_PROP -> pose == null && slot != null && prop != null;
                case MOVE_TO_ANCHOR, LOOK_AT_SLOT, SHOW_PROP, HIDE_PROP -> pose == null && slot != null && prop == null;
            };
            if (!argumentsValid || durationMs < 1 || durationMs > MAX_PLAN_DURATION_MILLIS) {
                throw new IllegalArgumentException("短期动作参数或时长无效");
            }
        }
    }

    public record Prop(
            /** 道具形状。 */
            Style style,
            /** 本地配色。 */
            Color color,
            /** 本地装饰图案。 */
            Pattern pattern) {
        public Prop {
            Objects.requireNonNull(style);
            Objects.requireNonNull(color);
            Objects.requireNonNull(pattern);
        }
    }

    public record Workspace(
            /** 当前工作区宽度。 */
            int width,
            /** 当前工作区高度。 */
            int height,
            /** 角色相对于工作区的横坐标。 */
            int x,
            /** 角色相对于工作区的纵坐标。 */
            int y) { }

    public record Context(
            /** 当前工作区及角色位置。 */
            Workspace workspace,
            /** 当前已占用的槽位和道具。 */
            Map<Slot, Prop> props,
            /** 近期已完成动作，按时间顺序排列。 */
            List<Action> recentActions,
            /** 上一次规划与播放的结果。 */
            Outcome lastOutcome) {
        public Context {
            Objects.requireNonNull(workspace);
            Objects.requireNonNull(lastOutcome);
            props = Map.copyOf(props);
            int start = Math.max(0, recentActions.size() - MAX_RECENT_ACTIONS);
            recentActions = List.copyOf(recentActions.subList(start, recentActions.size()));
        }
    }
}
