package com.example.myhelper.memory.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/**
 * 计划步骤包装工具（文档 15 v1.7 §9）。
 *
 * <p>把 PLAN_STEP Unit 包装成 ToolCallback，AI 可直接调用。内部转发到
 * {@link UniversalUnitExecutor#executePlanStep(String, String)}，递归展开执行。</p>
 */
public class UnitToolCallback implements ToolCallback {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String unitId;
    private final ToolDefinition definition;
    private final UniversalUnitExecutor executor;
    private final UnitStore unitStore;

    public UnitToolCallback(String unitId, String name, String description,
                            UniversalUnitExecutor executor, UnitStore unitStore) {
        this.unitId = unitId;
        this.executor = executor;
        this.unitStore = unitStore;
        this.definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema("{\"type\":\"object\",\"properties\":{\"params\":{\"type\":\"string\",\"description\":\"可选，JSON 字符串参数\"}}}")
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        String params = normalizeParams(toolInput);
        UniversalUnitExecutor.ExecutionResult result = executor.executePlanStep(unitId, params);
        if (result.success()) {
            // 成功后更新计数（§8.2）；探索模式追加 EXPLORATION 记录（§9）
            unitStore.incrementSuccess(unitId);
            ExplorationRecord.DeclareType declareType = executor.getExplorationDeclareType();
            if (declareType != null) {
                unitStore.appendExplorationRecord(unitId, declareType,
                        "执行完成（" + result.executedSteps().size() + " 步）");
            }
            return "执行完成（" + result.executedSteps().size() + " 步）";
        }
        return "执行失败: " + result.errorMessage();
    }

    /** 若 AI 传了 {"params":"..."}，取内层字符串；否则原样传递。 */
    private String normalizeParams(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) return "{}";
        try {
            Map<?, ?> map = objectMapper.readValue(toolInput, Map.class);
            Object params = map.get("params");
            if (params != null) return String.valueOf(params);
        } catch (Exception ignored) {
            // 非 JSON → 原样
        }
        return toolInput;
    }
}
