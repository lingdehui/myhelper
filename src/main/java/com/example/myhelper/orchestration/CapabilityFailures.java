package com.example.myhelper.orchestration;

import com.example.myhelper.capability.CapabilityLookup.Ref;
import com.example.myhelper.llm.LlmTexts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 本根任务内同实参的失败版本；只影响自动选用，不改技能发布或持久统计。 */
final class CapabilityFailures {
    /** JSON 树比较忽略对象键序并统一整数的 Java 宽度。 */
    private final ObjectMapper json = new ObjectMapper();
    /** 同一根任务的并发分支共享，访问由短同步方法保护。 */
    private final Set<Failure> failures = new HashSet<>();

    synchronized void add(Ref ref, Map<String, Object> arguments) {
        failures.add(new Failure(ref, normalized(arguments)));
    }

    synchronized Set<Ref> excluded(Map<String, Object> arguments) {
        JsonNode current = normalized(arguments);
        Set<Ref> result = new HashSet<>();
        for (Failure failure : failures) {
            if (failure.arguments().equals(current)) {
                result.add(failure.ref());
            }
        }
        return result;
    }

    private JsonNode normalized(Map<String, Object> arguments) {
        try {
            return json.readTree(json.writeValueAsBytes(arguments));
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException(LlmTexts.CAPABILITY_ARGUMENT_COMPARE_FAILED, failure);
        }
    }

    private record Failure(
            /** 已确认失败的固定链版本。 */
            Ref ref,
            /** 当次完整参数快照，避免全局禁用不同输入。 */
            JsonNode arguments) { }
}
