package com.example.myhelper.interaction.desktop;

import com.example.myhelper.agent.AgentCaller;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlanner;
import com.example.myhelper.interaction.desktop.behavior.ModelBehaviorPlanner;
import com.example.myhelper.llm.ModelClientConfiguration;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 在启动恢复前为桌面模式安装最终图摘要缓存。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "myhelper", name = "ui", havingValue = "desktop")
public class DesktopConfiguration {
    @Bean
    DesktopRecentTraces desktopRecentTraces() {
        return new DesktopRecentTraces(32);
    }

    @Bean
    BehaviorPlanner desktopBehaviorPlanner(AgentCaller caller, @Qualifier("openAiChatModel") OpenAiChatModel client,
                                           Environment environment) {
        return new ModelBehaviorPlanner(caller, ModelClientConfiguration.configuredModelRef(environment, client));
    }
}
