package com.example.myhelper.capability;

import com.example.myhelper.capability.CapabilityLookup.Match;
import com.example.myhelper.capability.CapabilityLookup.PlanMatches;
import com.example.myhelper.capability.CapabilityLookup.Query;
import com.example.myhelper.capability.CapabilityLookup.Ref;
import com.example.myhelper.orchestration.TaskPlan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 循环侧使用的固化检索端口；数据库、匹配和缓存实现由外部模块提供。 */
public interface CapabilitySearchPort {
    /** 是否已经接入真实检索；未启用与已启用但没有候选是不同状态。 */
    boolean enabled();

    /** 根入口检索一次；实现故障直接上抛，不伪装成没有候选。 */
    Match searchAtEntry(Query query);

    /**
     * 携带任务及恢复上下文的入口检索；调用方提供不可变上下文快照。
     * 旧实现不使用新增上下文时沿用原入口，外部模块可覆盖本方法实施筛选。
     */
    default Match searchAtEntry(Query query, Map<String, Object> context) {
        // 保持旧实现的调用与异常语义，不因为缺少上下文筛选实现而伪造结果。
        return searchAtEntry(query);
    }

    /** 每次实际模型规划后，按总体目标和各步骤重新检索。 */
    PlanMatches searchAfterPlanning(String runId, long revision, TaskPlan plan, Map<String, Object> input);

    /**
     * 为每次规划后检索提供任务及恢复上下文；调用方传入不可变快照。
     * 默认委托旧方法，已有检索与缓存实现无需为兼容而改变行为。
     */
    default PlanMatches searchAfterPlanning(String runId, long revision, TaskPlan plan,
                                          Map<String, Object> input, Map<String, Object> context) {
        // 仍由原接口按本轮模型任务执行检索，不增加重复查询或吞掉外部异常。
        return searchAfterPlanning(runId, revision, plan, input);
    }

    /** 执行前核对指定能力版本的资格；缓存命中不能代替此检查。 */
    boolean isAvailable(Ref ref);

    /** 尚未接入外部模块时显式使用空实现，不拦截已启用实现的异常。 */
    static CapabilitySearchPort disabled() {
        // 无状态实例可由多个根任务共用，每次计划仍创建自己的步骤映射。
        return DisabledCapabilitySearch.INSTANCE;
    }
}

/** 未启用的具名实现；保留完整空匹配形状，便于循环按正常调用点接线。 */
final class DisabledCapabilitySearch implements CapabilitySearchPort {
    /** 唯一的无状态实例，不包含共享的运行计划或缓存。 */
    static final DisabledCapabilitySearch INSTANCE = new DisabledCapabilitySearch();

    // 仅通过端口工厂取得未启用实例。
    private DisabledCapabilitySearch() {
    }

    @Override
    public boolean enabled() {
        // 明确报告未接入，调用方可以把这个事实写入执行链路。
        return false;
    }

    @Override
    public Match searchAtEntry(Query query) {
        // 参数错误不能因为模块未启用而被悄悄忽略。
        Objects.requireNonNull(query, "query");
        // 没有外部检索时不伪造候选或已选能力。
        return emptyMatch();
    }

    @Override
    public PlanMatches searchAfterPlanning(String runId, long revision, TaskPlan plan, Map<String, Object> input) {
        // 计划必须存在，才能为每个步骤建立明确的未匹配结果。
        Objects.requireNonNull(plan, "plan");
        // 与正式调用约定一致，当前输入不能为 null。
        Objects.requireNonNull(input, "input");
        // 每个计划持有独立的步骤映射，不共享可变容器。
        Map<String, Match> steps = new LinkedHashMap<>();
        // 即使未接入检索，也保留每个实际步骤的键。
        for (TaskPlan.Step step : plan.steps()) {
            // 当前步骤没有候选，也没有可以直接执行的固定定义。
            steps.put(step.id(), emptyMatch());
        }
        // 冻结映射，避免执行线程改变其他消费者看到的计划绑定。
        return new PlanMatches(emptyMatch(), Map.copyOf(steps));
    }

    @Override
    public boolean isAvailable(Ref ref) {
        // 缺少能力引用属于调用错误，而不是可执行资格。
        Objects.requireNonNull(ref, "ref");
        // 未启用模块不能批准任何能力版本执行。
        return false;
    }

    // 用不可变空值表达未找到能力，不产生伪造的空执行计划。
    private static Match emptyMatch() {
        // 保留与真实检索相同的数据形状，避免外层另写一套执行分支。
        return new Match(List.of(), Optional.empty());
    }
}
