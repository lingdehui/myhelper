package com.example.myhelper.world;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextUnitTest {
    @Test void confidenceDecaysAndExpires() {
        Instant observed = Instant.parse("2026-09-01T00:00:00Z");
        ContextUnit unit = new ContextUnit("state:x", ContextUnit.Role.OBSERVATION, "device:x", "online",
                ContextUnit.Operator.EQ, null, true, "state:device:x:online:true", "test", ContextUnit.Origin.OBSERVED,
                observed, observed.plusSeconds(100), 0.8, 0, 0, null, List.of());
        assertEquals(0.4, unit.effectiveConfidence(observed.plusSeconds(50)), 0.001);
        assertTrue(unit.isExpired(observed.plusSeconds(101)));
    }

    @Test void requirementIsNotAWorldValue() {
        ContextUnit unit = new ContextUnit("req:x", ContextUnit.Role.REQUIREMENT, "device:x", "online",
                ContextUnit.Operator.EQ, null, true, "state:device:x:online:true", "test", ContextUnit.Origin.SYSTEM_DEFINED,
                Instant.now(), null, 1, 0.9, 30_000, "unit:refresh", List.of());
        assertFalse(unit.isWorldValue());
        assertDoesNotThrow(() -> WorldModelPolicy.requireValidContext(unit));
    }

    @Test void stateMustPointToItself() {
        ContextUnit state = new ContextUnit("state:device:x:power:ON", ContextUnit.Role.STATE,
                "device:x", "power", ContextUnit.Operator.EQ, null, "ON",
                "state:device:x:power:ON", "catalog", ContextUnit.Origin.SYSTEM_DEFINED,
                null, null, 1, 0, 0, null, List.of());
        assertTrue(state.isStateDefinition());
        assertDoesNotThrow(() -> WorldModelPolicy.requireValidContext(state));
    }
}
