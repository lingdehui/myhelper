package com.example.myhelper.memory.unit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class UnitPathGeneralizerTest {
    @Test
    void promotesDifferentScalarValuesAndKeepsConstants() {
        var result = new UnitPathGeneralizer().generalize(List.of(
                List.of("{\"name\":\"卧室空调\"}", "{\"command\":\"OFF\",\"deviceId\":\"123\"}"),
                List.of("{\"name\":\"客厅空调\"}", "{\"command\":\"OFF\",\"deviceId\":\"456\"}")
        ));

        assertNotNull(result);
        assertEquals("{\"name\":\"$s1_name\"}", result.childArguments().get(0));
        assertEquals("{\"command\":\"OFF\",\"deviceId\":\"$s2_deviceId\"}", result.childArguments().get(1));
        assertEquals("卧室空调", result.occurrenceBindings().get(0).get("s1_name"));
        assertEquals("客厅空调", result.occurrenceBindings().get(1).get("s1_name"));
    }
}
