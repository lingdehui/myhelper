package com.example.myhelper.schedule;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 定时任务工具：暴露给 AI 的 {@code scheduleDailyTask} 等能力。
 *
 * <p>到点后由 {@link ScheduledTaskService} 把任务描述作为输入走主流程，动态调用工具执行。</p>
 */
@Component
public class ScheduledTaskTool {

    private final ScheduledTaskService scheduledTaskService;

    public ScheduledTaskTool(ScheduledTaskService scheduledTaskService) {
        this.scheduledTaskService = scheduledTaskService;
    }

    @Tool(description = "注册一个每天执行的定时任务。到点后会把 taskDescription 作为输入走主流程（规划 + 动态调用工具）自动执行，执行完自动注册第二天的同一任务。hour 是小时 0-23；minuteFrom/minuteTo 是分钟范围，如 0,30 表示整点到30分之间随机触发。")
    public String scheduleDailyTask(
            @ToolParam(description = "小时 0-23") int hour,
            @ToolParam(description = "分钟范围起点 0-59") int minuteFrom,
            @ToolParam(description = "分钟范围终点 0-59") int minuteTo,
            @ToolParam(description = "到点要执行的任务自然语言描述") String taskDescription) {
        return scheduledTaskService.registerDailyTask(hour, minuteFrom, minuteTo, taskDescription);
    }

    @Tool(description = "列出所有已注册的定时任务及其下次执行时间。")
    public String listScheduledTasks() {
        return scheduledTaskService.listTasks();
    }

    @Tool(description = "停用/取消一个定时任务。taskId 通过 listScheduledTasks 获取。")
    public String cancelScheduledTask(
            @ToolParam(description = "任务ID") String taskId) {
        return scheduledTaskService.cancelTask(taskId);
    }
}
