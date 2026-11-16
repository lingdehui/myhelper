package com.example.myhelper.tools;

import com.example.myhelper.orchestration.ToolReliabilityConfiguration;
import com.example.myhelper.orchestration.ToolReliabilityGate;
import com.example.myhelper.skill.RegisteredToolReliabilityLookup;
import com.example.myhelper.skill.ToolReliability;
import com.example.myhelper.world.model.ExecutionStatistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDirectoryConfigurationTest {
    /** 登记项由 Spring 配置实际绑定。 */
    private static final String REGISTRATION = "myhelper.tools.registrations[0].";

    @Test
    void configuredSourceAndPriorReachTheExistingSpringGate() {
        try (var context = configuredContext(properties())) {
            context.register(EvidenceConfiguration.class, ToolReliabilityConfiguration.class);
            context.refresh();

            var registration = context.getBean(ToolDirectory.class).find("convert").orElseThrow();
            assertEquals("upstream-converter", registration.source());
            assertEquals("external-instance", registration.ref().sourceInstance());
            assertEquals("v2", registration.ref().implementationVersion());
            var gate = context.getBean(ToolReliabilityGate.class);
            var decision = gate.evaluate("convert", Map.of("format", "csv"));
            assertTrue(decision.direct());
            assertEquals(0.9, decision.score(), 1e-9);
            assertFalse(gate.evaluate("convert", Map.of("format", "other")).direct());
        }
    }

    @Test
    void missingOriginFlagCannotDefaultToTrusted() {
        Map<String, Object> properties = properties();
        properties.remove(REGISTRATION + "model-generated");
        try (var context = configuredContext(properties)) {
            assertThrows(BeansException.class, context::refresh);
        }
    }

    @Test
    void absentRegistrationConfigCreatesAnEmptyDirectory() {
        try (var context = configuredContext(Map.of())) {
            context.refresh();
            assertTrue(context.getBean(ToolDirectory.class).find("convert").isEmpty());
        }
    }

    private static AnnotationConfigApplicationContext configuredContext(Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("tool-registry", properties));
        context.register(ToolDirectoryConfiguration.class);
        return context;
    }

    private static Map<String, Object> properties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(REGISTRATION + "tool-name", "convert");
        properties.put(REGISTRATION + "source-instance", "external-instance");
        properties.put(REGISTRATION + "implementation-version", "v2");
        properties.put(REGISTRATION + "source", "upstream-converter");
        properties.put(REGISTRATION + "model-generated", "false");
        properties.put(REGISTRATION + "consolidated", "false");
        properties.put(REGISTRATION + "project-developed", "false");
        properties.put(REGISTRATION + "priors[0].range-id", "csv");
        properties.put(REGISTRATION + "priors[0].success-rate", "0.9");
        properties.put(REGISTRATION + "priors[0].weight", "4");
        return properties;
    }

    @Configuration(proxyBeanMethods = false)
    static class EvidenceConfiguration {
        @Bean
        ToolReliability toolReliability(ToolDirectory directory) {
            return new ToolReliability(new RegisteredToolReliabilityLookup(directory, new CsvEvidenceLookup()));
        }
    }

    private static final class CsvEvidenceLookup implements RegisteredToolReliabilityLookup.VerifiedEvidenceLookup {
        @Override
        public Optional<RegisteredToolReliabilityLookup.VerifiedEvidence> findInVerifiedRange(
                ToolDirectory.ToolRef ref, Map<String, Object> arguments) {
            if (!Map.of("format", "csv").equals(arguments)) {
                return Optional.empty();
            }
            return Optional.of(new RegisteredToolReliabilityLookup.VerifiedEvidence(
                    ref, ExecutionStatistics.empty(), 0, "csv"));
        }
    }
}
