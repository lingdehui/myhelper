package com.example.myhelper.skill;

import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.world.model.Embedding;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 将候选仓储接到共享存储和模型模块的文本嵌入入口，不另建数据库或模型客户端。 */
@Configuration(proxyBeanMethods = false)
public class SkillRepositoryConfiguration {
    /** 单例发布仓储保持同一应用内的发布与补偿顺序。 */
    @Bean
    // 应用显式提供另一个仓储时不重复注册接口实现。
    @ConditionalOnMissingBean(SkillConsolidationService.Repository.class)
    public StoredSkillRepository storedSkillRepository(DualWriteStore store, Environment environment,
            @Qualifier("textEmbedding") Function<String, Embedding> embedding) {
        // 存储位置在业务装配层配置，不进入数据库模块类型路由。
        DualWriteStore.Database authority = environment.getProperty("myhelper.skill.authority",
                DualWriteStore.Database.class, DualWriteStore.Database.NEO4J);
        // 明确独立的索引目标。
        DualWriteStore.Database index = environment.getProperty("myhelper.skill.index",
                DualWriteStore.Database.class, DualWriteStore.Database.QDRANT);
        // 共享真正的文本向量生成器，发布失败沿用仓储的重试状态。
        return new StoredSkillRepository(store, authority, index, new CandidateEmbedding(embedding));
    }

    private record CandidateEmbedding(
            /** 由模型模块提供，同候选查询复用模型、版本和维度。 */
            Function<String, Embedding> embedding) implements Function<SkillConsolidationService.Candidate, Embedding> {
        @Override
        public Embedding apply(SkillConsolidationService.Candidate candidate) {
            // 使用可复用技能描述建立语义入口，实际计划仍完整保存在发布实体中。
            return embedding.apply(candidate.description().name() + "\n" + candidate.description().summary());
        }
    }
}
