package com.example.myhelper.capability;

import com.example.myhelper.orchestration.TaskPlan;
import java.util.Map;
import java.util.Objects;

/** 固化适用性判断端口；循环传入本次事实，独立实现返回是否可以使用指定能力。 */
@FunctionalInterface
public interface CapabilityUsePort {
    /** 判断失败或外部服务异常向上传递，不能伪装为已经允许执行。 */
    UseDecision evaluate(UseRequest request);

    public record UseRequest(
            /** 跨多次执行保留的任务身份。 */
            String taskId,
            /** 当前实际根运行身份。 */
            String rootRunId,
            /** 当前子任务或求解运行身份。 */
            String runId,
            /** 本次准备交给固化能力完成的目标。 */
            String goal,
            /** 已绑定的实际参数，冻结嵌套对象和数组。 */
            Map<String, Object> arguments,
            /** 本次准备执行的固定能力版本。 */
            CapabilityLookup.Ref ref,
            /** 当前任务状态、恢复事项等上下文，使用不可变 JSON 快照。 */
            Map<String, Object> context) {
        // 明确运行身份并冻结输入，外部判断不能改写任务的共享状态。
        public UseRequest {
            // 同一任务的多次运行需要稳定关联，不能用缺失身份猜测。
            TaskPlan.requireText(taskId, "taskId");
            // 当前根运行用于关联执行记录。
            TaskPlan.requireText(rootRunId, "rootRunId");
            // 子任务身份明确指出当前判断服务哪一次求解。
            TaskPlan.requireText(runId, "runId");
            // 适用性不能仅凭能力名判断，必须保留本次目标。
            TaskPlan.requireText(goal, "goal");
            // 与计划参数保持相同的深不可变 JSON 语义。
            arguments = TaskPlan.immutableMap(arguments);
            // 必须判断明确的固定版本，不隐式选择当前最新能力。
            Objects.requireNonNull(ref, "ref");
            // 复制恢复信息及其他上下文，避免并发变化影响正在执行的判断。
            context = TaskPlan.immutableMap(context);
        }
    }

    public record UseDecision(
            /** 本次输入、状态及执行范围是否允许使用该能力。 */
            boolean usable,
            /** 判断原因；允许时可以为空，拒绝原因由判断实现提供。 */
            String reason) {
    }
}
