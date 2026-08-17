package com.example.myhelper.memory.unit;

import com.example.myhelper.config.MyHelperProperties;
import com.example.myhelper.memory.graph.UnitNode;
import com.example.myhelper.memory.graph.UnitRepository;
import com.example.myhelper.memory.vector.episode.ToolCallLog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 万能执行器（文档 15 v1.7 §8）。
 *
 * <p>统一入口 {@link #executePlanStep(String, String)}，内部按 {@code scriptable} 分流：
 * <ul>
 *   <li>脚本化：遍历预编译 {@code script}，零递归零 AI；</li>
 *   <li>递归展开：按 CONTAINS 有序树递归执行，带防环与深度限制；</li>
 * </ul>
 *
 * <p>本执行器只负责"执行并返回轨迹"，不负责写库与计数更新（沉淀/计数由上层 Service 处理）。</p>
 */
@Service
public class UniversalUnitExecutor {

    private static final Logger log = LoggerFactory.getLogger(UniversalUnitExecutor.class);

    private final UnitRepository unitRepository;
    private final UnitStore unitStore;
    private final MyHelperProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 全量工具名 → ToolCallback（由应用启动时注入，含 MCP + 本地 + 动态工具） */
    private volatile Map<String, ToolCallback> toolMap = Collections.emptyMap();

    /** 递归深度上限（超大型任务可临时调大） */
    @Value("${myhelper.execution.max-depth:5}")
    private int maxDepth;

    /** 探索模式声明类型（VALIDATE/OPTIMIZE），非探索时为 null。由 TurnProcessor 在探索 Turn 设置。 */
    private volatile ExplorationRecord.DeclareType explorationDeclareType = null;

    public UniversalUnitExecutor(UnitRepository unitRepository, UnitStore unitStore, MyHelperProperties props) {
        this.unitRepository = unitRepository;
        this.unitStore = unitStore;
        this.props = props;
    }

    /** 探索模式：显式声明本次选中 Unit 的探索类型（§9）。 */
    public void setExplorationDeclareType(ExplorationRecord.DeclareType declareType) {
        this.explorationDeclareType = declareType;
    }

    public void clearExplorationDeclareType() {
        this.explorationDeclareType = null;
    }

    public ExplorationRecord.DeclareType getExplorationDeclareType() {
        return explorationDeclareType;
    }

    /**
     * 注入全量工具列表（启动时调用，与探索服务一致）。
     */
    public void setAllTools(ToolCallback[] tools) {
        if (tools == null) {
            this.toolMap = Collections.emptyMap();
            return;
        }
        Map<String, ToolCallback> map = new LinkedHashMap<>();
        for (ToolCallback tc : tools) {
            if (tc != null && tc.getToolDefinition() != null) {
                map.putIfAbsent(tc.getToolDefinition().name(), tc);
            }
        }
        this.toolMap = map;
    }

    /**
     * 把活跃 PLAN_STEP Unit 包装成工具（选哪个注册哪个：由规划阶段按名筛选注入）。
     *
     * <p>每次调用实时查 Neo4j，新沉淀的 Unit 下一轮自动可被规划选中。</p>
     */
    public ToolCallback[] buildUnitTools() {
        List<UnitNode> units = unitRepository.findActiveByUnitKind(UnitKind.PLAN_STEP.name());
        // 一次性拉取所有 PLAN_STEP 的 CONTAINS 子节点，消除逐个查询的 N+1
        Map<String, List<String>> childNamesByParent = new HashMap<>();
        for (String row : unitRepository.findPlanStepChildRows()) {
            // 单列拼接 parentId|childId|toolName|goal（toolName/goal 空串占位）
            String[] p = row.split("\\|", -1);
            childNamesByParent.computeIfAbsent(p[0], k -> new ArrayList<>())
                    .add(stepName(p[2], p[3], p[1]));
        }
        List<ToolCallback> tools = new ArrayList<>();
        for (UnitNode u : units) {
            String name = "planStep_" + u.getUnitId();
            List<String> childNames = childNamesByParent.get(u.getUnitId());
            if (childNames == null || childNames.isEmpty()) {
                childNames = childStepNamesFromScript(u);
            }
            tools.add(new UnitToolCallback(u.getUnitId(), name, buildToolDescription(u, childNames), this, unitStore));
        }
        return tools.toArray(new ToolCallback[0]);
    }

    /** 包装工具描述：goal + 内部步骤列表 + 适用场景（§3.3，标记由展示层统一加）。 */
    private String buildToolDescription(UnitNode u, List<String> childNames) {
        String goal = (u.getGoal() != null && !u.getGoal().isBlank()) ? u.getGoal()
                : (u.getMatchText() != null ? u.getMatchText() : u.getUnitId());
        StringBuilder sb = new StringBuilder(goal);

        if (childNames != null && !childNames.isEmpty()) {
            sb.append("。共 ").append(childNames.size()).append(" 步，内部按顺序执行：");
            for (int i = 0; i < childNames.size(); i++) {
                sb.append(" ").append(i + 1).append(") ").append(childNames.get(i));
            }
        }
        if (u.getDescription() != null && !u.getDescription().isBlank()) {
            sb.append("。").append(u.getDescription());
        }
        return sb.toString();
    }

    /** 子步骤名：CONTAINS 子节点用预加载数据（见 buildUnitTools），这里只兜底预编译 script 的工具名。 */
    private List<String> childStepNamesFromScript(UnitNode u) {
        List<String> names = new ArrayList<>();
        List<ToolCallLog> script = parseScript(u.getScriptJson());
        for (ToolCallLog step : script) {
            names.add(step.toolName());
        }
        return names;
    }

    /** 子节点概要 → 步骤名（toolName 优先，退回 goal，再退回 unitId）。 */
    private String stepName(String toolName, String goal, String childId) {
        if (toolName != null && !toolName.isBlank()) return toolName;
        if (goal != null && !goal.isBlank()) return goal;
        return childId;
    }

    /** 从包装工具名反查 unitId（planStep_<uuid> → uuid），非包装工具返回 null。 */
    public String resolveUnitId(String toolName) {
        if (toolName != null && toolName.startsWith("planStep_")) {
            return toolName.substring("planStep_".length());
        }
        return null;
    }

    /**
     * 执行结果。
     *
     * @param success       是否全部成功
     * @param executedSteps 已执行步骤日志（成功 + 失败，按执行顺序）
     * @param failedUnitId  失败所在 unitId（成功时为 null）
     * @param errorMessage  失败原因（成功时为 null）
     * @param outputs       本 Unit 产出的变量（来自 outputSignature，供数据流引用）
     */
    public record ExecutionResult(boolean success, List<ToolCallLog> executedSteps,
                                  String failedUnitId, String errorMessage,
                                  Map<String, String> outputs) {
        public static ExecutionResult success(List<ToolCallLog> steps) {
            return new ExecutionResult(true, steps, null, null, Map.of());
        }
        public static ExecutionResult success(List<ToolCallLog> steps, Map<String, String> outputs) {
            return new ExecutionResult(true, steps, null, null,
                    outputs == null ? Map.of() : outputs);
        }
        public static ExecutionResult failed(String failedUnitId, String error,
                                              List<ToolCallLog> steps) {
            return new ExecutionResult(false, steps, failedUnitId, error, Map.of());
        }
    }

    /**
     * 统一执行入口。
     *
     * @param unitId     Unit 业务 UUID
     * @param paramsJson 入参 JSON（{@code {"var":"value"}}），可为空
     */
    public ExecutionResult executePlanStep(String unitId, String paramsJson) {
        Map<String, String> scope = parseMap(paramsJson);
        return executeUnit(unitId, scope, new HashSet<>(), 0);
    }

    // ========================================================================
    // 递归 / 脚本化分流
    // ========================================================================

    private ExecutionResult executeUnit(String unitId, Map<String, String> scope,
                                        Set<String> path, int depth) {
        if (depth > maxDepth) {
            return ExecutionResult.failed(unitId, "递归深度超限(>" + maxDepth + ")", List.of());
        }
        if (path.contains(unitId)) {
            return ExecutionResult.failed(unitId, "检测到循环引用", List.of());
        }

        var opt = unitRepository.findByUnitId(unitId);
        if (opt.isEmpty()) {
            return ExecutionResult.failed(unitId, "Unit 不存在", List.of());
        }
        UnitNode unit = opt.get();

        if ("ARCHIVED".equalsIgnoreCase(unit.getStatus())) {
            return executeFallback(unitId, scope, path, depth, "Unit 已归档");
        }

        path.add(unitId);
        try {
            // 原子工具 / MCP 工具：直接调用实际工具
            if (unit.getToolName() != null && !unit.getToolName().isBlank()) {
                return executeTool(unit, scope);
            }
            if (unit.isScriptable()) {
                List<ToolCallLog> script = parseScript(unit.getScriptJson());
                if (script != null && !script.isEmpty()) {
                    return executeScript(script, scope);
                }
                // scriptable 标记但无脚本：降级到递归展开
            }
            return executeChildren(unitId, scope, path, depth);
        } finally {
            path.remove(unitId);
        }
    }

    /** 递归展开：按 CONTAINS 有序树执行，跳过被禁用目标，失败时尝试 FALLBACK。 */
    private ExecutionResult executeChildren(String unitId, Map<String, String> scope,
                                            Set<String> path, int depth) {
        List<String> childIds = unitRepository.findChildUnitIdsOrdered(unitId);
        if (childIds == null || childIds.isEmpty()) {
            // 无子步骤且不可脚本化：视为空计划（成功但不做任何事）
            return ExecutionResult.success(List.of());
        }

        Set<String> disabled = new HashSet<>(unitRepository.findDisabledUnitIds(unitId));
        List<ToolCallLog> allLogs = new ArrayList<>();
        Map<String, String> accumulated = new LinkedHashMap<>();

        for (String childId : childIds) {
            UnitNode child = unitRepository.findByUnitId(childId).orElse(null);
            String stepName = (child != null && child.getGoal() != null && !child.getGoal().isBlank())
                    ? child.getGoal() : childId;

            ExecutionResult r;
            if (disabled.contains(childId)) {
                // §4.2：FALLBACK 仅在被 DISABLES 时启用
                r = executeFallback(childId, scope, path, depth + 1, "步骤被 DISABLES 禁用");
                if (!r.success()) return r;
            } else {
                // §8.3：子步骤执行失败 → 记录并停止后续，不降级 FALLBACK
                r = executeUnit(childId, scope, path, depth + 1);
                if (!r.success()) {
                    return ExecutionResult.failed(childId, r.errorMessage(), allLogs);
                }
            }
            allLogs.addAll(r.executedSteps());
            mergeOutputs(scope, accumulated, stepName, r.outputs());
        }
        return ExecutionResult.success(allLogs, accumulated);
    }

    /** 合并子步骤输出：bare 变量供 `$var`，`stepName.var` 供 `$stepName.var`（§8.3 数据流）。 */
    private void mergeOutputs(Map<String, String> scope, Map<String, String> accumulated,
                              String stepName, Map<String, String> outputs) {
        if (outputs == null || outputs.isEmpty()) return;
        for (Map.Entry<String, String> e : outputs.entrySet()) {
            accumulated.putIfAbsent(e.getKey(), e.getValue());
            scope.putIfAbsent(e.getKey(), e.getValue());
            scope.put(stepName + "." + e.getKey(), e.getValue());
        }
    }

    /** 降级替代：只使用一次，不递归展开 FALLBACK 的 FALLBACK（防循环，靠 path+深度兜底）。 */
    private ExecutionResult executeFallback(String unitId, Map<String, String> scope,
                                            Set<String> path, int depth, String reason) {
        List<String> fallbackIds = unitRepository.findFallbackUnitIds(unitId);
        if (fallbackIds == null || fallbackIds.isEmpty()) {
            return ExecutionResult.failed(unitId, reason + "（无可用 FALLBACK）", List.of());
        }
        for (String fbId : fallbackIds) {
            ExecutionResult r = executeUnit(fbId, scope, path, depth);
            if (r.success()) return r;
        }
        return ExecutionResult.failed(unitId, reason + "（FALLBACK 均失败）", List.of());
    }

    // ========================================================================
    // 原子工具执行
    // ========================================================================

    /** 直接执行原子工具 / MCP 工具：把 Unit.params 解析后作为工具入参。 */
    private ExecutionResult executeTool(UnitNode unit, Map<String, String> scope) {
        String toolName = unit.getToolName();
        Map<String, String> params = parseMap(unit.getParamsJson());
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            resolved.put(e.getKey(), resolveVariables(e.getValue(), scope));
        }

        String args;
        try {
            args = objectMapper.writeValueAsString(resolved);
        } catch (Exception e) {
            args = "{}";
        }

        ToolCallback tool = findTool(toolName);
        if (tool == null) {
            return ExecutionResult.failed(unit.getUnitId(), "工具不存在: " + toolName, List.of());
        }

        long start = System.currentTimeMillis();
        try {
            String result = tool.call(args);
            long elapsed = System.currentTimeMillis() - start;
            boolean ok = !isFailureResult(result);
            log.info("[{}] {} ({}ms) {}", toolName, ok ? "✅" : "❌", elapsed,
                    result == null ? "" : result.replace('\n', ' '));
            List<ToolCallLog> logs = List.of(new ToolCallLog(toolName, args, result, ok, elapsed));
            return ok ? ExecutionResult.success(logs, extractOutputs(unit, result))
                      : ExecutionResult.failed(unit.getUnitId(), "工具返回失败", logs);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            List<ToolCallLog> logs = List.of(new ToolCallLog(toolName, args, e.getMessage(), false, elapsed));
            return ExecutionResult.failed(unit.getUnitId(), e.getMessage(), logs);
        }
    }

    /** 从 outputSignature 提取产出变量（单变量映射结果，多变量退回 result 键）。 */
    private Map<String, String> extractOutputs(UnitNode unit, String result) {
        Map<String, String> sig = parseMap(unit.getOutputSignatureJson());
        if (sig.isEmpty() || result == null) return Map.of();
        Map<String, String> outputs = new LinkedHashMap<>();
        if (sig.size() == 1) {
            outputs.put(sig.keySet().iterator().next(), result);
        } else {
            outputs.put("result", result);
        }
        return outputs;
    }

    // ========================================================================
    // 脚本化执行
    // ========================================================================

    private ExecutionResult executeScript(List<ToolCallLog> script, Map<String, String> scope) {
        List<ToolCallLog> executed = new ArrayList<>();
        for (ToolCallLog step : script) {
            String resolvedArgs = resolveVariables(step.args(), scope);
            ToolCallback tool = findTool(step.toolName());
            if (tool == null) {
                executed.add(new ToolCallLog(step.toolName(), resolvedArgs,
                        "工具不存在", false, 0));
                return ExecutionResult.failed(step.toolName(), "工具不存在: " + step.toolName(), executed);
            }

            long start = System.currentTimeMillis();
            try {
                String result = tool.call(resolvedArgs);
                long elapsed = System.currentTimeMillis() - start;
                boolean ok = !isFailureResult(result);
                log.info("[{}] {} ({}ms) {}", step.toolName(), ok ? "✅" : "❌", elapsed,
                        result == null ? "" : result.replace('\n', ' '));
                executed.add(new ToolCallLog(step.toolName(), resolvedArgs, result, ok, elapsed));
                if (!ok) {
                    return ExecutionResult.failed(step.toolName(), "工具返回失败", executed);
                }
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                executed.add(new ToolCallLog(step.toolName(), resolvedArgs,
                        e.getMessage(), false, elapsed));
                return ExecutionResult.failed(step.toolName(), e.getMessage(), executed);
            }
        }
        return ExecutionResult.success(executed);
    }

    // ========================================================================
    // 工具 / 变量 / 序列化辅助
    // ========================================================================

    private ToolCallback findTool(String name) {
        if (name == null) return null;
        return toolMap.get(name);
    }

    /** 变量替换：$varName / $stepName.varName → 实际值（字面替换，非正则，长键优先）。 */
    private String resolveVariables(String args, Map<String, String> scope) {
        if (args == null || args.isBlank() || scope == null || scope.isEmpty()) return args;
        List<String> keys = new ArrayList<>(scope.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        String result = args;
        for (String key : keys) {
            result = result.replace("$" + key, scope.get(key));
        }
        return result;
    }

    /** 检查返回字符串是否包含失败标识（来自配置 myhelper.execution.failure-markers）。 */
    private boolean isFailureResult(String result) {
        if (result == null || result.isBlank()) return false;
        List<String> markers = props.execution().failureMarkers();
        if (markers == null) return false;
        for (String marker : markers) {
            if (result.contains(marker)) return true;
        }
        return false;
    }

    private List<ToolCallLog> parseScript(String scriptJson) {
        if (scriptJson == null || scriptJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(scriptJson, new TypeReference<List<ToolCallLog>>() {});
        } catch (Exception e) {
            log.warn("⚠️ 解析 script 失败: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, String> parseMap(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("⚠️ 解析参数 JSON 失败: {} → {}", json, e.getMessage());
            return new HashMap<>();
        }
    }
}
