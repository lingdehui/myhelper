package com.example.myhelper.orchestration;

import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.llm.ModelAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 将一份计划中的逻辑分支关联到本次运行绑定；不执行模型，不占模型请求许可。 */
final class PlanModelBindings implements AutoCloseable {
    /** 需要模型时保存分叉和汇合；纯工具步骤统一沿用当前 owner。 */
    private final PlanBranches.Layout layout;
    /** 当前计划所属分支；计划执行结束后仍由它继续下一轮。 */
    private final ModelAdapter.Binding owner;
    /** 本根运行的外部模型端口接线，不保存模型候选或占用计数。 */
    private final RunModelAccess models;
    /** 按逻辑分支保存需要模型求解的目标，顺序沿用计划声明。 */
    private final Map<String, List<ModelAdapter.RoutingTask>> tasksByBranch;
    /** 区分不同轮次、递归任务以及固化展开中的同名逻辑分支。 */
    private final String scopeId = UUID.randomUUID().toString();
    /** 本计划仍有效的绑定；根条目引用已有 owner，不由本计划释放。 */
    private final Map<String, ModelAdapter.Binding> bindings = new LinkedHashMap<>();
    /** 已开始或已结束的分叉，避免同一组被两个并发步骤重复分配。 */
    private final Set<String> startedForks = new LinkedHashSet<>();
    /** 已归还的分叉；汇合后不能再次创建旧分支。 */
    private final Set<String> closedForks = new LinkedHashSet<>();
    /** 任一次局部归还失败都要交给最终收尾，不能变成可自动重规划的普通执行失败。 */
    private RuntimeException releaseFailure;

    PlanModelBindings(TaskPlan plan, ModelAdapter.Binding owner, RunModelAccess models, boolean needsModels) {
        this(plan, owner, models, needsModels, Map.of());
    }

    PlanModelBindings(TaskPlan plan, ModelAdapter.Binding owner, RunModelAccess models,
                      Map<String, List<ModelAdapter.RoutingTask>> tasksByStep) {
        this(plan, owner, models, !tasksByStep.isEmpty(), tasksByStep);
    }

    private PlanModelBindings(TaskPlan plan, ModelAdapter.Binding owner, RunModelAccess models,
                              boolean needsModels, Map<String, List<ModelAdapter.RoutingTask>> tasksByStep) {
        // 只有实际还需要模型的计划，才要求明确模型分叉和汇合归属。
        if (needsModels) {
            // 必须在派发步骤前检查，不能执行一半才发现父模型归属有歧义。
            this.layout = PlanBranches.analyze(plan);
        } else {
            // 纯工具只需要合法 DAG；交叉依赖不应被模型分支分析额外拒绝。
            this.layout = inheritedLayout(plan);
        }
        // 保存本轮父分支，而不覆盖它的模型或上下文。
        this.owner = owner;
        // 各计划只保存身份映射，真实模型绑定由外部适配器维护。
        this.models = models;
        this.tasksByBranch = groupTasksByBranch(layout, tasksByStep);
        // 无分叉的步骤直接使用当前分支。
        bindings.put(PlanBranches.ROOT_BRANCH, owner);
    }

    // 输入按计划声明顺序收集，同一分支上的多项任务也保持这个顺序。
    private static Map<String, List<ModelAdapter.RoutingTask>> groupTasksByBranch(
            PlanBranches.Layout layout, Map<String, List<ModelAdapter.RoutingTask>> tasksByStep) {
        Map<String, List<ModelAdapter.RoutingTask>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, List<ModelAdapter.RoutingTask>> step : tasksByStep.entrySet()) {
            String branchId = layout.branch(step.getKey());
            List<ModelAdapter.RoutingTask> branchTasks = grouped.get(branchId);
            if (branchTasks == null) {
                branchTasks = new ArrayList<>();
                grouped.put(branchId, branchTasks);
            }
            branchTasks.addAll(step.getValue());
        }
        for (Map.Entry<String, List<ModelAdapter.RoutingTask>> branch : grouped.entrySet()) {
            branch.setValue(List.copyOf(branch.getValue()));
        }
        return Map.copyOf(grouped);
    }

    // 纯工具沿用所在分支；具体步骤和并发关系仍由执行图的依赖边表达。
    private static PlanBranches.Layout inheritedLayout(TaskPlan plan) {
        // 保留步骤身份校验，不为工具编造模型子分支。
        Map<String, String> stepBranches = new LinkedHashMap<>();
        // TaskPlan 已检查依赖无环、步骤引用及参数来源，此处不再分析模型归属。
        for (TaskPlan.Step step : plan.steps()) {
            // 当前计划根身份在运行时会映射为传入 owner，嵌套纯工具也保留原上下文。
            stepBranches.put(step.id(), PlanBranches.ROOT_BRANCH);
        }
        // 不创建模型分叉，因此 ready 和 close 都不会申请或释放模型绑定。
        return new PlanBranches.Layout(stepBranches, List.of());
    }

    // 返回可写入执行图的身份，尚未分配模型也能定位同一逻辑分支。
    String branchId(String stepId) {
        return runtimeId(layout.branch(stepId));
    }

    // 即使此步骤不调用模型，执行图也能还原它所属分支的父子关系。
    String parentBranchId(String stepId) {
        String logicalBranchId = layout.branch(stepId);
        // 当前计划的主线可能已处于上一层 B 分支，保留其真实父身份。
        if (PlanBranches.ROOT_BRANCH.equals(logicalBranchId)) {
            return owner.parentId() == null ? "" : owner.parentId();
        }
        // 其他分支的父归属已经在布局中明确。
        return runtimeId(layout.forkForBranch(logicalBranchId).parentBranchId());
    }

    // 依赖全部完成后才调用；同步范围只覆盖分配和归还，不等待任何 Future。
    synchronized ModelAdapter.Binding ready(String stepId) {
        // 先关闭在本节点汇合的子分叉，再恢复它们原来的父绑定。
        for (PlanBranches.Fork fork : layout.closingAt(stepId)) {
            closeFork(fork);
        }
        // 线性步骤命中旧绑定；首次进入子分支才分配整组。
        return ensure(layout.branch(stepId));
    }

    // 同组全部子分支在一个临界区内分配，选择不依赖哪个子任务执行得快。
    private ModelAdapter.Binding ensure(String logicalBranchId) {
        // 父绑定和已经开始的子分支直接复用。
        ModelAdapter.Binding existing = bindings.get(logicalBranchId);
        if (existing != null) {
            return existing;
        }
        // 结构已经由 PlanBranches 校验，查找该分支所属的分叉。
        PlanBranches.Fork fork = layout.forkForBranch(logicalBranchId);
        // 汇合后的旧分支不可重新启动，避免重复执行或重复计数。
        if (fork == null || closedForks.contains(fork.id()) || startedForks.contains(fork.id())) {
            throw new IllegalStateException(LlmTexts.CLOSED_PLAN_BRANCH_PREFIX + logicalBranchId);
        }
        // 嵌套分叉先取得自己的父分支，保留 A/B 等等待中的原绑定。
        ModelAdapter.Binding parent = ensure(fork.parentBranchId());
        // 保留稳定身份顺序，将各子分支自己的目标随整组申请转交。
        List<ModelAdapter.BranchRequest> children = new ArrayList<>();
        for (String childId : fork.childBranchIds()) {
            children.add(new ModelAdapter.BranchRequest(runtimeId(childId),
                    tasksByBranch.getOrDefault(childId, List.of())));
        }
        // 只有含模型工作的布局才存在子分支；混合计划仍向外部整组申请绑定。
        List<ModelAdapter.Binding> allocated = models.fork(parent, children);
        // 把稳定顺序的结果保存回本计划的逻辑分支表。
        for (int index = 0; index < allocated.size(); index++) {
            bindings.put(fork.childBranchIds().get(index), allocated.get(index));
        }
        // 只有端口调用成功后才标记开始，外部异常不能冒充没有候选。
        startedForks.add(fork.id());
        // 返回当前步骤所需的子分支。
        return bindings.get(logicalBranchId);
    }

    // closingAt 已按内层优先排序，保证释放父分支前没有仍在运行的子分支。
    private void closeFork(PlanBranches.Fork fork) {
        if (closedForks.contains(fork.id())) {
            return;
        }
        // 即使分叉因前驱失败未启动，也只关闭归属，不伪造任何执行。
        for (String childId : fork.childBranchIds()) {
            ModelAdapter.Binding binding = bindings.get(childId);
            if (binding != null) {
                // 先通知外部释放，再移除局部快照；纯工具身份不会触发外部调用。
                release(binding);
                bindings.remove(childId);
            }
        }
        // 整组释放成功后才标记已汇合。
        closedForks.add(fork.id());
    }

    // 根逻辑分支沿用传入身份，其余属于当前计划作用范围。
    private String runtimeId(String logicalBranchId) {
        if (PlanBranches.ROOT_BRANCH.equals(logicalBranchId)) {
            return owner.id();
        }
        return scopeId + "/" + logicalBranchId;
    }

    // 归还错误需要保留到计划最终收尾，即使后来重试清理成功也不能抹掉不确定记录。
    private void release(ModelAdapter.Binding binding) {
        try {
            models.release(binding);
        } catch (RuntimeException failure) {
            if (releaseFailure == null) {
                releaseFailure = failure;
            }
            throw failure;
        }
    }

    @Override
    // 全部实际步骤结束后统一清理；取消对外返回时不能提前调用。
    public synchronized void close() {
        // 嵌套绑定总在父绑定之后加入，逆序释放保证子分支先结束。
        List<ModelAdapter.Binding> remaining = new ArrayList<>(bindings.values());
        for (int index = remaining.size() - 1; index >= 0; index--) {
            ModelAdapter.Binding binding = remaining.get(index);
            // owner 属于上一层，保留给汇合后的下一轮决策。
            if (!binding.id().equals(owner.id())) {
                try {
                    release(binding);
                } catch (RuntimeException failure) {
                    // 已由 release 保存首个失败；继续回收其他分支，根会话随后仍会关闭。
                    continue;
                }
            }
        }
        // 失败时交给上层记录 UNKNOWN，不能把已执行产物丢成普通异常结果。
        if (releaseFailure != null) {
            throw releaseFailure;
        }
        // 清理局部映射并保留父上下文；根会话由实际工作链最终关闭。
        bindings.clear();
        bindings.put(PlanBranches.ROOT_BRANCH, owner);
    }
}
