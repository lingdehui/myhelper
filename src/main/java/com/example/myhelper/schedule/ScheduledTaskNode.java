package com.example.myhelper.schedule;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;

/**
 * Neo4j 图谱节点：每日定时任务。
 *
 * <p>由 {@link ScheduledTaskService} 管理：注册后持久化到 Neo4j，进程重启后自动恢复调度。</p>
 */
@Node("ScheduledTask")
public class ScheduledTaskNode {

    @Id
    private String taskId;

    /** 触发小时 0-23 */
    private int hour;

    /** 触发分钟范围起点 0-59 */
    private int minuteFrom;

    /** 触发分钟范围终点 0-59 */
    private int minuteTo;

    /** 到点执行的任务描述（自然语言，走主流程动态调用工具） */
    private String taskDescription;

    private boolean enabled;

    /** 下次执行时间戳（epoch millis） */
    private Long nextRunAt;

    /** 上次执行时间戳（epoch millis） */
    private Long lastRunAt;

    private Long createdAt;

    public ScheduledTaskNode() {}

    public ScheduledTaskNode(String taskId, int hour, int minuteFrom, int minuteTo, String taskDescription) {
        this.taskId = taskId;
        this.hour = hour;
        this.minuteFrom = minuteFrom;
        this.minuteTo = minuteTo;
        this.taskDescription = taskDescription;
        this.enabled = true;
        this.createdAt = System.currentTimeMillis();
    }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public int getHour() { return hour; }
    public void setHour(int hour) { this.hour = hour; }

    public int getMinuteFrom() { return minuteFrom; }
    public void setMinuteFrom(int minuteFrom) { this.minuteFrom = minuteFrom; }

    public int getMinuteTo() { return minuteTo; }
    public void setMinuteTo(int minuteTo) { this.minuteTo = minuteTo; }

    public String getTaskDescription() { return taskDescription; }
    public void setTaskDescription(String taskDescription) { this.taskDescription = taskDescription; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Long getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(Long nextRunAt) { this.nextRunAt = nextRunAt; }

    public Long getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Long lastRunAt) { this.lastRunAt = lastRunAt; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
