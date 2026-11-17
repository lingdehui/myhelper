package com.example.myhelper.interaction.desktop.behavior;

import java.util.function.BooleanSupplier;

/** 在后台为一次空闲窗口规划短期行为。 */
@FunctionalInterface
public interface BehaviorPlanner {
    /** 获取许可后才发起调用，许可失效时返回 null，调用或响应错误交给桌面会话降级。 */
    BehaviorPlan plan(BehaviorPlan.Context context, BooleanSupplier permit);
}
