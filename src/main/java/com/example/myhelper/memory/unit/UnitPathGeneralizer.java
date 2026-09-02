package com.example.myhelper.memory.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 对同构工具路径的参数做保守反统一：相同值保留，不同标量提升为公共参数。 */
final class UnitPathGeneralizer {
    private final ObjectMapper mapper = new ObjectMapper();

    record Generalized(List<String> childArguments, List<Map<String, String>> occurrenceBindings,
                       Map<String, String> signature) {}

    Generalized generalize(List<List<String>> argumentsByOccurrence) {
        if (argumentsByOccurrence.size() < 2) return null;
        int width = argumentsByOccurrence.get(0).size();
        if (width < 2 || argumentsByOccurrence.stream().anyMatch(v -> v.size() != width)) return null;
        List<JsonNode> templates = new ArrayList<>();
        List<Map<String, String>> bindings = new ArrayList<>();
        for (int i = 0; i < argumentsByOccurrence.size(); i++) bindings.add(new LinkedHashMap<>());
        Map<String, String> signature = new LinkedHashMap<>();
        try {
            for (int step = 0; step < width; step++) {
                List<JsonNode> samples = new ArrayList<>();
                for (List<String> occurrence : argumentsByOccurrence) {
                    samples.add(mapper.readTree(blank(occurrence.get(step))));
                }
                JsonNode template = generalizeNode(samples, "s" + (step + 1), bindings, signature);
                if (template == null) return null;
                templates.add(template);
            }
            List<String> json = new ArrayList<>();
            for (JsonNode template : templates) json.add(mapper.writeValueAsString(template));
            return new Generalized(List.copyOf(json), List.copyOf(bindings), Map.copyOf(signature));
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode generalizeNode(List<JsonNode> samples, String path,
                                    List<Map<String, String>> bindings, Map<String, String> signature) {
        JsonNode first = samples.get(0);
        if (samples.stream().allMatch(first::equals)) return first.deepCopy();
        if (samples.stream().allMatch(JsonNode::isObject)) {
            ObjectNode result = mapper.createObjectNode();
            var fields = first.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (samples.stream().anyMatch(n -> !n.has(field)) ||
                        samples.stream().anyMatch(n -> n.size() != first.size())) return null;
                List<JsonNode> values = samples.stream().map(n -> n.get(field)).toList();
                JsonNode generalized = generalizeNode(values, path + "_" + safe(field), bindings, signature);
                if (generalized == null) return null;
                result.set(field, generalized);
            }
            return result;
        }
        if (samples.stream().allMatch(UnitPathGeneralizer::isScalar)) {
            String variable = unique(path, signature);
            signature.put(variable, scalarType(first));
            for (int i = 0; i < samples.size(); i++) {
                bindings.get(i).put(variable, scalarValue(samples.get(i)));
            }
            return TextNode.valueOf("$" + variable);
        }
        return null;
    }

    private static boolean isScalar(JsonNode n) {
        return n != null && (n.isTextual() || n.isNumber() || n.isBoolean() || n.isNull());
    }
    private static String scalarValue(JsonNode n) { return n.isTextual() ? n.textValue() : n.toString(); }
    private static String scalarType(JsonNode n) {
        if (n.isBoolean()) return "boolean";
        if (n.isIntegralNumber()) return "integer";
        if (n.isNumber()) return "number";
        return "string";
    }
    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9_]", "_"); }
    private static String unique(String base, Map<String, String> signature) {
        String value = base; int suffix = 2;
        while (signature.containsKey(value)) value = base + "_" + suffix++;
        return value;
    }
    private static String blank(String json) { return json == null || json.isBlank() ? "{}" : json; }
}
