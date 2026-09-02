package com.example.myhelper.memory.unit;

import com.example.myhelper.memory.vector.episode.ToolCallLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把新轨迹中与既有计划相同的连续步骤折叠为对该 PLAN_STEP 的一次引用。 */
final class UnitGraphCompactor {

    private final ObjectMapper mapper = new ObjectMapper();

    record Invocation(String unitId, String toolName, String argumentsJson, int consumedSteps) {
        boolean isFragment() { return unitId != null; }
    }

    List<Invocation> compact(List<ToolCallLog> trace, List<UnitStore.PlanFragment> fragments) {
        List<Invocation> result = new ArrayList<>();
        int index = 0;
        while (index < trace.size()) {
            Invocation matched = null;
            for (UnitStore.PlanFragment fragment : fragments) {
                if (fragment.steps().size() > trace.size() - index) continue;
                Map<String, String> bindings = new LinkedHashMap<>();
                if (matches(trace, index, fragment, bindings)) {
                    matched = new Invocation(fragment.unitId(), null, toJson(bindings), fragment.steps().size());
                    break; // fragments 已按长度降序，采用最长匹配
                }
            }
            if (matched != null) {
                result.add(matched);
                index += matched.consumedSteps();
            } else {
                ToolCallLog step = trace.get(index++);
                result.add(new Invocation(null, step.toolName(), step.args(), 1));
            }
        }
        return result;
    }

    private boolean matches(List<ToolCallLog> trace, int offset, UnitStore.PlanFragment fragment,
                            Map<String, String> bindings) {
        for (int i = 0; i < fragment.steps().size(); i++) {
            UnitStore.FragmentStep expected = fragment.steps().get(i);
            ToolCallLog actual = trace.get(offset + i);
            if (!expected.toolName().equals(actual.toolName())) return false;
            try {
                JsonNode expectedArgs = mapper.readTree(blankJson(expected.argumentsJson()));
                JsonNode actualArgs = mapper.readTree(blankJson(actual.args()));
                if (!unify(expectedArgs, actualArgs, bindings)) return false;
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private boolean unify(JsonNode expected, JsonNode actual, Map<String, String> bindings) {
        if (expected == null || actual == null) return expected == actual;
        if (expected.isTextual() && isVariable(expected.textValue())) {
            String variable = expected.textValue().substring(1);
            String value = actual.isTextual() ? actual.textValue() : actual.toString();
            return !bindings.containsKey(variable) ? bindings.put(variable, value) == null
                    : bindings.get(variable).equals(value);
        }
        if (expected.getNodeType() != actual.getNodeType()) return false;
        if (expected.isObject()) {
            if (expected.size() != actual.size()) return false;
            Iterator<Map.Entry<String, JsonNode>> fields = expected.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!actual.has(field.getKey()) || !unify(field.getValue(), actual.get(field.getKey()), bindings)) {
                    return false;
                }
            }
            return true;
        }
        if (expected.isArray()) {
            if (expected.size() != actual.size()) return false;
            for (int i = 0; i < expected.size(); i++) {
                if (!unify(expected.get(i), actual.get(i), bindings)) return false;
            }
            return true;
        }
        return expected.equals(actual);
    }

    private boolean isVariable(String value) {
        return value != null && value.matches("\\$[A-Za-z_][A-Za-z0-9_.-]*");
    }

    private String blankJson(String json) {
        return json == null || json.isBlank() ? "{}" : json;
    }

    private String toJson(Map<String, String> bindings) {
        try { return mapper.writeValueAsString(bindings); }
        catch (Exception e) { return "{}"; }
    }
}
