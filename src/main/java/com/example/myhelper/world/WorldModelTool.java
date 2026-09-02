package com.example.myhelper.world;

import com.example.myhelper.memory.unit.Unit;
import com.example.myhelper.memory.unit.UnitStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 让 Agent 用同一 ContextUnit 模型写观测、前置条件和成功预期。 */
@Component
public class WorldModelTool {
    private final EnvironmentStateService environment;
    private final UnitStore units;
    private final ObjectMapper mapper;

    public WorldModelTool(EnvironmentStateService environment, UnitStore units, ObjectMapper mapper) {
        this.environment = environment;
        this.units = units;
        this.mapper = mapper;
    }

    @Tool(description = "写入或刷新一个世界状态。valueJson 是 JSON 值；refreshUnitId 可为空，表示该状态没有自动刷新 Unit。")
    public String updateWorldState(
            @ToolParam(description = "对象ID，例如 device:living-room-ac") String subjectId,
            @ToolParam(description = "状态属性，例如 power、online、brand") String predicate,
            @ToolParam(description = "JSON值，例如 true、\"OFF\"、[\"DISCRETE_OFF\"]") String valueJson,
            @ToolParam(description = "有效毫秒数；0表示长期事实") long ttlMillis,
            @ToolParam(description = "可信度0到1") double confidence,
            @ToolParam(description = "负责刷新此状态的 Unit ID，可为空") String refreshUnitId) {
        Object value = parse(valueJson);
        Instant now = Instant.now();
        ContextUnit snapshot = environment.observe(subjectId, predicate, ContextUnit.Operator.EQ,
                null, value, "world-model-tool", ContextUnit.Origin.OBSERVED, now,
                ttlMillis <= 0 ? null : now.plusMillis(ttlMillis), confidence,
                blankToNull(refreshUnitId), true);
        return "已记录观测: " + snapshot.id() + " -> " + snapshot.stateId();
    }

    @Tool(description = "给 Unit 增加直接执行前置条件。相同 Unit 的所有前置条件按 AND 匹配；状态不可信时可调用 refreshUnitId 刷新。")
    public String addUnitRequirement(
            @ToolParam(description = "目标 Unit ID") String unitId,
            @ToolParam(description = "对象ID") String subjectId,
            @ToolParam(description = "状态属性") String predicate,
            @ToolParam(description = "EQ、NOT_EQ、EXISTS、IN、GREATER_THAN、LESS_THAN、SUPPORTS") String operator,
            @ToolParam(description = "期望JSON值；EXISTS也传 null") String expectedJson,
            @ToolParam(description = "最低可信度0到1") double minimumConfidence,
            @ToolParam(description = "最大状态年龄毫秒；0表示不限制") long maximumAgeMillis,
            @ToolParam(description = "刷新状态的 Unit ID，可为空") String refreshUnitId) {
        Unit unit = units.findById(unitId).orElse(null);
        if (unit == null) return "Unit不存在: " + unitId;
        String id = "requirement:" + UUID.randomUUID();
        environment.upsertContext(clause(id, ContextUnit.Role.REQUIREMENT, subjectId, predicate,
                operator, expectedJson, minimumConfidence, maximumAgeMillis, refreshUnitId), true);
        List<String> required = new ArrayList<>(unit.requiredContextIds());
        required.add(id);
        units.configureDirectExecution(unitId, Unit.DirectExecutionStatus.ACTIVE, required, unit.expectedContextIds());
        return "已增加直接执行条件: " + id;
    }

    @Tool(description = "给 Unit 增加执行后的状态预期，用于确认实际目标已经达成。")
    public String addUnitExpectation(
            @ToolParam(description = "目标 Unit ID") String unitId,
            @ToolParam(description = "对象ID") String subjectId,
            @ToolParam(description = "状态属性") String predicate,
            @ToolParam(description = "期望JSON值") String expectedJson,
            @ToolParam(description = "刷新状态的 Unit ID，可为空") String refreshUnitId) {
        Unit unit = units.findById(unitId).orElse(null);
        if (unit == null) return "Unit不存在: " + unitId;
        String id = "expectation:" + UUID.randomUUID();
        environment.upsertContext(clause(id, ContextUnit.Role.EXPECTATION, subjectId, predicate,
                "EQ", expectedJson, 0.0, 0, refreshUnitId), true);
        List<String> expected = new ArrayList<>(unit.expectedContextIds());
        expected.add(id);
        units.configureDirectExecution(unitId, Unit.DirectExecutionStatus.ACTIVE, unit.requiredContextIds(), expected);
        return "已增加执行结果预期: " + id;
    }

    private ContextUnit clause(String id, ContextUnit.Role role, String subject, String predicate,
                               String operator, String json, double minConfidence, long maxAge, String refresh) {
        ContextUnit.Operator op = ContextUnit.Operator.valueOf(operator.toUpperCase());
        Object value = parse(json);
        ContextUnit state = environment.ensureState(subject, predicate, op, null, value, true);
        return new ContextUnit(id, role, subject, predicate, op,
                null, value, state.id(), "unit-contract", ContextUnit.Origin.SYSTEM_DEFINED,
                Instant.now(), null, 1.0, minConfidence, maxAge, blankToNull(refresh), List.of());
    }

    private Object parse(String json) {
        if (json == null || json.isBlank() || "null".equalsIgnoreCase(json.trim())) return null;
        try { return mapper.readValue(json, Object.class); }
        catch (Exception e) { return json; }
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
}
