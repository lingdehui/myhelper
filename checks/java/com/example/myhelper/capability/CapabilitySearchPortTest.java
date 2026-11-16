package com.example.myhelper.capability;

import com.example.myhelper.orchestration.TaskPlan;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

/** 核对未启用与外部故障的区别，不访问真实数据库。 */
class CapabilitySearchPortTest {
    @Test
    void disabledPortReturnsCompleteEmptyMatchesAndNeverApprovesExecution() {
        CapabilitySearchPort port = CapabilitySearchPort.disabled();
        assertFalse(port.enabled());
        CapabilityLookup.Match entry = port.searchAtEntry(query());
        assertTrue(entry.candidates().isEmpty());
        assertTrue(entry.selected().isEmpty());
        CapabilityLookup.PlanMatches matches = port.searchAfterPlanning("run", 1, plan(), Map.of());
        assertTrue(matches.whole().selected().isEmpty());
        assertEquals(2, matches.steps().size());
        for (String stepId : List.of("one", "two")) {
            assertTrue(matches.steps().get(stepId).candidates().isEmpty());
            assertTrue(matches.steps().get(stepId).selected().isEmpty());
        }
        assertFalse(port.isAvailable(new CapabilityLookup.Ref("test", "unit", 1)));
        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            public void execute() {
                matches.steps().remove("one");
            }
        });
    }

    @Test
    void enabledLookupPropagatesExternalFailuresInsteadOfReturningDisabledMatches() {
        IllegalStateException failure = new IllegalStateException("external unavailable");
        CapabilitySearchPort port = new CapabilityLookup(new FailingRepository(failure), new NeverMatch(), 1, Duration.ofSeconds(1));
        assertTrue(port.enabled());
        assertSame(failure, assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                port.searchAtEntry(query());
            }
        }));
        assertSame(failure, assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                port.searchAfterPlanning("run", 1, plan(), Map.of());
            }
        }));
        assertSame(failure, assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() {
                port.isAvailable(new CapabilityLookup.Ref("test", "unit", 1));
            }
        }));
    }

    private static CapabilityLookup.Query query() {
        return new CapabilityLookup.Query("run", 0, CapabilityLookup.ENTRY_QUERY_ID, "goal", Map.of());
    }

    private static TaskPlan plan() {
        return new TaskPlan("goal", List.of(
                new TaskPlan.Step("one", "first", TaskPlan.Kind.TASK, null, Map.of(), List.of(), Map.of()),
                new TaskPlan.Step("two", "second", TaskPlan.Kind.TASK, null, Map.of(), List.of("one"), Map.of())));
    }

    private record FailingRepository(IllegalStateException failure) implements CapabilityLookup.Repository {
        @Override
        public List<CapabilityLookup.Candidate> search(CapabilityLookup.Query query) {
            throw failure;
        }

        @Override
        public Optional<CapabilityLookup.Definition> load(CapabilityLookup.Ref ref) {
            throw failure;
        }

        @Override
        public boolean isAvailable(CapabilityLookup.Ref ref) {
            throw failure;
        }
    }

    private static final class NeverMatch implements CapabilityLookup.Matcher {
        @Override
        public boolean matches(CapabilityLookup.Query query, CapabilityLookup.Candidate candidate) {
            return false;
        }
    }
}
