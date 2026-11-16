package com.example.myhelper.capability;

import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.world.model.Embedding;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 装配共享嵌入和双写存储上的能力检索仓储。 */
@Configuration(proxyBeanMethods = false)
public class CapabilityRepositoryConfiguration {
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
