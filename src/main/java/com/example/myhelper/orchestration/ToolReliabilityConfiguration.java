package com.example.myhelper.orchestration;

import com.example.myhelper.skill.ToolReliability;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 装配可靠度门限和累计成功次数；应用仍需把本 Bean 注入编排器，并提供技能模块的生产查询。 */
@Configuration(proxyBeanMethods = false)
public class ToolReliabilityConfiguration {
    @Bean
    public ToolReliabilityGate toolReliabilityGate(Environment environment,
                                                   ObjectProvider<ToolReliability> reliability) {
        double threshold = environment.getProperty(ToolReliabilityGate.THRESHOLD_PROPERTY,
                Double.class, ToolReliabilityGate.DEFAULT_THRESHOLD);
        long minimumSuccesses = environment.getProperty(ToolReliabilityGate.MINIMUM_SUCCESSES_PROPERTY,
                Long.class, ToolReliabilityGate.DEFAULT_MINIMUM_SUCCESSES);
        // 未接生产技能查询时按未知处理，不能使用假统计继续零模型执行。
        return new ToolReliabilityGate(reliability.getIfAvailable(), threshold, minimumSuccesses);
    }
}
