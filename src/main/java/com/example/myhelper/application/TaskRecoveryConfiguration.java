package com.example.myhelper.application;

import com.example.myhelper.interaction.TaskEntry;
import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.persistence.Neo4jEntityStore;
import com.example.myhelper.persistence.PersistenceConfiguration;
import com.example.myhelper.records.RecordsConfiguration;
import com.example.myhelper.records.StoredTaskRecords;
import com.example.myhelper.records.TaskRecordOwner;
import com.example.myhelper.records.TaskRecordPort;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** 正式应用默认启用持久任务；数据库故障明确失败，不退回内存记录。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "myhelper.records.enabled", havingValue = "true", matchIfMissing = true)
@Import({PersistenceConfiguration.class, RecordsConfiguration.class})
public class TaskRecoveryConfiguration {
    /** 每批默认只恢复少量任务，等待真实收尾后继续下一页。 */
    private static final int DEFAULT_RECOVERY_BATCH_SIZE = 4;
    /** 与执行图装配的缺省容量一致，用于防止启动批次挤满图仓库。 */
    private static final int DEFAULT_TRACE_MAX_RUNS = 64;

    @Bean(destroyMethod = "")
    public TaskProcessLock taskProcessLock(Environment environment) throws IOException {
        String configured = environment.getProperty("myhelper.records.state-directory");
        Path directory = configured == null
                ? Path.of(System.getProperty("user.home"), ".myhelper", "task-runtime") : Path.of(configured);
        return TaskProcessLock.holdForProcess(directory);
    }

    @Bean
    public TaskRecordOwner taskRecordOwner(TaskProcessLock owner) {
        // 记录模块只接收稳定身份；取得独占锁仍属于应用的启动职责。
        return new TaskRecordOwner(owner.ownerId());
    }

    @Bean
    public TaskRecoveryService taskRecoveryService(AgentOrchestrator orchestrator, TaskRecordPort records) {
        return new TaskRecoveryService(orchestrator, records);
    }

    @Bean
    public StartupTaskRecovery startupTaskRecovery(TaskProcessLock owner, StoredTaskRecords records,
            TaskRecoveryService service, TaskEntry entry, Neo4jEntityStore graph, TaskActivity activity,
            Environment environment) {
        int batch = environment.getProperty("myhelper.records.recovery-batch-size", Integer.class,
                DEFAULT_RECOVERY_BATCH_SIZE);
        int capacity = environment.getProperty("myhelper.orchestration.trace-max-runs", Integer.class,
                DEFAULT_TRACE_MAX_RUNS);
        if (batch > capacity) {
            throw new IllegalArgumentException("恢复批量不能超过执行图运行容量");
        }
        return new StartupTaskRecovery(owner, records, service, entry, graph, batch, activity);
    }
}
