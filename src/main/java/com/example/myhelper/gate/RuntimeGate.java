package com.example.myhelper.gate;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import java.util.Map;

/** 本次运行的动作许可与结果验收，由应用提供业务规则。 */
public interface RuntimeGate {
    /** ref 为空时检查普通工具，否则检查固定版本能力。 */
    boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref);

    /** 检查实际结果是否满足目标。 */
    boolean verify(String goal, Map<String, Object> args, Object output);

    /** 需要按新指令验收时，由业务实现覆盖并核对完整指令。 */
    default boolean verify(String goal, Map<String, Object> args, Object output, Map<String, Object> instructions) {
        return verify(goal, args, output);
    }

    /** 绑定完成后的资格检查，可在实际派发前多次读取。 */
    default boolean allowTool(ToolCall call) {
        return allow(call.goal(), call.args(), null);
    }

    /** 紧邻实际派发，可消耗仅用于本次操作的一次性许可。 */
    default boolean beforeToolDispatch(ToolCall call) {
        return true;
    }

    /** 依据实际工具身份和本次回执验收单个工具步骤。 */
    default boolean verifyTool(ToolCall call, Object output, Map<String, Object> instructions) {
        return verify(call.goal(), call.args(), output, instructions);
    }

    /** 验收模型完成答复或固定能力的组合结果。 */
    default boolean verifyCompletion(CompletionContext context, Object output, Map<String, Object> instructions) {
        return verify(context.goal(), context.args(), output, instructions);
    }

    record ToolCall(
            /** 计划中使用的工具调用名。 */
            String toolName,
            /** 宿主已绑定的实际工具身份，按名称执行的旧工具允许为空。 */
            ToolRef toolRef,
            /** 本步骤目标。 */
            String goal,
            /** 编排绑定完成的本次参数。 */
            Map<String, Object> args,
            /** 稳定任务身份。 */
            String taskId,
            /** 当前根运行身份。 */
            String rootRunId,
            /** 当前子任务运行身份。 */
            String runId,
            /** 用于关联派发和结果的操作身份。 */
            String operationId) {
    }

    record CompletionContext(
            /** 待验收的任务或组合能力目标。 */
            String goal,
            /** 本节点输入参数。 */
            Map<String, Object> args,
            /** 稳定任务身份。 */
            String taskId,
            /** 当前根运行身份。 */
            String rootRunId,
            /** 当前子任务运行身份。 */
            String runId,
            /** 负责本次验收的链路节点身份。 */
            String operationId) {
    }
}
