package com.example.myhelper.skill;

import com.example.myhelper.agent.AgentCaller;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.records.RunArchivePort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.example.myhelper.tools.ToolDirectory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** 将技能业务接到共享存储、模型和工具目录。 */
@Configuration(proxyBeanMethods = false)
@Import(SkillChainReliabilityConfiguration.class)
public class SkillRuntimeConfiguration {
    @Bean
    public ToolParameterStore toolParameterStore(DualWriteStore storage) {
        return new ToolParameterStore(storage);
    }

    @Bean
    public ModelToolRangeCheck modelToolRangeCheck(AgentCaller caller, Environment environment,
                                                    @Qualifier("openAiChatModel") OpenAiChatModel client) {
        return new ModelToolRangeCheck(caller, modelRef(environment, client));
    }

    @Bean
    public ToolReliability toolReliability(ToolDirectory directory, ToolParameterStore parameters,
                                           ModelToolRangeCheck rangeCheck) {
        var evidence = new RecordedToolReliability(parameters, rangeCheck);
        return new ToolReliability(new RegisteredToolReliabilityLookup(directory, evidence));
    }

    @Bean
    public SkillConsolidationService skillConsolidationService(AgentCaller caller, Environment environment,
                                                                 @Qualifier("openAiChatModel") OpenAiChatModel client,
                                                                 SkillConsolidationService.Repository repository) {
        return new SkillConsolidationService(caller, modelRef(environment, client), repository);
    }

    @Bean
    public ArchivedToolParameterRecorder archivedToolParameterRecorder(ToolParameterStore parameters,
                                                                        ModelToolRangeCheck rangeCheck) {
        return new ArchivedToolParameterRecorder(parameters, rangeCheck);
    }

    @Bean
    public ThreadPoolTaskExecutor skillBackgroundExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("myhelper-skill-");
        return executor;
    }

    @Bean
    public ArchivedSkillProcessing archivedSkillProcessing(
            ObjectProvider<RunArchivePort> archives,
            ArchivedToolParameterRecorder recorder,
            ArchivedSkillChainReliability chains,
            ObjectProvider<CompletedRunConsolidation> consolidation,
            @Qualifier("skillBackgroundExecutor") ThreadPoolTaskExecutor executor) {
        return new ArchivedSkillProcessing(archives, recorder, chains, consolidation, executor);
    }

    private static String modelRef(Environment environment, OpenAiChatModel client) {
        String configured = environment.getProperty("myhelper.skill.model-ref");
        if (configured == null) {
            configured = environment.getProperty("myhelper.llm.model-ref");
        }
        if (configured == null) {
            configured = client.getDefaultOptions().getModel();
        }
        return configured;
    }
}
