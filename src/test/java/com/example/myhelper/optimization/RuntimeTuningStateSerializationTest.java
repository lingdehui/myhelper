package com.example.myhelper.optimization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 审计状态必须能跨重启序列化和恢复，避免试验在重启后失去回滚基线。 */
class RuntimeTuningStateSerializationTest {

    @Test
    void persistsOverridesAuditAndPendingTrial() throws Exception {
        RuntimeTuningService.PersistedState state = new RuntimeTuningService.PersistedState();
        state.overrides.put(RuntimeTuningService.Parameter.TOOL_CACHE_SIZE.key(), 625.0);
        state.audit.add(new RuntimeTuningService.AuditRecord(1L,
                RuntimeTuningService.Parameter.TOOL_CACHE_SIZE.key(), 500, 625, "APPLIED", "test", "sandbox.yml"));
        state.pendingTrial = new RuntimeTuningService.Trial(RuntimeTuningService.Parameter.TOOL_CACHE_SIZE.key(),
                500, 625, "test", 1L,
                new RuntimeMetricsService.Snapshot(50, 5, 1_000, 0, 0, 50, 10,
                        20, 18, 2, 0, 0, 0, 0), "sandbox.yml", false);

        ObjectMapper mapper = new ObjectMapper();
        RuntimeTuningService.PersistedState restored = mapper.readValue(
                mapper.writeValueAsBytes(state), RuntimeTuningService.PersistedState.class);

        assertEquals(625.0, restored.overrides.get(RuntimeTuningService.Parameter.TOOL_CACHE_SIZE.key()));
        assertEquals(1, restored.audit.size());
        assertNotNull(restored.pendingTrial);
        assertEquals(RuntimeTuningService.Parameter.TOOL_CACHE_SIZE.key(), restored.pendingTrial.parameterPath());
    }
}
