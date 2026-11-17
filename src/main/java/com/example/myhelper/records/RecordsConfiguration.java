package com.example.myhelper.records;

import com.example.myhelper.persistence.DualWriteStore;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 正式记录、指令和归档端口共用同一个 Neo4j 任务聚合仓储。 */
@Configuration(proxyBeanMethods = false)
public class RecordsConfiguration {
    @Bean
    @ConditionalOnMissingBean(StoredTaskRecords.class)
    public StoredTaskRecords storedTaskRecords(DualWriteStore store, TaskRecordOwner owner,
            ObjectProvider<StoredTaskRecords.ReviewPolicy> policies,
            ObjectProvider<ToolReplayPolicy> replayPolicies,
            ObjectProvider<RunArchivePort.Listener> listeners) {
        StoredTaskRecords.ReviewPolicy policy = policies.getIfAvailable();
        if (policy == null) {
            policy = StoredTaskRecords.retainingReviews();
        }
        ToolReplayPolicy replayPolicy = replayPolicies.getIfAvailable();
        if (replayPolicy == null) {
            replayPolicy = ToolReplayPolicy.safeDefault();
        }
        return new StoredTaskRecords(store, policy, new NotifyAfterCommit(listeners), owner.id(), replayPolicy);
    }

    private static final class NotifyAfterCommit implements Consumer<RunArchivePort.ArchiveRef> {
        /** 延迟获取监听器，避免仓储和归档消费者构造循环。 */
        private final ObjectProvider<RunArchivePort.Listener> listeners;
        private NotifyAfterCommit(ObjectProvider<RunArchivePort.Listener> listeners) {
            this.listeners = listeners;
        }
        @Override
        public void accept(RunArchivePort.ArchiveRef ref) {
            for (RunArchivePort.Listener listener : listeners) {
                listener.afterCommit(ref);
            }
        }
    }
}
