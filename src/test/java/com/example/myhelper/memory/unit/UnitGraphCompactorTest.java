package com.example.myhelper.memory.unit;

import com.example.myhelper.memory.vector.episode.ToolCallLog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnitGraphCompactorTest {

    private final UnitGraphCompactor compactor = new UnitGraphCompactor();

    @Test
    void foldsLongestExistingPlanAndBindsItsPublicParameter() {
        var fragment = new UnitStore.PlanFragment("identify-ac", List.of(
                new UnitStore.FragmentStep("findDevice", "{\"name\":\"$device\"}"),
                new UnitStore.FragmentStep("findBrand", "{\"device\":\"$device\"}")
        ));
        List<ToolCallLog> trace = List.of(
                step("findDevice", "{\"name\":\"$target\"}"),
                step("findBrand", "{\"device\":\"$target\"}"),
                step("sendInfrared", "{\"command\":\"off\"}")
        );

        var compacted = compactor.compact(trace, List.of(fragment));

        assertEquals(2, compacted.size());
        assertEquals("identify-ac", compacted.get(0).unitId());
        assertEquals("{\"device\":\"$target\"}", compacted.get(0).argumentsJson());
        assertEquals("sendInfrared", compacted.get(1).toolName());
    }

    @Test
    void doesNotFoldWhenConstantsDiffer() {
        var fragment = new UnitStore.PlanFragment("turn-off", List.of(
                new UnitStore.FragmentStep("findDevice", "{\"name\":\"$device\"}"),
                new UnitStore.FragmentStep("sendInfrared", "{\"command\":\"off\"}")
        ));
        List<ToolCallLog> trace = List.of(
                step("findDevice", "{\"name\":\"$target\"}"),
                step("sendInfrared", "{\"command\":\"on\"}")
        );

        var compacted = compactor.compact(trace, List.of(fragment));

        assertEquals(2, compacted.size());
        assertEquals("findDevice", compacted.get(0).toolName());
        assertEquals("sendInfrared", compacted.get(1).toolName());
    }

    private ToolCallLog step(String tool, String args) {
        return new ToolCallLog(tool, args, "ok", true, 1);
    }
}
