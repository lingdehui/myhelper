package com.example.myhelper.schedule;

import com.example.myhelper.config.ModelRouter;
import com.example.myhelper.service.TtsService;
import com.example.myhelper.service.TurnProcessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 每日定时任务服务：注册 → 持久化(Neo4j) → 调度 → 到点走主流程(规划+动态调用工具) → 递归调度明天。
 *
 * <p>进程重启后通过 {@link #restore()} 恢复所有启用任务，不会丢失。</p>
 */
@Component
public class ScheduledTaskService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskService.class);

    private final ScheduledTaskRepository repository;
    private final TurnProcessor turnProcessor;
    private final ModelRouter modelRouter;
    private final TtsService ttsService;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "scheduled-task");
        t.setDaemon(true);
        return t;
    });

    /** 启动后由 MyHelperApplication 注入完整工具列表（含动态生成工具） */
    private volatile ToolCallback[] tools = new ToolCallback[0];

    public ScheduledTaskService(ScheduledTaskRepository repository,
                                TurnProcessor turnProcessor,
                                ModelRouter modelRouter,
                                TtsService ttsService) {
        this.repository = repository;
        this.turnProcessor = turnProcessor;
        this.modelRouter = modelRouter;
        this.ttsService = ttsService;
    }

    public void setAllTools(ToolCallback[] tools) {
        this.tools = tools != null ? tools : new ToolCallback[0];
    }

    /** 启动时恢复所有启用任务，重新调度 */
    @PostConstruct
    public void restore() {
        try {
            List<ScheduledTaskNode> tasks = repository.findByEnabledTrue();
            for (ScheduledTaskNode t : tasks) {
                schedule(t);
            }
            log.info("⏰ 定时任务恢复完成: {} 个", tasks.size());
        } catch (Exception e) {
            log.warn("⚠️ 定时任务恢复失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }

    /** 注册一个每天执行的任务 */
    public String registerDailyTask(int hour, int minuteFrom, int minuteTo, String taskDescription) {
        if (hour < 0 || hour > 23) return "❌ hour 必须在 0-23";
        if (minuteFrom < 0 || minuteTo > 59 || minuteFrom > minuteTo) return "❌ 分钟范围不合法";
        if (taskDescription == null || taskDescription.isBlank()) return "❌ 任务描述不能为空";

        String taskId = UUID.randomUUID().toString();
        ScheduledTaskNode task = new ScheduledTaskNode(taskId, hour, minuteFrom, minuteTo, taskDescription);
        long next = computeNextRunAt(hour, minuteFrom, minuteTo);
        task.setNextRunAt(next);
        repository.save(task);
        schedule(task);

        return "✅ 已注册每日定时任务（id=" + taskId + "），下次执行 " +
                new SimpleDateFormat("MM-dd HH:mm").format(new Date(next)) +
                "，每天 " + hour + ":" + minuteFrom + "~" + minuteTo + " 之间随机触发";
    }

    /** 列出所有任务 */
    public String listTasks() {
        List<ScheduledTaskNode> all = repository.findAll();
        if (all == null || all.isEmpty()) return "暂无定时任务";
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm");
        StringBuilder sb = new StringBuilder("定时任务列表：\n");
        for (ScheduledTaskNode t : all) {
            sb.append("- ").append(t.getTaskId()).append("：")
              .append(t.isEnabled() ? "启用" : "停用")
              .append("，每天 ").append(t.getHour()).append(":").append(t.getMinuteFrom())
              .append("~").append(t.getMinuteTo())
              .append("，").append(t.getTaskDescription())
              .append("，下次 ").append(t.getNextRunAt() != null ? fmt.format(new Date(t.getNextRunAt())) : "-")
              .append("\n");
        }
        return sb.toString();
    }

    /** 停用一个任务 */
    public String cancelTask(String taskId) {
        Optional<ScheduledTaskNode> t = repository.findByTaskId(taskId);
        if (t.isEmpty()) return "❌ 未找到任务 " + taskId;
        t.get().setEnabled(false);
        repository.save(t.get());
        return "✅ 已停用任务 " + taskId;
    }

    // ===== 内部 =====

    private void schedule(ScheduledTaskNode task) {
        long next = task.getNextRunAt() != null ? task.getNextRunAt() : System.currentTimeMillis();
        long delay = next - System.currentTimeMillis();
        if (delay < 0) delay = 0;
        scheduler.schedule(() -> executeAndReschedule(task.getTaskId()), delay, TimeUnit.MILLISECONDS);
    }

    private void executeAndReschedule(String taskId) {
        ScheduledTaskNode task;
        try {
            task = repository.findByTaskId(taskId).orElse(null);
        } catch (Exception e) {
            log.warn("⚠️ 读取定时任务失败: {}", e.getMessage());
            return;
        }
        if (task == null || !task.isEnabled()) return;

        // 1. 执行任务描述：走主流程（规划 → 动态调用工具 → 反思沉淀）
        try {
            log.info("⏰ 定时任务触发 [{}]: {}", taskId, task.getTaskDescription());
            turnProcessor.process(modelRouter, tools, task.getTaskDescription(), ttsService);
        } catch (Exception e) {
            log.error("❌ 定时任务执行失败 [{}]: {}", taskId, e.getMessage(), e);
        }

        // 2. 更新执行时间，计算明天的下次触发时间
        task.setLastRunAt(System.currentTimeMillis());
        task.setNextRunAt(computeNextRunAt(task.getHour(), task.getMinuteFrom(), task.getMinuteTo()));
        try {
            repository.save(task);
        } catch (Exception e) {
            log.warn("⚠️ 保存定时任务下次时间失败: {}", e.getMessage());
        }

        // 3. 递归调度明天的任务
        schedule(task);
    }

    /** 计算下一次执行时间戳：在 [hour:minuteFrom, hour:minuteTo] 内随机取分钟；已过则顺延到明天 */
    private long computeNextRunAt(int hour, int minuteFrom, int minuteTo) {
        int minute = minuteFrom;
        if (minuteTo > minuteFrom) {
            minute = minuteFrom + ThreadLocalRandom.current().nextInt(minuteTo - minuteFrom + 1);
        }
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime candidate = now.toLocalDate().atTime(hour, minute).atZone(zone);
        if (!candidate.isAfter(now)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant().toEpochMilli();
    }
}
