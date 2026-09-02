package com.example.myhelper.memory.unit;

import com.example.myhelper.memory.vector.episode.ToolCallLog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UnitDataflowInferrerTest {
    @Test
    void rewritesLaterArgumentToPreviousJsonOutput() {
        List<ToolCallLog> original = List.of(
                call("findDevice", "{\"name\":\"卧室空调\"}", "{\"deviceId\":\"device-123\"}"),
                call("sendInfrared", "{\"deviceId\":\"device-123\",\"command\":\"OFF\"}", "ok")
        );
        List<ToolCallLog> templated = List.of(
                call("findDevice", "{\"name\":\"$target\"}", "{\"deviceId\":\"device-123\"}"),
                call("sendInfrared", "{\"deviceId\":\"device-123\",\"command\":\"OFF\"}", "ok")
        );

        var inferred = new UnitDataflowInferrer().infer(original, templated);

        assertEquals("{\"deviceId\":\"$findDevice.deviceId\",\"command\":\"OFF\"}",
                inferred.rewrittenCalls().get(1).args());
        assertEquals("$.deviceId", inferred.outputSignaturesByTool().get("findDevice").get("deviceId"));
    }

    @Test
    void doesNotLinkShortUnrelatedCommonValue() {
        List<ToolCallLog> calls = List.of(
                call("check", "{}", "{\"status\":\"OFF\"}"),
                call("send", "{\"command\":\"OFF\"}", "ok")
        );
        var inferred = new UnitDataflowInferrer().infer(calls, calls);
        assertEquals("{\"command\":\"OFF\"}", inferred.rewrittenCalls().get(1).args());
    }

    private ToolCallLog call(String tool, String args, String result) {
        return new ToolCallLog(tool, args, result, true, 1);
    }
}
