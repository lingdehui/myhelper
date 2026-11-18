package com.example.myhelper.world;

import com.example.myhelper.orchestration.WorldContextPort;
import com.example.myhelper.persistence.DualWriteStore;
import java.time.Clock;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 默认将业务状态写入支持条件写的图存储。上下文组装需宿主提供明确的访问选择。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(DualWriteStore.class)
public class WorldModelConfiguration {
    @Bean
    public WorldRepository worldRepository(DualWriteStore store) {
        return new WorldRepository(store, DualWriteStore.Database.NEO4J, Clock.systemUTC());
    }

    @Bean
    public ContextService worldContextService(WorldRepository repository) {
        return new ContextService(repository);
    }

    @Bean
    public WorldDeviceDirectory worldDeviceDirectory(DualWriteStore store) {
        return new WorldDeviceDirectory(store, DualWriteStore.Database.NEO4J);
    }

    @Bean
    public WorldDeviceStatus worldDeviceStatus(WorldDeviceDirectory directory, WorldRepository repository,
            ContextService context) {
        return new WorldDeviceStatus(directory, repository, context, Clock.systemUTC());
    }

    @Bean
    public WorldConditions worldConditions(WorldRepository repository, ContextService service) {
        return new WorldConditions(repository, service);
    }

    @Bean
    public WorldReferenceExpander worldReferenceExpander(DualWriteStore store,
            ObjectProvider<WorldReferenceExpander.SourceResolver> resolvers) {
        var resolver = resolvers.getIfAvailable();
        return new WorldReferenceExpander(store, resolver == null ? new NoReferenceSource() : resolver);
    }

    @Bean
    @ConditionalOnMissingBean(WorldContextPort.class)
    public WorldContextPort worldPlanningContext(ContextService service, WorldReferenceExpander expander,
            ObjectProvider<WorldPlanningContext.Selector> selectors) {
        var selector = selectors.getIfAvailable();
        if (selector == null) {
            return WorldContextPort.disabled();
        }
        return new WorldPlanningContext(service, expander, selector, Clock.systemUTC());
    }

    /** 跨实体位置没有明确映射时，不扩大根引用的选库范围。 */
    private static final class NoReferenceSource implements WorldReferenceExpander.SourceResolver {
        @Override
        public Optional<DualWriteStore.Database> resolve(WorldReferenceExpander.LocatedRef owner,
                String slot, com.example.myhelper.world.model.EntityRef target) {
            return Optional.empty();
        }
    }
}
