package com.example.myhelper.memory.vector.episode;

import com.example.myhelper.config.MyHelperProperties;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 计划脚本执行器（决策1：稳定度高时升级成脚本）。
 *
 * <p>当 Unit 的 scriptable=true（successCount≥5 且 stability>0.9）时，
 * 跳过 AI 决策，直接按 script 脚本逐步调用工具（ToolCallback.call()）。</p>
 *
 * <h3>变量替换</h3>
 * <p>script 的 args 里的 <code>$varName</code> 占位符替换为 PlanMatcher 提取的变量值。
 * 例：<code>{"name":"$contact"}</code> → <code>{"name":"张三"}</code></p>
 *
 * <h3>失败处理（决策2：失败步重新规划后继续）</h3>
 * <p>逐步执行，某步失败时：
 * <ul>
 *   <li>工具抛异常 → 记录失败步位置</li>
 *   <li>返回字符串含失败标识 → 视为失败（标识来自配置 myhelper.execution.failure-markers）</li>
 *   <li>返回字符串不含失败标识 → 视为成功</li>
 * </ul>
 * 返回已执行的步骤日志，上层可以据此分段继续。</p> */
@Service
public class PlanExecutor {

    private final MyHelperProperties props;

    public PlanExecutor(MyHelperProperties props) {
        this.props = props;
    }

    /**
     * 执行结果。
     *
     * @param success       是否全部成功
     * @param executedSteps 已执行的步骤日志（含成功的和失败的）
     * @param failedStepIndex 失败步位置（-1=全部成功；≥0=第 N 步失败）
     * @param errorMessage  失败错误信息（成功时为 null）
     */
    public record ExecutionResult(boolean success, List<ToolCallLog> executedSteps,
                                   int failedStepIndex, String errorMessage) {
        public static ExecutionResult success(List<ToolCallLog> steps) {
            return new ExecutionResult(true, steps, -1, null);
        }
        public static ExecutionResult failed(List<ToolCallLog> steps, int failedIndex, String error) {
            return new ExecutionResult(false, steps, failedIndex, error);
        }
    }

    /**
     * 按计划脚本逐步执行（稳定度高时调用，跳过 AI）。
     *
     * @param script    预编译线性脚本（Unit.script 或 Episode.toolCalls）
     * @param variables 变量值（PlanMatcher 提取，key=变量名, value=变量值）
     * @param allTools  所有可用工具
     * @return 执行结果（含已执行步骤日志 + 失败步位置）
     */
    public ExecutionResult executeScript(List<ToolCallLog> script, Map<String, String> variables,
                                          ToolCallback[] allTools) {
        if (script == null || script.isEmpty()) {
            return ExecutionResult.failed(new ArrayList<>(), -1, "脚本为空");
        }
        return executeFromStep(script, 0, variables, allTools);
    }

    /** 从失败步之后继续执行剩余步骤（分段继续，决策2） */
    public ExecutionResult executeFromStep(List<ToolCallLog> script, int fromStep,
                                            Map<String, String> variables,
                                            ToolCallback[] allTools) {
        List<ToolCallLog> executed = new ArrayList<>();

        if (script == null || fromStep >= script.size()) {
            return ExecutionResult.success(executed);
        }

        for (int i = fromStep; i < script.size(); i++) {
            ToolCallLog step = script.get(i);
            String resolvedArgs = resolveVariables(step.args(), variables);
            ToolCallback tool = findTool(step.toolName(), allTools);
            if (tool == null) {
                executed.add(new ToolCallLog(step.toolName(), resolvedArgs,
                        "工具不存在: " + step.toolName(), false, 0));
                return ExecutionResult.failed(executed, i, "工具不存在: " + step.toolName());
            }
            long start = System.currentTimeMillis();
            try {
                String result = tool.call(resolvedArgs);
                long elapsed = System.currentTimeMillis() - start;
                if (isFailureResult(result)) {
                    executed.add(new ToolCallLog(step.toolName(), resolvedArgs, result, false, elapsed));
                    return ExecutionResult.failed(executed, i, "工具返回失败: " + trimResult(result));
                }
                executed.add(new ToolCallLog(step.toolName(), resolvedArgs, result, true, elapsed));
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                executed.add(new ToolCallLog(step.toolName(), resolvedArgs,
                        e.getMessage(), false, elapsed));
                return ExecutionResult.failed(executed, i, e.getMessage());
            }
        }
        return ExecutionResult.success(executed);
    }

    /** 变量替换：$varName → 实际值 */
    private String resolveVariables(String args, Map<String, String> variables) {
        if (args == null || variables == null || variables.isEmpty()) return args;
        String result = args;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("$" + entry.getKey(), entry.getValue());
        }
        return result;
    }

    /** 按名查找工具 */
    private ToolCallback findTool(String name, ToolCallback[] allTools) {
        if (allTools == null || name == null) return null;
        for (ToolCallback tc : allTools) {
            if (tc.getToolDefinition().name().equals(name)) return tc;
        }
        return null;
    }

    /** 检查工具返回字符串是否包含失败标识（来自配置 myhelper.execution.failure-markers） */
    private boolean isFailureResult(String result) {
        if (result == null || result.isBlank()) return false;
        for (String marker : props.execution().failureMarkers()) {
            if (result.contains(marker)) return true;
        }
        return false;
    }

    /** 截断返回字符串用于错误日志 */
    private static String trimResult(String result) {
        if (result == null) return "(null)";
        String oneLine = result.replace("\n", " ").trim();
        return oneLine.length() > 100 ? oneLine.substring(0, 100) + "..." : oneLine;
    }
}
