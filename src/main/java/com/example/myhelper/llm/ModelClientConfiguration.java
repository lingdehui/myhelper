package com.example.myhelper.llm;

import com.example.myhelper.agent.AgentCaller;
import java.util.List;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/** 复用 Spring AI 的连接配置，为循环和单次调用装配同一个模型目录。 */
@Configuration(proxyBeanMethods = false)
public class ModelClientConfiguration {
    @Bean
    public ConfiguredModelAdapter configuredModelAdapter(
            @Qualifier("openAiChatModel") OpenAiChatModel configuredClient,
            ObjectProvider<ModelRoutingProvider> routingProvider,
            Environment environment) {
        String modelRef = environment.getProperty("myhelper.llm.model-ref");
        if (modelRef == null) {
            modelRef = configuredClient.getDefaultOptions().getModel();
        }
        boolean supportsTools = environment.getProperty("myhelper.llm.supports-tools", Boolean.class, true);
        // 沿用已配置的连接和模型选项。单次调用发生错误时交给编排层处理。
        OpenAiChatModel client = configuredClient.mutate()
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .build();
        ConfiguredModelAdapter.ModelConfig model = new ConfiguredModelAdapter.ModelConfig(
                modelRef, client, true, supportsTools);
        return new ConfiguredModelAdapter(List.of(model), routingProvider.getIfAvailable());
    }

    @Bean
    public AgentCaller agentCaller(ConfiguredModelAdapter adapter, ObjectProvider<ToolCallback> callbacks) {
        return new AgentCaller(adapter, callbacks.orderedStream().toList());
    }
}
