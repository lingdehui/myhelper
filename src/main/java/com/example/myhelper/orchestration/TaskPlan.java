package com.example.myhelper.orchestration;

import com.example.myhelper.llm.LlmTexts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 模型或固化能力给出的有限执行图；参数只接受 JSON 数据，不持有调用方可变集合。 */
public record TaskPlan(
        /** 本次规划的总体目标，用于规划后检索整条固化。 */
        String goal,
        /** 步骤及其明确依赖；数组顺序不隐含业务依赖。 */
        List<Step> steps,
        /** 可选控制分支声明；为空时仅从结构明确的 DAG 推导，不把交叉数据依赖猜成汇合。 */
        PlanBranches.Routing routing) {
    /** 参数来源保留字：读取当前任务输入，而不是某个前驱步骤。 */
    public static final String TASK_INPUT_SOURCE = "$input";

    /** 保留原有计划构造方式；普通线性和明确的分叉/汇合不需要手写分支声明。 */
    public TaskPlan(String goal, List<Step> steps) {
        this(goal, steps, null);
    }

    // 在接收计划时完成整体校验，任何动作都尚未开始。
    public TaskPlan {
        // 总体目标不能为空，它还用于规划后的数据库检索。
        requireText(goal, "goal");
        // 固定步骤列表，不允许模型计划被外部修改。
        steps = List.copyOf(steps);
        // 空计划不表示任务完成；完成应通过 finish_task 返回。
        if (steps.isEmpty()) {
            // 抛出参数或协议错误，阻止无效输入继续进入执行。
            throw new IllegalArgumentException(LlmTexts.PLAN_STEPS_REQUIRED);
        }
        // 建立步骤索引，用来检测重复 ID 和不存在的前驱。
        Map<String, Step> byId = new LinkedHashMap<>();
        // 逐步校验计划结构，而不是执行工具。
        for (Step step : steps) {
            // 步骤 ID 在本计划内必须唯一。
            if (byId.putIfAbsent(step.id(), step) != null) {
                // 抛出参数或协议错误，阻止无效输入继续进入执行。
                throw new IllegalArgumentException(LlmTexts.DUPLICATE_STEP_PREFIX + step.id());
            }
        }
        // 在启动任何动作前，检查引用完整性及整个依赖图。
        Set<String> resolved = new HashSet<>();
        // 通过拓扑消解检查依赖是否无环，直到所有步骤都可解析。
        while (resolved.size() < steps.size()) {
            // 记录本遍解析前的数量，用于检测是否无法继续推进。
            int before = resolved.size();
            // 逐步校验计划结构，而不是执行工具。
            for (Step step : steps) {
                // 检查当前步骤声明的每个前驱 ID。
                for (String dependency : step.dependsOn()) {
                    // 引用不存在的步骤会导致永久等待，因此接收计划时直接拒绝。
                    if (!byId.containsKey(dependency)) {
                        // 抛出参数或协议错误，阻止无效输入继续进入执行。
                        throw new IllegalArgumentException(LlmTexts.UNKNOWN_DEPENDENCY_PREFIX + dependency);
                    }
                }
                // 检查每个参数来源是否对应任务输入或显式前驱。
                for (InputBinding binding : step.bindings().values()) {
                    // 读取其他步骤结果必须同时声明执行依赖，防止过早读取。
                    if (!binding.source().equals(TASK_INPUT_SOURCE) && !step.dependsOn().contains(binding.source())) {
                        // 抛出参数或协议错误，阻止无效输入继续进入执行。
                        throw new IllegalArgumentException(LlmTexts.BINDING_DEPENDENCY_REQUIRED_PREFIX + binding.source());
                    }
                }
                // 前驱都已解析后，当前步骤也可加入已解析集合。
                if (resolved.containsAll(step.dependsOn())) {
                    // 登记此步骤的拓扑检查已经通过；不表示工具已执行。
                    resolved.add(step.id());
                }
            }
            // 一整遍无法新增任何节点，说明剩余依赖形成循环。
            if (before == resolved.size()) {
                // 抛出参数或协议错误，阻止无效输入继续进入执行。
                throw new IllegalArgumentException(LlmTexts.CYCLIC_DEPENDENCY);
            }
        }
    }

    /** TOOL 调用已有普通工具；TASK 通过编排器递归求解一个子目标。 */
    public enum Kind {
        /** 使用工具名定位已注册的回调。 */
        TOOL,
        /** 将子目标递归交给编排器求解。 */
        TASK
    }

    public record Step(
            /** 本计划内唯一编号。 */
            String id,
            /** LLM 给出的具体子目标，是规划后检索的查询依据。 */
            String goal,
            /** 普通工具或递归任务。 */
            Kind kind,
            /** TOOL 时必须是本任务允许的工具名，TASK 时为空。 */
            String toolName,
            /** 本次已知参数；依赖参数随后按 bindings 绑定。 */
            Map<String, Object> arguments,
            /** 必须成功后才能运行本步骤的前驱编号。 */
            List<String> dependsOn,
            /** 参数名到顶层输入或前驱结果 JSON Pointer 的映射。 */
            Map<String, InputBinding> bindings) {
        // 固定一步的工具/任务类型、参数及依赖合同。
        public Step {
            // 每步必须有明确身份，供其他步骤引用。
            requireText(id, "step.id");
            // 子目标不能为空，它是规划后查找子能力的语义依据。
            requireText(goal, "step.goal");
            // 类型不能为空，必须明确是普通工具还是递归任务。
            Objects.requireNonNull(kind, "step.kind");
            // 普通工具必须指定实际注册名称。
            if (kind == Kind.TOOL) {
                // 工具名不能为空，执行器据此定位回调。
                requireText(toolName, "step.toolName");
            }
            // 递归任务不能同时指定一个普通工具名。
            if (kind == Kind.TASK && toolName != null && !toolName.isBlank()) {
                // 抛出参数或协议错误，阻止无效输入继续进入执行。
                throw new IllegalArgumentException(LlmTexts.TASK_TOOL_NOT_ALLOWED);
            }
            // 把嵌套参数冻结，多个并发步骤不能改写彼此输入。
            arguments = immutableMap(arguments);
            // 未提供依赖时视为空列表，否则保存只读前驱列表。
            dependsOn = List.copyOf(dependsOn == null ? List.of() : dependsOn);
            // 重复前驱没有额外含义，直接拒绝以保持合同明确。
            if (new HashSet<>(dependsOn).size() != dependsOn.size()) {
                // 抛出参数或协议错误，阻止无效输入继续进入执行。
                throw new IllegalArgumentException(LlmTexts.DUPLICATE_DEPENDENCY);
            }
            // 固定参数来源映射；未提供绑定时为空映射。
            bindings = Map.copyOf(bindings == null ? Map.of() : bindings);
        }
    }

    public record InputBinding(
            /** $input 表示当前任务参数，其他值表示依赖步骤编号。 */
            String source,
            /** 空字符串取整个结果；其他路径遵循 JSON Pointer。 */
            String pointer) {
        // 校验一个数据来源与字段路径。
        public InputBinding {
            // 来源不能为空；保留字表示任务输入，其他值表示步骤 ID。
            requireText(source, "binding.source");
            // JSON Pointer 可为空表示整体值；非空时必须以斜线开头。
            if (pointer == null || (!pointer.isEmpty() && !pointer.startsWith("/"))) {
                // 抛出参数或协议错误，阻止无效输入继续进入执行。
                throw new IllegalArgumentException(LlmTexts.INVALID_ARGUMENT_PATH);
            }
        }
    }

    /** 深复制 JSON 值；拒绝任意可变 Java 对象作为任务参数。 */
    public static Object immutable(Object value) {
        // JSON 标量直接保留；Map/List 需要继续递归复制。
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number) {
            // 返回当前标量，不执行任何类型猜测或参数转换。
            return value;
        }
        // JSON 对象要逐字段复制，避免共享可变嵌套集合。
        if (value instanceof Map<?, ?> map) {
            // 建立独立对象副本；LinkedHashMap 保留读取顺序。
            Map<String, Object> copy = new LinkedHashMap<>();
            // 递归检查每一个键和嵌套值。
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                Object item = entry.getValue();
                // JSON 对象只允许字符串键，拒绝其他 Java 键类型。
                if (!(key instanceof String text)) {
                    // 抛出参数或协议错误，阻止无效输入继续进入执行。
                    throw new IllegalArgumentException(LlmTexts.JSON_STRING_KEY_REQUIRED);
                }
                // 深复制字段值后加入新对象。
                copy.put(text, immutable(item));
            }
            // 禁止后续修改对象，但允许 JSON 合法的 null 字段值。
            return Collections.unmodifiableMap(copy);
        }
        // JSON 数组同样逐元素复制。
        if (value instanceof List<?> list) {
            // 建立独立数组副本。
            List<Object> copy = new ArrayList<>();
            // 递归冻结数组中的对象和子数组。
            for (Object item : list) {
                copy.add(immutable(item));
            }
            // 返回只读数组，禁止执行器修改共享参数。
            return Collections.unmodifiableList(copy);
        }
        // 抛出参数或协议错误，阻止无效输入继续进入执行。
        throw new IllegalArgumentException(LlmTexts.UNSUPPORTED_ARGUMENT_TYPE_PREFIX + value.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    // 统一 Map 入口；这里只对已检查为字符串键的 JSON 对象做类型转换。
    public static Map<String, Object> immutableMap(Map<String, Object> value) {
        // 空参数映射统一视为空对象，否则深复制全部内容。
        return (Map<String, Object>) immutable(value == null ? Map.of() : value);
    }

    // 统一非空文本校验，name 只用于标识错误字段。
    public static void requireText(String value, String name) {
        // JSON 标量直接保留；Map/List 需要继续递归复制。
        if (value == null || value.isBlank()) {
            // 抛出参数或协议错误，阻止无效输入继续进入执行。
            throw new IllegalArgumentException(name + LlmTexts.REQUIRED_TEXT_SUFFIX);
        }
    }
}
