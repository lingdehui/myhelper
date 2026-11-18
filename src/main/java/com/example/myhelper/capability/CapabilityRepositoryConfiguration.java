package com.example.myhelper.capability;

import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.skill.SkillChainReliability;
import com.example.myhelper.world.model.Embedding;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 装配共享嵌入和双写存储上的能力检索仓储。 */
@Configuration(proxyBeanMethods = false)
public class CapabilityRepositoryConfiguration {
    /** 内容缓存默认上限；与数据库召回数量分别控制。 */
    private static final int DEFAULT_CACHE_CAPACITY = 256;
    /** 缓存只保存固定定义，发布资格及评分每次选择重新查询。 */
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);

    @Bean
    @ConditionalOnBean(CapabilityLookup.Matcher.class)
    @ConditionalOnMissingBean(CapabilitySearchPort.class)
    public CapabilityLookup capabilityLookup(CapabilityLookup.Repository repository, CapabilityLookup.Matcher matcher,
            ObjectProvider<SkillChainReliability> scoring, Environment environment) {
        int capacity = environment.getProperty("myhelper.capability.cache-capacity", Integer.class,
                DEFAULT_CACHE_CAPACITY);
        Duration ttl = environment.getProperty("myhelper.capability.cache-ttl", Duration.class, DEFAULT_CACHE_TTL);
        SkillChainReliability reliability = scoring.getIfAvailable();
        if (reliability == null) {
            return new CapabilityLookup(repository, matcher, capacity, ttl);
        }
        return new CapabilityLookup(repository, matcher, capacity, ttl, reliability);
    }

    @Bean
    @ConditionalOnMissingBean(CapabilityLookup.Repository.class)
    public QdrantCapabilityRepository qdrantCapabilityRepository(DualWriteStore store, Environment environment,
            @Qualifier("textEmbedding") Function<String, Embedding> embedding) {
        String namespace = environment.getProperty("myhelper.capability.namespace");
        if (namespace == null) {
            namespace = environment.getRequiredProperty("qdrant.collection");
        }
        // 固化技能写入与定义读取使用同一个权威来源配置。
        DualWriteStore.Database authority = environment.getProperty(
                "myhelper.skill.authority", DualWriteStore.Database.class, DualWriteStore.Database.NEO4J);
        int dimensions = environment.getRequiredProperty("qdrant.vector-size", Integer.class);
        int limit = environment.getProperty("myhelper.capability.limit", Integer.class, 20);
        double scoreThreshold = environment.getProperty("myhelper.capability.score-threshold", Double.class, 0.0);
        var config = new QdrantCapabilityRepository.SearchConfig(namespace, authority, dimensions, limit, scoreThreshold);
        return QdrantCapabilityRepository.withTextEmbedding(store, config, embedding);
    }
}
