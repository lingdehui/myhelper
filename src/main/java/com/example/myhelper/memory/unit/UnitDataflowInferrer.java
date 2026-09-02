package com.example.myhelper.memory.unit;

import com.example.myhelper.memory.vector.episode.ToolCallLog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从一次成功轨迹中确定性识别“前一步输出值被后一步作为参数使用”的数据流。 */
final class UnitDataflowInferrer {
    private final ObjectMapper mapper = new ObjectMapper();

    record Inference(List<ToolCallLog> rewrittenCalls,
                     Map<String, Map<String, String>> outputSignaturesByTool) {}
    private record OutputValue(int step, String stepName, String variable,
                               String selector, String value) {}

    Inference infer(List<ToolCallLog> original, List<ToolCallLog> templated) {
        if (original == null || templated == null || original.size() != templated.size()) {
            return new Inference(templated == null ? List.of() : templated, Map.of());
        }
        List<OutputValue> available = new ArrayList<>();
        List<ToolCallLog> rewritten = new ArrayList<>();
        Map<String, Map<String, String>> signatures = new LinkedHashMap<>();
        for (int i = 0; i < original.size(); i++) {
            ToolCallLog actual = original.get(i);
            ToolCallLog template = templated.get(i);
            String args = rewriteArgs(actual.args(), template.args(), available, signatures);
            rewritten.add(new ToolCallLog(template.toolName(), args, template.result(),
                    template.success(), template.durationMs()));
            available.addAll(extractOutputs(i, actual.toolName(), actual.result()));
        }
        Map<String, Map<String, String>> immutable = new LinkedHashMap<>();
        signatures.forEach((k, v) -> immutable.put(k, Map.copyOf(v)));
        return new Inference(List.copyOf(rewritten), Map.copyOf(immutable));
    }

    private String rewriteArgs(String actualJson, String templateJson, List<OutputValue> available,
                               Map<String, Map<String, String>> signatures) {
        try {
            JsonNode actual = mapper.readTree(blank(actualJson));
            JsonNode template = mapper.readTree(blank(templateJson));
            if (!actual.isObject() || !template.isObject()) return templateJson;
            rewriteObject((ObjectNode) actual, (ObjectNode) template, "", available, signatures);
            return mapper.writeValueAsString(template);
        } catch (Exception e) {
            return templateJson;
        }
    }

    private void rewriteObject(ObjectNode actual, ObjectNode template, String path,
                               List<OutputValue> available,
                               Map<String, Map<String, String>> signatures) {
        actual.fields().forEachRemaining(field -> {
            String name = field.getKey();
            JsonNode actualValue = field.getValue();
            JsonNode templateValue = template.get(name);
            if (templateValue == null) return;
            if (actualValue.isObject() && templateValue.isObject()) {
                rewriteObject((ObjectNode) actualValue, (ObjectNode) templateValue,
                        path + "/" + name, available, signatures);
                return;
            }
            if (!isScalar(actualValue)) return;
            String value = scalar(actualValue);
            OutputValue source = nearestSource(available, name, value);
            if (source == null) return;
            template.put(name, "$" + source.stepName() + "." + source.variable());
            signatures.computeIfAbsent(source.stepName(), ignored -> new LinkedHashMap<>())
                    .put(source.variable(), source.selector());
        });
    }

    private OutputValue nearestSource(List<OutputValue> available, String inputName, String value) {
        for (int i = available.size() - 1; i >= 0; i--) {
            OutputValue candidate = available.get(i);
            if (!candidate.value().equals(value)) continue;
            // 短值只有字段语义一致才关联，避免把 true/ok/OFF 等常见值误判为数据流。
            if (candidate.variable().equalsIgnoreCase(inputName) || value.length() >= 8) return candidate;
        }
        return null;
    }

    private List<OutputValue> extractOutputs(int step, String stepName, String result) {
        if (result == null || result.isBlank()) return List.of();
        List<OutputValue> values = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(result);
            flatten(step, stepName, root, "$", values);
        } catch (Exception ignored) {
            values.add(new OutputValue(step, stepName, "result", "string", result));
        }
        return values;
    }

    private void flatten(int step, String stepName, JsonNode node, String selector,
                         List<OutputValue> values) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(field -> flatten(step, stepName, field.getValue(),
                    selector + "." + field.getKey(), values));
        } else if (isScalar(node)) {
            String variable = selector.equals("$") ? "result"
                    : selector.substring(selector.lastIndexOf('.') + 1);
            values.add(new OutputValue(step, stepName, variable,
                    selector.equals("$") ? "string" : selector, scalar(node)));
        }
        // 数组不自动建立位置依赖；索引通常不稳定，留给显式 outputSignature。
    }

    private boolean isScalar(JsonNode value) {
        return value != null && (value.isTextual() || value.isNumber() || value.isBoolean());
    }
    private String scalar(JsonNode value) { return value.isTextual() ? value.textValue() : value.asText(); }
    private String blank(String json) { return json == null || json.isBlank() ? "{}" : json; }
}
