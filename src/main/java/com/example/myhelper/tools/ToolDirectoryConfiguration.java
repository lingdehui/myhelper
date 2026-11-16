package com.example.myhelper.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 从宿主配置装配活动工具和各已验证范围的先验。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ToolDirectoryConfiguration.DirectoryProperties.class)
public class ToolDirectoryConfiguration {
    @Bean
    public ToolDirectory toolDirectory(DirectoryProperties properties) {
        List<ToolDirectory.Registration> active = new ArrayList<>();
        if (properties.registrations() != null) {
            for (RegistrationProperties tool : properties.registrations()) {
                active.add(tool.toRegistration());
            }
        }
        return new ToolDirectory(active);
    }

    @ConfigurationProperties(prefix = "myhelper.tools")
    public record DirectoryProperties(
            /** 宿主启用并核对实际执行绑定的工具。 */
            List<RegistrationProperties> registrations) {
    }

    public record RegistrationProperties(
            /** 工具调用名。 */
            String toolName,
            /** 接入实例标识。 */
            String sourceInstance,
            /** 已绑定的实现版本。 */
            String implementationVersion,
            /** 已核对的来源。 */
            String source,
            /** 是否由大模型生成，配置必须显式提供。 */
            Boolean modelGenerated,
            /** 是否为固化组合，配置必须显式提供。 */
            Boolean consolidated,
            /** 是否由本项目开发，配置必须显式提供。 */
            Boolean projectDeveloped,
            /** 可选的范围先验。 */
            List<PriorProperties> priors) {
        private ToolDirectory.Registration toRegistration() {
            Map<String, ToolDirectory.Prior> byRange = new LinkedHashMap<>();
            if (priors != null) {
                for (PriorProperties prior : priors) {
                    ToolDirectory.Prior value = new ToolDirectory.Prior(
                            Objects.requireNonNull(prior.successRate(), "success-rate"),
                            Objects.requireNonNull(prior.weight(), "weight"));
                    if (byRange.putIfAbsent(prior.rangeId(), value) != null) {
                        throw new IllegalArgumentException("工具先验范围重复登记：" + prior.rangeId());
                    }
                }
            }
            return new ToolDirectory.Registration(
                    new ToolDirectory.ToolRef(toolName, sourceInstance, implementationVersion), source,
                    Objects.requireNonNull(modelGenerated, "model-generated"),
                    Objects.requireNonNull(consolidated, "consolidated"),
                    Objects.requireNonNull(projectDeveloped, "project-developed"), byRange);
        }
    }

    public record PriorProperties(
            /** 已验证范围的稳定编号。 */
            String rangeId,
            /** 初始可靠度。 */
            Double successRate,
            /** 非负等效样本数。 */
            Long weight) {
    }
}
