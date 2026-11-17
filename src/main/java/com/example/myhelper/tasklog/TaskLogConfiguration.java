package com.example.myhelper.tasklog;

import com.example.myhelper.tasklog.TaskLogPort.Event;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 将实时日志接入默认应用输出，沿用 Spring 的日志路由和级别配置。 */
@Configuration(proxyBeanMethods = false)
public class TaskLogConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "myhelper", name = "ui", havingValue = "desktop")
    TaskLogBuffer desktopTaskLog(Environment environment, ObjectProvider<TaskLogPort> log) {
        int capacity = environment.getProperty("myhelper.task-log.replay-capacity", Integer.class, 512);
        return new TaskLogBuffer(capacity, new QueueDrops(log));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(TaskLogPort.class)
    @ConditionalOnProperty(prefix = "myhelper.task-log", name = "enabled", matchIfMissing = true)
    AsyncTaskLog taskLog(Environment environment, ObjectProvider<TaskLogPort.Listener> listeners) {
        int capacity = environment.getProperty("myhelper.task-log.queue-capacity", Integer.class, 512);
        return new AsyncTaskLog(capacity, new ApplicationLog(listeners));
    }

    private record QueueDrops(
            /** 发布方也依赖订阅端，查询计数时再获取它。 */
            ObjectProvider<TaskLogPort> log) implements LongSupplier {
        @Override
        public long getAsLong() {
            if (log.getIfAvailable() instanceof AsyncTaskLog async) {
                return async.droppedCount();
            }
            return 0;
        }
    }

    private static final class ApplicationLog implements Consumer<Event> {
        /** 运行观测统一使用固定日志分类。 */
        private static final System.Logger LOGGER = System.getLogger("myhelper.tasklog");
        /** 延迟获取订阅方，允许应用视图同时依赖任务运行时。 */
        private final ObjectProvider<TaskLogPort.Listener> listeners;

        private ApplicationLog(ObjectProvider<TaskLogPort.Listener> listeners) {
            this.listeners = listeners;
        }

        @Override
        public void accept(Event event) {
            LOGGER.log(System.Logger.Level.INFO, event.line());
            boolean failed = false;
            for (TaskLogPort.Listener listener : listeners) {
                try {
                    listener.onEvent(event);
                } catch (RuntimeException ignored) {
                    failed = true;
                }
            }
            if (failed) {
                // 继续调用其他订阅者，再由异步投递器统一统计本条消费失败。
                throw new IllegalStateException("任务日志订阅处理失败");
            }
        }
    }
}
