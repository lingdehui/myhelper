package com.example.myhelper.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class EnvironmentStateServiceTest {
    @Test void observationsAreHistoricalButShareOneCanonicalState() {
        // persist=false 时仓储不可用会自动退回内存世界模型，适合验证纯状态语义。
        EnvironmentStateService service = new EnvironmentStateService(null, null, new ObjectMapper());

        ContextUnit first = service.observe("device:ac", "power", ContextUnit.Operator.EQ,
                null, "ON", "sensor-a", ContextUnit.Origin.OBSERVED,
                Instant.parse("2026-09-02T00:00:00Z"), null, 0.9, null, false);
        ContextUnit second = service.observe("device:ac", "power", ContextUnit.Operator.EQ,
                null, "ON", "sensor-b", ContextUnit.Origin.OBSERVED,
                Instant.parse("2026-09-02T00:01:00Z"), null, 0.95, null, false);

        assertNotEquals(first.id(), second.id());
        assertEquals(first.stateId(), second.stateId());
        assertEquals("state:device:ac:power:ON", first.stateId());
        assertEquals(second.id(), service.findCurrentValue("device:ac", "power").orElseThrow().id());
        assertEquals("ON", service.findCurrentState("device:ac", "power").orElseThrow().literalValue());
    }
}
