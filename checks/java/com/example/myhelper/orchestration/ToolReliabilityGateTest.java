package com.example.myhelper.orchestration;

import com.example.myhelper.skill.ToolReliability;
import com.example.myhelper.world.model.ExecutionStatistics;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;
import static org.junit.jupiter.api.Assertions.*;

class ToolReliabilityGateTest {
    @Test
    void strictThresholdUsesSkillScoreIncludingExternalFailureAttribution() {
        assertFalse(gate(5, 95, 0, 0.05).evaluate("echo", Map.of()).direct());
        assertTrue(gate(6, 94, 0, 0.05).evaluate("echo", Map.of()).direct());
        assertFalse(gate(10, 5, 0, 0.70).evaluate("echo", Map.of()).direct());
        assertTrue(gate(10, 5, 1, 0.70).evaluate("echo", Map.of()).direct());
        assertEquals(1.0, gate(150, 0, 0, 0.05).evaluate("echo", Map.of()).score());
        assertFalse(gate(0, 5, 0, 0.05).evaluate("echo", Map.of()).direct());
        assertNull(gate(0, 0, 0, 0.05).evaluate("echo", Map.of()).score());
        assertNull(gate(0, 5, 5, 0.05).evaluate("echo", Map.of()).score());
    }

    @Test
    void priorAppliesAtColdStartAndUpdatesWithActualObservations() {
        var prior = new ToolReliability.Prior(0.95, 20);
        var cold = new ToolReliability.Sample(ExecutionStatistics.empty(), 0, prior);
        var observed = new ToolReliability.Sample(new ExecutionStatistics(8, 2, List.of()), 1, prior);
        assertEquals(0.95, reliability(cold).score("echo", Map.of()).orElseThrow(), 1e-12);
        assertEquals(27.0 / 29.0, reliability(observed).score("echo", Map.of()).orElseThrow(), 1e-12);
        assertEquals(0.75, reliability(150, 50, 0).score("echo", Map.of()).orElseThrow(), 1e-12);
    }

    @Test
    void ordinarySkillsNeedBothActualSuccessesAndAHighEnoughScore() {
        assertFalse(gate(4, 0, 0, 0.85).evaluate("echo", Map.of()).direct());
        assertTrue(gate(5, 0, 0, 0.85).evaluate("echo", Map.of()).direct());
        assertFalse(gate(5, 1, 0, 0.85).evaluate("echo", Map.of()).direct());
        assertTrue(gate(6, 1, 0, 0.85).evaluate("echo", Map.of()).direct());
        assertFalse(gate(17, 3, 0, 0.85).evaluate("echo", Map.of()).direct());
    }

    @Test
    void trustedAtomicToolSkipsCountButStillNeedsScore() {
        var prior = new ToolReliability.Prior(0.95, 5);
        var confirmedFailure = new ExecutionStatistics(0, 1, List.of());
        var cold = new ToolReliability.Sample(ExecutionStatistics.empty(), 0, prior, true);
        var methodFailure = new ToolReliability.Sample(confirmedFailure, 0, prior, true);
        var externalFailure = new ToolReliability.Sample(confirmedFailure, 1, prior, true);
        var ordinaryWithPrior = new ToolReliability.Sample(ExecutionStatistics.empty(), 0, prior);
        assertTrue(new ToolReliabilityGate(reliability(cold), 0.85).evaluate("echo", Map.of()).direct());
        assertFalse(new ToolReliabilityGate(reliability(methodFailure), 0.85).evaluate("echo", Map.of()).direct());
        assertTrue(new ToolReliabilityGate(reliability(externalFailure), 0.85).evaluate("echo", Map.of()).direct());
        assertFalse(new ToolReliabilityGate(reliability(ordinaryWithPrior), 0.85).evaluate("echo", Map.of()).direct());
    }

    @Test
    void missingAndFailedQueriesDoNotApproveDirectExecution() {
        assertFalse(ToolReliabilityGate.unavailable().evaluate("echo", Map.of()).direct());
        for (boolean fail : List.of(false, true)) {
            ToolReliability lookup = new ToolReliability(new ToolReliability.Lookup() {
                @Override
                public Optional<ToolReliability.Sample> findInVerifiedRange(String tool, Map<String, Object> args) {
                    if (fail) { throw new IllegalStateException("query unavailable"); }
                    return Optional.empty();
                }
            });
            ToolReliabilityGate.Decision decision = new ToolReliabilityGate(lookup, 0.05).evaluate("echo", Map.of());
            assertFalse(decision.direct());
            assertNull(decision.score());
            assertNotNull(decision.reason());
            assertNull(decision.asMap().get("score"));
        }
    }

    @Test
    void configurationReadsYamlAndCanOverrideThresholdWithoutAQueryBean() {
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new FileSystemResource("src/main/resources/application.yml"));
        for (double expected : List.of(0.85, 0.20)) {
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(
                        new PropertiesPropertySource("applicationYaml", loader.getObject()));
                if (expected == 0.20) {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                            "override", Map.of(ToolReliabilityGate.THRESHOLD_PROPERTY, expected)));
                }
                context.register(ToolReliabilityConfiguration.class);
                context.refresh();
                ToolReliabilityGate.Decision decision = context.getBean(ToolReliabilityGate.class)
                        .evaluate("echo", Map.of());
                assertEquals(expected, decision.threshold());
                assertFalse(decision.direct());
            }
        }
    }

    @Test
    void configurationUsesTheProvidedSkillQueryBean() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "override", Map.of(ToolReliabilityGate.THRESHOLD_PROPERTY, 0.90,
                            ToolReliabilityGate.MINIMUM_SUCCESSES_PROPERTY, 3)));
            context.getBeanFactory().registerSingleton("skillReliability", reliability(3, 0, 0));
            context.register(ToolReliabilityConfiguration.class);
            context.refresh();
            assertTrue(context.getBean(ToolReliabilityGate.class).evaluate("echo", Map.of()).direct());
        }
    }

    @Test
    void invalidThresholdsAreRejectedAtConstruction() {
        for (double invalid : List.of(-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException.class, new Executable() {
                @Override
                public void execute() {
                    new ToolReliabilityGate(null, invalid);
                }
            });
        }
    }

    private static ToolReliabilityGate gate(long success, long failure, long external, double threshold) {
        return new ToolReliabilityGate(reliability(success, failure, external), threshold);
    }

    private static ToolReliability reliability(long success, long failure, long external) {
        return reliability(new ToolReliability.Sample(new ExecutionStatistics(success, failure, List.of()), external));
    }

    private static ToolReliability reliability(ToolReliability.Sample sample) {
        return new ToolReliability(new ToolReliability.Lookup() {
            @Override
            public Optional<ToolReliability.Sample> findInVerifiedRange(String tool, Map<String, Object> args) {
                return Optional.of(sample);
            }
        });
    }
}
