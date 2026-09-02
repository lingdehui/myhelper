package com.example.myhelper.memory.unit;

import com.example.myhelper.config.MyHelperProperties;
import com.example.myhelper.memory.graph.UnitNode;
import com.example.myhelper.memory.graph.UnitRepository;
import com.example.myhelper.memory.vector.episode.ToolCallLog;
import com.example.myhelper.world.ContextUnit;
import com.example.myhelper.world.EnvironmentStateService;
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
 * <p>统一入口 {@link #executePlanStep(String, String)}，执行唯一的递归 Unit 树：
 * <ul>
 *   <li>先匹配 Unit 引用的 REQUIREMENT ContextUnit，必要时调用刷新 Unit；</li>
 *   <li>再按 CONTAINS 有序树递归执行，带防环与深度限制；</li>
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
    private final EnvironmentStateService environmentState;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 全量工具名 → ToolCallback（由应用启动时注入，含 MCP + 本地 + 动态工具） */
    private volatile Map<String, ToolCallback> toolMap = Collections.emptyMap();

    /** 递归深度上限（超大型任务可临时调大） */
    @Value("${myhelper.execution.max-depth:5}")
    private int maxDepth;

    /** 探索模式声明类型（VALIDATE/OPTIMIZE），非探索时为 null。由 TurnProcessor 在探索 Turn 设置。 */
    private volatile ExplorationRecord.DeclareType explorationDeclareType = null;

    public UniversalUnitExecutor(UnitRepository unitRepository, UnitStore unitStore, MyHelperProperties props,
                                 EnvironmentStateService environmentState) {
        this.unitRepository = unitRepository;
        this.unitStore = unitStore;
        this.props = props;
        this.environmentState = environmentState;
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
            childNames = List.of();
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
        ExecutionTrace trace = new ExecutionTrace();
        return executeUnit(unitId, scope, new HashSet<>(), 0, null, trace, false);
    }

    /** 语义缓存直达入口：与普通 AI 工具调用不同，必须先通过 ContextUnit 前置条件。 */
    public ExecutionResult executeDirectPlanStep(String unitId, String paramsJson) {
        Map<String, String> scope = parseMap(paramsJson);
        ExecutionTrace trace = new ExecutionTrace();
        return executeUnit(unitId, scope, new HashSet<>(), 0, null, trace, true);
    }

    // ========================================================================
    // Context 条件 / 递归执行
    // ========================================================================

    private ExecutionResult executeUnit(String unitId, Map<String, String> scope,
                                        Set<String> path, int depth, String invocationJson,
                                        ExecutionTrace trace, boolean enforceGuard) {
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

        // CONTAINS 的调用参数同样适用于组合 Unit：先在父作用域解析，再绑定进子作用域。
        // TOOL 仍在 executeTool 中读取原始 invocationJson，避免重复处理。
        Map<String, String> effectiveScope = scope;
        if ((unit.getToolName() == null || unit.getToolName().isBlank())
                && invocationJson != null && !invocationJson.isBlank()) {
            effectiveScope = new LinkedHashMap<>(scope);
            Map<String, String> invocation = parseMap(invocationJson);
            for (Map.Entry<String, String> entry : invocation.entrySet()) {
                effectiveScope.put(entry.getKey(), resolveVariables(entry.getValue(), scope));
            }
        }

        if (enforceGuard) {
            GuardResult guard = evaluateRequiredContext(unit, effectiveScope, path, depth, trace);
            if (guard != GuardResult.SATISFIED) {
                return ExecutionResult.failed(unitId, "直接执行条件未满足: " + guard, List.of());
            }
        }

        if ("ARCHIVED".equalsIgnoreCase(unit.getStatus())) {
            return executeFallback(unitId, scope, path, depth, "Unit 已归档", trace);
        }

        path.add(unitId);
        try {
            // 原子工具 / MCP 工具：直接调用实际工具
            if (unit.getToolName() != null && !unit.getToolName().isBlank()) {
                return executeTool(unit, effectiveScope, invocationJson);
            }
            ExecutionResult result = executeChildren(unitId, effectiveScope, path, depth, trace);
            if (result.success() && !verifyExpectedContext(unit, effectiveScope, path, depth, trace)) {
                return ExecutionResult.failed(unitId, "执行后环境状态未达到预期", result.executedSteps());
            }
            return result;
        } finally {
            path.remove(unitId);
        }
    }

    /** 递归展开：按 CONTAINS 有序树执行，跳过被禁用目标，失败时尝试 FALLBACK。 */
    private ExecutionResult executeChildren(String unitId, Map<String, String> scope,
                                            Set<String> path, int depth, ExecutionTrace trace) {
        List<String> rows = unitRepository.findChildInvocationRows(unitId);
        if (rows == null || rows.isEmpty()) {
            // 无子步骤且不是原子工具：视为空计划
            return ExecutionResult.success(List.of());
        }

        Set<String> disabled = new HashSet<>(unitRepository.findDisabledUnitIds(unitId));
        List<ToolCallLog> allLogs = new ArrayList<>();
        Map<String, String> accumulated = new LinkedHashMap<>();

        for (String row : rows) {
            String[] parts = row.split("\\|", -1);
            String childId = parts[1];
            String invocationJson = decodeArguments(parts.length > 2 ? parts[2] : "");
            UnitNode child = unitRepository.findByUnitId(childId).orElse(null);
            String stepName = (child != null && child.getGoal() != null && !child.getGoal().isBlank())
                    ? child.getGoal() : childId;

            ExecutionResult r;
            if (disabled.contains(childId)) {
                // §4.2：FALLBACK 仅在被 DISABLES 时启用
                r = executeFallback(childId, scope, path, depth + 1, "步骤被 DISABLES 禁用", trace);
                if (!r.success()) return r;
            } else {
                // §8.3：子步骤执行失败 → 记录并停止后续，不降级 FALLBACK
                r = executeUnit(childId, scope, path, depth + 1, invocationJson, trace, false);
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
                                            Set<String> path, int depth, String reason, ExecutionTrace trace) {
        List<String> fallbackIds = unitRepository.findFallbackUnitIds(unitId);
        if (fallbackIds == null || fallbackIds.isEmpty()) {
            return ExecutionResult.failed(unitId, reason + "（无可用 FALLBACK）", List.of());
        }
        for (String fbId : fallbackIds) {
            ExecutionResult r = executeUnit(fbId, scope, path, depth, null, trace, true);
            if (r.success()) return r;
        }
        return ExecutionResult.failed(unitId, reason + "（FALLBACK 均失败）", List.of());
    }

    private GuardResult evaluateRequiredContext(UnitNode unit, Map<String, String> scope,
                                                Set<String> path, int depth, ExecutionTrace trace) {
        if (!Unit.DirectExecutionStatus.ACTIVE.name().equals(unit.getDirectExecutionStatus())) {
            return GuardResult.NOT_ACTIVE;
        }
        for (String requirementId : parseStringList(unit.getRequiredContextIdsJson())) {
            ContextUnit requirement = environmentState.getContext(requirementId).orElse(null);
            if (requirement == null || requirement.role() != ContextUnit.Role.REQUIREMENT) return GuardResult.UNKNOWN;
            GuardResult result = match(requirement);
            if ((result == GuardResult.UNKNOWN || result == GuardResult.STALE)
                    && requirement.refreshUnitId() != null
                    && trace.refreshedContextIds.add(requirementId)
                    && trace.refreshCount++ < 5) {
                ExecutionResult refresh = executeUnit(requirement.refreshUnitId(), scope, path, depth + 1,
                        null, trace, false);
                if (refresh.success()) result = match(requirement);
            }
            if (result != GuardResult.SATISFIED) return result;
        }
        return GuardResult.SATISFIED;
    }

    private boolean verifyExpectedContext(UnitNode unit, Map<String, String> scope, Set<String> path,
                                          int depth, ExecutionTrace trace) {
        for (String expectationId : parseStringList(unit.getExpectedContextIdsJson())) {
            ContextUnit expectation = environmentState.getContext(expectationId).orElse(null);
            if (expectation == null || expectation.role() != ContextUnit.Role.EXPECTATION) return false;
            GuardResult result = match(expectation);
            if (result != GuardResult.SATISFIED && expectation.refreshUnitId() != null
                    && trace.refreshedContextIds.add(expectationId) && trace.refreshCount++ < 5) {
                ExecutionResult refresh = executeUnit(expectation.refreshUnitId(), scope, path, depth + 1,
                        null, trace, false);
                if (refresh.success()) result = match(expectation);
            }
            if (result != GuardResult.SATISFIED) return false;
        }
        return true;
    }

    private GuardResult match(ContextUnit clause) {
        ContextUnit evidence = environmentState.findCurrentValue(clause.subjectId(), clause.predicate()).orElse(null);
        if (evidence == null || evidence.stateId() == null) return GuardResult.UNKNOWN;
        ContextUnit actualState = environmentState.getState(evidence.stateId()).orElse(null);
        ContextUnit expectedState = environmentState.getState(clause.stateId()).orElse(null);
        if (actualState == null || expectedState == null) return GuardResult.UNKNOWN;
        long age = evidence.observedAt() == null ? Long.MAX_VALUE
                : Math.max(0L, System.currentTimeMillis() - evidence.observedAt().toEpochMilli());
        if (clause.maximumAgeMillis() > 0 && age > clause.maximumAgeMillis()) return GuardResult.STALE;
        if (evidence.effectiveConfidence(java.time.Instant.now()) < clause.requiredConfidence()) return GuardResult.STALE;
        Object actualValue = actualState.objectId() != null ? actualState.objectId() : actualState.literalValue();
        Object expected = expectedState.objectId() != null ? expectedState.objectId() : expectedState.literalValue();
        boolean matched = switch (clause.operator()) {
            case EXISTS -> actualValue != null;
            case EQ -> evidence.stateId().equals(clause.stateId());
            case NOT_EQ -> !evidence.stateId().equals(clause.stateId());
            case IN, SUPPORTS -> actualValue instanceof java.util.Collection<?> c
                    ? c.stream().anyMatch(v -> java.util.Objects.equals(String.valueOf(v), String.valueOf(expected)))
                    : String.valueOf(actualValue).contains(String.valueOf(expected));
            case GREATER_THAN -> number(actualValue) > number(expected);
            case LESS_THAN -> number(actualValue) < number(expected);
        };
        return matched ? GuardResult.SATISFIED : GuardResult.UNSATISFIED;
    }

    private double number(Object value) {
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (Exception e) { return Double.NaN; }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private enum GuardResult { SATISFIED, UNSATISFIED, UNKNOWN, STALE, NOT_ACTIVE }
    private static final class ExecutionTrace {
        private final Set<String> refreshedContextIds = new HashSet<>();
        private int refreshCount;
    }

    // ========================================================================
    // 原子工具执行
    // ========================================================================

    /** 直接执行原子工具 / MCP 工具：把 Unit.params 解析后作为工具入参。 */
    private ExecutionResult executeTool(UnitNode unit, Map<String, String> scope, String invocationJson) {
        String toolName = unit.getToolName();
        Map<String, String> params = parseMap(invocationJson == null ? unit.getParamsJson() : invocationJson);
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

    /** 从 outputSignature 提取产出变量；值为 $.path 时从 JSON 结果选择字段，旧类型声明取整个结果。 */
    private Map<String, String> extractOutputs(UnitNode unit, String result) {
        Map<String, String> sig = parseMap(unit.getOutputSignatureJson());
        if (sig.isEmpty() || result == null) return Map.of();
        Map<String, String> outputs = new LinkedHashMap<>();
        JsonNodeHolder json = parseResultJson(result);
        boolean selectedAny = false;
        boolean hasSelector = false;
        for (Map.Entry<String, String> entry : sig.entrySet()) {
            String selector = entry.getValue();
            if (selector != null && selector.startsWith("$.") && json.node != null) {
                hasSelector = true;
                com.fasterxml.jackson.databind.JsonNode selected = selectJson(json.node, selector);
                if (selected != null && !selected.isMissingNode() && !selected.isNull()) {
                    outputs.put(entry.getKey(), selected.isValueNode() ? selected.asText() : selected.toString());
                    selectedAny = true;
                }
            } else if (sig.size() == 1) {
                outputs.put(entry.getKey(), result);
            }
        }
        // 兼容旧数据：多个仅声明类型、没有 JSON 选择器的签名，沿用统一 result 输出。
        if (!hasSelector && sig.size() > 1) outputs.put("result", result);
        if (hasSelector && !selectedAny) log.debug("Unit {} 的输出选择器未命中结果", unit.getUnitId());
        return outputs;
    }

    private JsonNodeHolder parseResultJson(String result) {
        try { return new JsonNodeHolder(objectMapper.readTree(result)); }
        catch (Exception e) { return new JsonNodeHolder(null); }
    }

    private com.fasterxml.jackson.databind.JsonNode selectJson(com.fasterxml.jackson.databind.JsonNode root,
                                                                String selector) {
        com.fasterxml.jackson.databind.JsonNode current = root;
        for (String part : selector.substring(2).split("\\.")) {
            if (current == null || !current.isObject()) return null;
            current = current.get(part);
        }
        return current;
    }

    private record JsonNodeHolder(com.fasterxml.jackson.databind.JsonNode node) {}

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

    private String decodeArguments(String encoded) {
        if (encoded == null || encoded.isBlank()) return "{}";
        try { return new String(java.util.Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { return "{}"; }
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
