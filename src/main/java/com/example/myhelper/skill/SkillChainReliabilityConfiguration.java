package com.example.myhelper.skill;

import com.example.myhelper.persistence.DualWriteStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 将固化链评分和归档记账接到正式存储及已装配的范围模型。 */
@Configuration(proxyBeanMethods = false)
public class SkillChainReliabilityConfiguration {
    @Bean
    public ArchivedSkillChainReliability skillChainReliability(DualWriteStore storage, Environment environment,
                                                       ModelToolRangeCheck rangeCheck) {
        DualWriteStore.Database authority = environment.getProperty("myhelper.skill.authority",
                DualWriteStore.Database.class, DualWriteStore.Database.NEO4J);
        String namespace = environment.getProperty("myhelper.capability.namespace");
        if (namespace == null) {
            namespace = environment.getRequiredProperty("qdrant.collection");
        }
        return new ArchivedSkillChainReliability(storage, authority, namespace, rangeCheck);
    }
}
