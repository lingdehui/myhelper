package com.example.myhelper.orchestration;

import com.example.myhelper.skill.RegisteredToolReliabilityLookup;
import com.example.myhelper.skill.ToolReliability;
import com.example.myhelper.tools.ToolDirectory;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import com.example.myhelper.world.model.ExecutionStatistics;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.*;

class SpringToolBindingTest {
    @Test void actualCallbackIdentityIsUsedForScoringDispatchAndEvidence() {
        ToolRef active = new ToolRef("echo", "instance", "2");
        ToolRef old = new ToolRef("echo", "instance", "1");
        AtomicInteger calls = new AtomicInteger();
        SpringToolExecutor actions = SpringToolExecutor.withBindings(
                List.of(new ToolDirectory.Binding(active, new Echo(calls))), new NoResources(), 1);
        AtomicReference<ToolRef> queried = new AtomicReference<>();
        RegisteredToolReliabilityLookup lookup = new RegisteredToolReliabilityLookup(
                new ToolDirectory(List.of(new ToolDirectory.Registration(active, "source", false, false, true))),
                new RegisteredToolReliabilityLookup.VerifiedEvidenceLookup() {
                    @Override public Optional<RegisteredToolReliabilityLookup.VerifiedEvidence> findInVerifiedRange(
                            ToolRef ref, Map<String, Object> arguments) {
                        queried.set(ref);
                        return Optional.of(new RegisteredToolReliabilityLookup.VerifiedEvidence(
                                ref, new ExecutionStatistics(6, 0, List.of()), 0, "exact"));
                    }
                });
        ToolReliabilityGate gate = new ToolReliabilityGate(new ToolReliability(lookup), 0.85);
        Map<String, Object> arguments = Map.of("value", 2);
        assertFalse(gate.evaluate(old, arguments).direct());
        assertNull(queried.get());
        ToolRef resolved = actions.resolveTool("echo").orElseThrow();
        assertTrue(gate.evaluate(resolved, arguments).direct());
        assertEquals(active, queried.get());
        AtomicReference<Map<String, Object>> binding = new AtomicReference<>();
        AgentOrchestrator.CallContext context = new AgentOrchestrator.CallContext("root", "run", "node",
                Instant.now().plusSeconds(30), new AtomicReference<>(), "task",
                new BiConsumer<String, Map<String, Object>>() {
                    @Override public void accept(String phase, Map<String, Object> evidence) {
                        assertEquals("TOOL_BINDING", phase);
                        binding.set(evidence);
                    }
                });
        assertEquals(AgentOrchestrator.Status.FAILED, actions.execute(old, arguments, context).status());
        assertEquals(0, calls.get());
        assertNull(binding.get());
        assertEquals(AgentOrchestrator.Status.SUCCESS, actions.execute(resolved, arguments, context).status());
        assertEquals(1, calls.get());
        assertEquals(Map.of("toolName", "echo", "sourceInstance", "instance", "implementationVersion", "2"), binding.get());
    }

    private static final class Echo implements ToolCallback {
        /** 实际回调次数。 */
        private final AtomicInteger calls;

        private Echo(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("echo").description("echo").inputSchema("{}").build();
        }

        @Override public String call(String input) {
            calls.incrementAndGet();
            return input;
        }
    }

    private static final class NoResources implements SpringToolExecutor.ResourceResolver {
        @Override public List<String> keys(String tool, Map<String, Object> arguments) {
            return List.of();
        }
    }
}
