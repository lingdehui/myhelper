package com.example.myhelper.orchestration;

import com.example.myhelper.llm.LlmTexts;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将计划的数据依赖图转成明确的模型分支归属；不执行任务，也不选择模型。 */
public final class PlanBranches {
    /** 本计划进入前已有的运行分支；嵌套计划的 $root 可以对应父计划中的 B。 */
    public static final String ROOT_BRANCH = "$root";
    /** 仅供分析使用的虚拟入口，不会作为业务步骤执行。 */
    private static final String ENTRY = "$routing-entry";
    /** 计划结束是没有显式汇合步骤的分叉的收束边界。 */
    private static final String EXIT = "$routing-exit";

    private PlanBranches() {
    }

    /** 显式声明可处理跨支数据依赖；省略时按结构明确的分叉/汇合推导。 */
    public record Routing(
            /** 完整 stepId 到逻辑分支编号的映射，主线使用 $root。 */
            Map<String, String> stepBranches,
            /** 分叉组，父组必须先于子组声明或可由父子关系解析。 */
            List<Fork> forks) {
        public Routing {
            // 保留步骤声明顺序，并禁止调用方修改已经提交的分支归属。
            stepBranches = Collections.unmodifiableMap(new LinkedHashMap<>(stepBranches));
            // 固定分叉列表，分析期间不会被其他线程改写。
            forks = List.copyOf(forks);
        }
    }

    /** 一组子分支只在自己的汇合边界结束，父分支模型始终保留。 */
    public record Fork(
            /** 计划内唯一分叉编号。 */
            String id,
            /** 分叉前所属分支，汇合后继续使用该分支。 */
            String parentBranchId,
            /** 这一组同时创建的子分支，按列表顺序分配模型。 */
            List<String> childBranchIds,
            /** 汇合步骤编号；null 表示整份计划结束时收束。 */
            String joinStepId) {
        public Fork {
            // 分叉编号和父分支编号必须明确，后续要据此恢复父模型。
            TaskPlan.requireText(id, "fork.id");
            // 根分支使用 $root，普通父分支使用已声明编号。
            TaskPlan.requireText(parentBranchId, "fork.parentBranchId");
            // 保存稳定的子分支顺序，分配模型时沿用此顺序。
            childBranchIds = List.copyOf(childBranchIds);
            // 单个子任务不是分叉，同一子分支也不能在一组中出现两次。
            if (childBranchIds.size() < 2 || new LinkedHashSet<>(childBranchIds).size() != childBranchIds.size()) {
                // 拒绝不明确的组，避免多分配或重复归还绑定。
                throw new IllegalArgumentException(LlmTexts.ROUTING_CHILDREN_REQUIRED);
            }
            // 逐一确认子分支有可追踪的身份。
            for (String child : childBranchIds) {
                // 空白编号不能用作运行时映射键。
                TaskPlan.requireText(child, "fork.childBranchId");
            }
            // null 表示计划末尾汇合；声明具体步骤时则必须有有效编号。
            if (joinStepId != null) {
                // 是否确实存在这个步骤，在整份布局校验时判断。
                TaskPlan.requireText(joinStepId, "fork.joinStepId");
            }
        }
    }

    /** 只读布局；运行时可据此在首个子分支就绪时分配整组模型。 */
    public record Layout(
            /** 每个业务步骤所属的逻辑分支。 */
            Map<String, String> stepBranches,
            /** 父组在前、子组在后的分叉列表。 */
            List<Fork> forks) {
        public Layout {
            // 冻结已分析好的归属，运行时只能查询而不能改变计划结构。
            stepBranches = Collections.unmodifiableMap(new LinkedHashMap<>(stepBranches));
            // 保持父组在前的顺序，关闭时可以反向先处理内层组。
            forks = List.copyOf(forks);
        }

        /** 根据步骤编号取得逻辑分支；未知步骤明确失败，不默认使用根模型。 */
        public String branch(String stepId) {
            // 读取这一步在当前计划中的所属分支。
            String branchId = stepBranches.get(stepId);
            // 查不到说明调用方使用了另一份计划或错误的步骤编号。
            if (branchId == null) {
                // 报告身份错误，避免执行到错误模型分支。
                throw new IllegalArgumentException(LlmTexts.UNKNOWN_ROUTING_STEP_PREFIX + stepId);
            }
            // 分配器将这个逻辑编号转换成本次运行的真实分支身份。
            return branchId;
        }

        /** 根分支不由本计划创建，返回 null；其他分支返回其所属分叉组。 */
        public Fork forkForBranch(String branchId) {
            // 一个非根分支只属于一个创建它的分叉组。
            for (Fork fork : forks) {
                // 找到声明这个子分支的组后即可返回。
                if (fork.childBranchIds().contains(branchId)) {
                    // 其中保存了父分支和本组汇合位置。
                    return fork;
                }
            }
            // 根分支由上一层传入，不在本计划的任何子分支列表中。
            return null;
        }

        /** 深层组先释放；此方法只描述边界，不修改任何运行状态。 */
        public List<Fork> closingAt(String stepId) {
            // 收集本步骤到达时需要结束的分叉组。
            List<Fork> closing = new ArrayList<>();
            // 逆序遍历保证嵌套子组先结束，父组后结束。
            for (int index = forks.size() - 1; index >= 0; index--) {
                // 读取这一个候选组的汇合边界。
                Fork fork = forks.get(index);
                // 只有边界就是当前步骤的组才能在此归还绑定。
                if (stepId.equals(fork.joinStepId())) {
                    // 保存当前顺序，调用方不需要再自行计算嵌套深度。
                    closing.add(fork);
                }
            }
            // 对外返回只读结果，防止关闭顺序被修改。
            return List.copyOf(closing);
        }
    }

    /** 在任何工具启动前完成布局校验；结构不明确时要求显式声明，不猜测父模型。 */
    public static Layout analyze(TaskPlan plan) {
        // 先建立仅用于分析的依赖图，真实计划的 dependsOn 保持原样。
        Graph graph = new Graph(plan.steps());
        // 模型已明确声明控制分支时，按声明校验而不是再次猜测。
        if (plan.routing() != null) {
            // 校验步骤覆盖、父子关系和汇合必须等待的范围。
            return explicit(plan.routing(), graph);
        }
        // 未声明时，尝试分析结构明确的线性路径及嵌套分叉。
        Inference inference = new Inference(graph);
        // 整份计划以传入分支为主线，走到虚拟出口即计划结束。
        inference.walk(ENTRY, EXIT, ROOT_BRANCH);
        // 将分析过程中的可变表封装成运行时只读布局。
        return new Layout(inference.owners, inference.forks);
    }

    /** 显式布局只改变模型归属，不增加、删除或重排业务依赖。 */
    private static Layout explicit(Routing routing, Graph graph) {
        // 取出模型声明的每步归属。
        Map<String, String> owners = routing.stepBranches();
        // 声明不能漏步骤，也不能夹带当前计划之外的步骤。
        if (!owners.keySet().equals(graph.steps.keySet())) {
            // 不完整的归属会导致部分步骤在错误模型上执行。
            throw new IllegalArgumentException(LlmTexts.ROUTING_STEP_COVERAGE_REQUIRED);
        }
        // 从已存在的主线开始，逐层登记新分支。
        Set<String> known = new LinkedHashSet<>();
        // $root 指向调用本计划时已有的模型绑定。
        known.add(ROOT_BRANCH);
        // 单独记录组编号，防止不同分叉使用同一个身份。
        Set<String> groupIds = new LinkedHashSet<>();
        // 排序后的列表供运行时先创建父组、再创建子组。
        List<Fork> ordered = new ArrayList<>();
        // 尚未找到父分支的组留在这里，下一遍继续处理。
        List<Fork> remaining = new ArrayList<>(routing.forks());
        // 直到所有组的父子关系都已明确。
        while (!remaining.isEmpty()) {
            // 用数量变化判断是否存在失联父级或循环。
            int before = remaining.size();
            // 遍历副本，允许处理成功后从待办列表中移除当前组。
            for (Fork fork : List.copyOf(remaining)) {
                // 父分支尚未登记时，暂时不能创建这一组子分支。
                if (!known.contains(fork.parentBranchId())) {
                    // 等包含父分支的外层组先处理完。
                    continue;
                }
                // 每个分叉组必须有独立编号。
                if (!groupIds.add(fork.id())) {
                    // 重复组不能覆盖之前已经登记的分叉。
                    throw new IllegalArgumentException(LlmTexts.DUPLICATE_ROUTING_ID_PREFIX + fork.id());
                }
                // 登记这一组实际创建的全部子分支。
                for (String child : fork.childBranchIds()) {
                    // 已登记的根分支或其他子分支不能被重复创建。
                    if (!known.add(child)) {
                        // 同名分支会让父级和模型绑定失去唯一性。
                        throw new IllegalArgumentException(LlmTexts.DUPLICATE_ROUTING_ID_PREFIX + child);
                    }
                }
                // 父级已确定，这一组可加入有序结果。
                ordered.add(fork);
                // 从下一遍的待解析组中移除。
                remaining.remove(fork);
            }
            // 完整遍历仍没有进展，继续循环也无法解决父子关系。
            if (before == remaining.size()) {
                // 在执行开始前报告错误，避免运行时无限等待。
                throw new IllegalArgumentException(LlmTexts.INVALID_ROUTING_PARENTS);
            }
        }
        // 父子组排序完成后创建只读布局，供后续范围检查使用。
        Layout layout = new Layout(owners, ordered);
        // 每个步骤只能属于根分支或刚刚登记的子分支。
        for (String owner : owners.values()) {
            // 不能通过步骤映射引用没有创建过的分支。
            if (!known.contains(owner)) {
                // 把错误编号交回调用方修正规划。
                throw new IllegalArgumentException(LlmTexts.UNKNOWN_ROUTING_BRANCH_PREFIX + owner);
            }
        }
        // 同一线性分支的步骤必须可排序；并行支线不能藏在同一个模型归属里。
        for (String first : graph.steps.keySet()) {
            // 与其他步骤逐对比较，不改变它们的执行依赖。
            for (String second : graph.steps.keySet()) {
                // 同属一条线却互不依赖，实际应是两个并行分支。
                if (!first.equals(second) && owners.get(first).equals(owners.get(second))
                        && !graph.before(first, second) && !graph.before(second, first)) {
                    // 让规划显式拆分，不能仅因模型相同就混成一条线。
                    throw new IllegalArgumentException(LlmTexts.ROUTING_BRANCH_NOT_LINEAR);
                }
            }
        }
        // 最后检查每组的汇合位置和父分支等待范围。
        for (Fork fork : ordered) {
            // 一次检查包含全部嵌套子步骤，不只看直接子步骤。
            validateFork(fork, layout, graph);
        }
        // 所有结构约束通过后才交给调度器使用。
        return layout;
    }

    /** 检查本组会等完所有后代，并在正确的父分支继续。 */
    private static void validateFork(Fork fork, Layout layout, Graph graph) {
        // 记录具体汇合步骤，null 表示直到整个计划结束才收束。
        String join = fork.joinStepId();
        // 内层不能拖到计划末尾才释放，而外层已经在某个业务步骤收束。
        String parent = fork.parentBranchId();
        // 沿父级向上检查，不能超过任意一层外部分叉的结束边界。
        while (!parent.equals(ROOT_BRANCH)) {
            // 找到创建当前父分支的外层组。
            Fork outer = layout.forkForBranch(parent);
            // 外层中途就汇合时，内层必须更早在自己的父分支汇合。
            if (join == null && outer.joinStepId() != null) {
                // 拒绝会先释放父模型、后结束子模型的声明。
                throw new IllegalArgumentException(LlmTexts.INVALID_ROUTING_JOIN);
            }
            // 继续核对更外面的一层。
            parent = outer.parentBranchId();
        }
        // 具体汇合步骤必须存在，而且属于本组原来的父分支。
        if (join != null && (!graph.steps.containsKey(join) || !layout.branch(join).equals(fork.parentBranchId()))) {
            // 不能把 D/E 的汇合错误地交给 A，而跳过它们的父分支 B。
            throw new IllegalArgumentException(LlmTexts.INVALID_ROUTING_JOIN);
        }
        // 收集所有子分支以及更深层子分支中的业务步骤。
        Set<String> memberSteps = new LinkedHashSet<>();
        // 每个直接子分支都需要至少有一项工作。
        for (String child : fork.childBranchIds()) {
            // 先假定该子分支为空，找到所属步骤后再更新。
            boolean found = false;
            // 逐步检查是否位于当前子分支范围之内。
            for (String stepId : graph.steps.keySet()) {
                // inside 同时识别直接归属和嵌套归属。
                if (inside(layout.branch(stepId), child, layout)) {
                    // 找到实际工作，不再把这条子分支视为空分支。
                    found = true;
                    // 保存起来，随后核对父分支是否等待完这部分工作。
                    memberSteps.add(stepId);
                    // 业务汇合必须在所有这些步骤之后才允许启动。
                    if (join != null && !graph.before(stepId, join)) {
                        // 少等一个后代都可能导致提前释放模型绑定。
                        throw new IllegalArgumentException(LlmTexts.ROUTING_JOIN_MUST_WAIT);
                    }
                }
            }
            // 空分支不产生工作，不能凭空占一个模型分配位置。
            if (!found) {
                // 要求修正规划中的无效分支。
                throw new IllegalArgumentException(LlmTexts.EMPTY_ROUTING_BRANCH);
            }
        }
        // 父主线要么在全部子步骤之前，要么在汇合之后，不能在等待期间暗中并行推进。
        for (String stepId : graph.steps.keySet()) {
            // 本检查只约束原父分支，其他外层并行分支可以继续执行。
            if (!layout.branch(stepId).equals(fork.parentBranchId())) {
                // 跳过不属于本组父线的步骤。
                continue;
            }
            // 汇合本身及其后续步骤已经处于子分支结束之后。
            if (join != null && (stepId.equals(join) || graph.before(join, stepId))) {
                // 这些步骤可以合法恢复父模型继续执行。
                continue;
            }
            // 剩下的父步骤必须是分叉之前的准备工作。
            for (String member : memberSteps) {
                // 若不是所有子步骤的前驱，就会与子分支同时推进父线。
                if (!graph.before(stepId, member)) {
                    // 拒绝违反“父分支等待汇合”约定的布局。
                    throw new IllegalArgumentException(LlmTexts.ROUTING_PARENT_MUST_WAIT);
                }
            }
        }
    }

    /** 判断一个分支是否就是指定祖先，或者由该祖先逐层拆出。 */
    private static boolean inside(String branch, String ancestor, Layout layout) {
        // 从步骤直接所属的分支开始向上寻找。
        String current = branch;
        // 到达根分支就不再有外层子分支归属。
        while (!current.equals(ROOT_BRANCH)) {
            // 找到指定祖先，说明步骤在它的控制范围内。
            if (current.equals(ancestor)) {
                // 直接归属和更深层归属都返回 true。
                return true;
            }
            // 父子关系已经校验无环，可以安全向上一层移动。
            current = layout.forkForBranch(current).parentBranchId();
        }
        // 一直走到根也没找到，说明它属于另一条支线。
        return false;
    }

    /** 分析专用 DAG；传递依赖只用于校验，约简边不改变真实调度依赖。 */
    private static final class Graph {
        /** 原步骤索引，保留计划声明顺序。 */
        private final Map<String, TaskPlan.Step> steps = new LinkedHashMap<>();
        /** 各节点的所有前驱，用于确定先后和消除重复的传递依赖。 */
        private final Map<String, Set<String>> ancestors = new LinkedHashMap<>();
        /** 去掉传递冗余边后的后继，加上虚拟入口和出口。 */
        private final Map<String, List<String>> successors = new LinkedHashMap<>();
        /** 每条后续路径都必经的节点集合，用来找结构化汇合。 */
        private final Map<String, Set<String>> postdominators = new LinkedHashMap<>();
        /** 含虚拟入口和出口的拓扑顺序。 */
        private final List<String> order = new ArrayList<>();

        /** 建索引、消除分析图中的冗余边，再计算各条路径共同经过的位置。 */
        private Graph(List<TaskPlan.Step> planSteps) {
            // 先保存真实步骤，后续所有分析都只使用这些编号。
            for (TaskPlan.Step step : planSteps) {
                // 虚拟入口和出口的编号不能与用户步骤混淆。
                if (step.id().equals(ENTRY) || step.id().equals(EXIT)) {
                    // 接收计划时即拒绝冲突，避免把真实动作当作边界跳过。
                    throw new IllegalArgumentException(LlmTexts.RESERVED_ROUTING_STEP);
                }
                // 保留计划原有顺序，使同组分支的分配顺序可重复。
                steps.put(step.id(), step);
            }
            // 虚拟入口排在所有真实步骤前面，统一处理多起点计划。
            order.add(ENTRY);
            // 入口的后继稍后设为所有没有前驱的步骤。
            successors.put(ENTRY, new ArrayList<>());
            // TaskPlan 已校验 DAG；此处按稳定顺序建立传递前驱。
            while (ancestors.size() < steps.size()) {
                // 每遍只处理前驱已解析的步骤，保持前驱先于后继。
                for (TaskPlan.Step step : planSteps) {
                    // 已处理过或前驱尚未齐全的步骤留给其他位置/下一遍。
                    if (ancestors.containsKey(step.id()) || !ancestors.keySet().containsAll(step.dependsOn())) {
                        // 不提前读取还没有计算好的前驱关系。
                        continue;
                    }
                    // 此集合既保存直接前驱，也保存间接前驱。
                    Set<String> before = new LinkedHashSet<>();
                    // 沿每个直接前驱合并它之前已经发生的步骤。
                    for (String dependency : step.dependsOn()) {
                        // 直接依赖本身必须在当前步骤之前完成。
                        before.add(dependency);
                        // 该依赖的全部前驱自然也在当前步骤之前。
                        before.addAll(ancestors.get(dependency));
                    }
                    // 保存完整先后关系，后面用它判断路径和汇合是否合法。
                    ancestors.put(step.id(), before);
                    // 记录当前步骤在从前到后顺序中的位置。
                    order.add(step.id());
                    // 先建空后继表，所有节点齐全后再连边。
                    successors.put(step.id(), new ArrayList<>());
                }
            }
            // 虚拟出口排在所有真实步骤之后，代表整份计划结束。
            order.add(EXIT);
            // 出口之后没有任何步骤。
            successors.put(EXIT, List.of());
            // 按实际依赖建立分析图；只省略已经被间接路径表达的重复边。
            for (TaskPlan.Step step : planSteps) {
                // 没有前驱的步骤可以从计划入口直接开始。
                if (step.dependsOn().isEmpty()) {
                    // 多个入口后继表示计划一开始就有并行支线。
                    successors.get(ENTRY).add(step.id());
                }
                // 决定每个直接依赖是否还需要单独保留一条分析边。
                for (String dependency : step.dependsOn()) {
                    // 默认该依赖不能省略，发现替代路径后再标记。
                    boolean redundant = false;
                    // 例如 c 同时依赖 a/b，且 b 已依赖 a，则 a→c 是重复边。
                    for (String other : step.dependsOn()) {
                        // 另一个直接前驱已经必须等当前依赖，说明存在替代路径。
                        if (!dependency.equals(other) && before(dependency, other)) {
                            // 分析时不用把重复边误认成另一条并行支线。
                            redundant = true;
                            // 找到一条替代路径就足够，无需继续比较。
                            break;
                        }
                    }
                    // 只把不能省略的依赖边放入分析图。
                    if (!redundant) {
                        // 从前驱指向当前步骤，后面沿这个方向识别分叉。
                        successors.get(dependency).add(step.id());
                    }
                }
            }
            // 所有末端步骤连接同一个虚拟出口，统一分析没有显式汇合的计划。
            for (String stepId : steps.keySet()) {
                // 没有业务后继说明该步骤已经位于一条支线的末尾。
                if (successors.get(stepId).isEmpty()) {
                    // 连接出口只用于分析，不会生成额外工具动作。
                    successors.get(stepId).add(EXIT);
                }
            }
            // 从出口出发必经的节点只有出口自己，这是向前计算的起点。
            postdominators.put(EXIT, Set.of(EXIT));
            // 倒序处理，保证每个后继的必经节点集合已经算好。
            for (int index = order.size() - 2; index >= 0; index--) {
                // 取出正在计算的步骤或虚拟入口。
                String node = order.get(index);
                // 先用第一个后继的集合建立独立副本，避免修改后继记录。
                Set<String> common = new LinkedHashSet<>(postdominators.get(successors.get(node).getFirst()));
                // 当前节点可能有多条后路，只留下每条后路都会经过的位置。
                for (String next : successors.get(node)) {
                    // 取交集后，某一条支线独有的步骤会被排除。
                    common.retainAll(postdominators.get(next));
                }
                // 从本节点出发当然也会经过本节点自身。
                common.add(node);
                // 保存计算结果，供更前面的步骤寻找共同汇合位置。
                postdominators.put(node, common);
            }
        }

        /** 判断 first 是否被依赖关系保证在 second 之前完成。 */
        private boolean before(String first, String second) {
            // 传递前驱已算好，查询时不重新遍历整张图。
            return ancestors.get(second).contains(first);
        }

        /** 找到这些子支线之后共同必经的最早位置，作为本组汇合边界。 */
        private String join(List<String> starts) {
            // 从第一条支线的必经节点开始逐步缩小候选范围。
            Set<String> common = new LinkedHashSet<>(postdominators.get(starts.getFirst()));
            // 汇合必须是每条支线都会经过的位置。
            for (String start : starts) {
                // 只保留所有子支线共有的候选。
                common.retainAll(postdominators.get(start));
            }
            // 最靠前的共同必经点就是本组结构化汇合；没有业务汇合时是 EXIT。
            for (String node : order) {
                // 按先后顺序找到的第一个共同位置就是最近的汇合。
                if (common.contains(node)) {
                    // 返回真实步骤或虚拟出口，由调用方转换成运行边界。
                    return node;
                }
            }
            // 有效计划通常至少共享出口；无法确定时仍明确报告歧义。
            throw new IllegalArgumentException(LlmTexts.AMBIGUOUS_ROUTING);
        }
    }

    private static final class Inference {
        /** 不可执行的分析图。 */
        private final Graph graph;
        /** 分析产生的步骤归属。 */
        private final Map<String, String> owners = new LinkedHashMap<>();
        /** 分叉组按父组在前保存。 */
        private final List<Fork> forks = new ArrayList<>();

        private Inference(Graph graph) {
            // 保存已经建立好先后关系和共同必经点的分析图。
            this.graph = graph;
        }

        /** 沿一条主线走到 stop；遇到分叉先递归处理各子线，汇合后恢复 owner。 */
        private void walk(String start, String stop, String owner) {
            // 当前节点从本次子线入口开始。
            String node = start;
            // stop 属于汇合后的父线，不在当前子线中重复登记。
            while (!node.equals(stop)) {
                // 同一步被两条支线抢占，说明存在交叉/部分汇合，不能自动猜归属。
                if (!node.equals(ENTRY) && owners.putIfAbsent(node, owner) != null) {
                    // 要求规划提供显式 routing，保留原有数据依赖含义。
                    throw new IllegalArgumentException(LlmTexts.AMBIGUOUS_ROUTING);
                }
                // 查看当前步骤之后有几条不能省略的路径。
                List<String> next = graph.successors.get(node);
                // 只有一个后继就是继续沿线执行，模型归属不变。
                if (next.size() == 1) {
                    // 移到下一步，仍然使用当前 owner。
                    node = next.getFirst();
                    // 线性推进不创建分叉，也不申请新模型。
                    continue;
                }
                // 多个后继需要找出它们重新汇合的位置。
                String join = graph.join(next);
                // 本计划内依发现顺序生成唯一组编号，不使用模型名字充当身份。
                String forkId = "fork-" + (forks.size() + 1);
                // 先准备同组全部子分支编号，供运行时原子分配模型。
                List<String> children = new ArrayList<>();
                // 子分支顺序与分析图的后继顺序一致。
                for (int index = 0; index < next.size(); index++) {
                    // 组编号加组内位置，能区分同一计划内的不同逻辑支线。
                    children.add(forkId + "/" + (index + 1));
                }
                // 先登记父组，再进入子线，因此 forks 始终保持父组在前。
                forks.add(new Fork(forkId, owner, children, join.equals(EXIT) ? null : join));
                // 分别分析各条子线，直到本组共同汇合位置为止。
                for (int index = 0; index < next.size(); index++) {
                    // 子线内部再遇分叉时递归同一逻辑，可形成 B→D/E 等嵌套。
                    walk(next.get(index), join, children.get(index));
                }
                // 所有子线分析结束，恢复原 owner 处理汇合及之后的父线。
                node = join;
            }
        }
    }
}
