package com.example.desktopbrain.registry;

import java.util.List;
import java.util.Map;

/**
 * 工具统一数据传输对象。
 * 所有工具（Java @Tool / MCP / @GeneratedTool）统一为此格式入库。
 *
 * @param id          唯一标识：{TYPE}:{SOURCE}:{NAME}
 * @param name        工具调用名称
 * @param description 功能描述
 * @param type        工具类型：JAVA / MCP / GENERATED
 * @param source      来源：JAVA时为类名，MCP时为Server名
 * @param parameters  参数列表（名称、类型、是否必填、描述）
 * @param returnType  返回类型描述
 * @param status      状态：ACTIVE / DEPRECATED / DISABLED
 * @param callable    当前是否可调用（验证通过）
 * @param categories  所属分类名列表
 * @param inputSchema 原始输入 JSON Schema（供 AI 理解参数约束）
 */
public record ToolModel(
        String id,
        String name,
        String description,
        String type,
        String source,
        List<ParamInfo> parameters,
        String returnType,
        String status,
        boolean callable,
        List<String> categories,
        Map<String, Object> inputSchema
) {
    /** 参数信息 */
    public record ParamInfo(
            String name,
            String type,
            boolean required,
            String description
    ) {}

    /** 工厂方法 */
    public static ToolModel of(String id, String name, String description, String type,
                                String source, List<ParamInfo> params, String returnType,
                                List<String> categories, Map<String, Object> inputSchema) {
        return new ToolModel(id, name, description, type, source,
                params != null ? params : List.of(),
                returnType != null ? returnType : "String",
                "ACTIVE", true, categories != null ? categories : List.of(),
                inputSchema);
    }

    /** 用于 AI prompt 中的格式化文本 */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("- ").append(name).append(" [").append(type).append("]");
        if (description != null && !description.isBlank()) {
            sb.append(": ").append(description);
        }
        if (!parameters.isEmpty()) {
            sb.append("\n  参数:");
            for (ParamInfo p : parameters) {
                sb.append("\n    ").append(p.name())
                        .append(" (").append(p.type()).append(")")
                        .append(p.required() ? " [必填]" : "");
                if (p.description() != null && !p.description().isBlank()) {
                    sb.append(" - ").append(p.description());
                }
            }
        }
        return sb.toString();
    }
}
