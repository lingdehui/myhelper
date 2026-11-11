package com.example.myhelper.orchestration;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.orchestration.ExecutionTraceStore.NodeKind;
import com.example.myhelper.orchestration.ExecutionTraceStore.EdgeKind;
import com.example.myhelper.orchestration.ExecutionTraceStore.NodeStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * 递归并发主流程：根入口检索一次，每次 LLM 提交计划后重新检索。
 * 父任务通过 CompletionStage 等待子任务，不占用工作线程阻塞等待。
 * 固化生成、匹配策略、门禁和验收均由注入组件负责。
 */
public final class AgentOrchestrator {
    /**
     * 每次调用只产生一个决策的模型适配器。
     */
    private final Planner planner;
    /**
     * 独立检索及内容缓存。
     */
    private final CapabilityLookup lookup;
    /**
     * 普通工具执行端口，不负责递归任务。
     */
    private final ActionExecutor actions;
    /**
     * 本次执行条件与结果检查，不能默认缓存一次通过结论。
     */
    private final Checks checks;
    /**
     * 共享、只读可查询的执行关系记录。
     */
    private final ExecutionTraceStore traces;
    /**
     * 由调用方管理的有界工作执行器。
     */
    private final Executor workers;
    /**
     * 只负责截止时间，不运行模型或工具。
     */
    private final ScheduledExecutorService timer;
    /**
     * 每根任务共享的执行限制。
     */
    private final Limits limits;

    // 装配核心组件；生命周期和具体业务策略由调用方管理。
    public AgentOrchestrator(Planner planner, CapabilityLookup lookup, ActionExecutor actions, Checks checks,
                             ExecutionTraceStore traces, Executor workers, ScheduledExecutorService timer, Limits limits) {
        // 保存单次模型决策适配器，编排器通过它调用模型。
        this.planner = Objects.requireNonNull(planner);
        // 保存独立固化检索组件，便于以后替换检索实现。
        this.lookup = Objects.requireNonNull(lookup);
        // 保存普通工具执行器；递归任务仍由本类负责。
        this.actions = Objects.requireNonNull(actions);
        // 保存真实门禁和验收策略，不在编排器内默认放行。
        this.checks = Objects.requireNonNull(checks);
        // 保存共享执行图，其他模块通过它读取运行关系。
        this.traces = Objects.requireNonNull(traces);
        // 保存调用方管理的工作池，模型和工具调用都在其中运行。
        this.workers = Objects.requireNonNull(workers);
        // 保存超时计时器；计时线程不执行模型或业务工具。
        this.timer = Objects.requireNonNull(timer);
        // 保存本运行树的轮数、数量、深度和时间预算。
        this.limits = Objects.requireNonNull(limits);
    }

    /**
     * 新用户任务入口。递归子任务从内部入口进入，不重复根任务入口检索。
     */
    public Handle submit(Request request) {
        // 为新根任务生成身份，后续子任务和链路都归属于它。
        String root = UUID.randomUUID().toString();
        // 创建此根任务的内存执行图。
        traces.open(root);
        // 创建整棵任务树共用的截止时间、取消信号和计数器。
        Control control = new Control(root, request, Instant.now().plus(limits.timeout()));
        // 创建对外结果句柄，调用方可以异步等待。
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        // 登记超时处理；到期只请求停止，不假定已经撤销外部动作。
        var timeout = timer.schedule(new Runnable() {
            @Override
            public void run() {
                // 仅在没有停止原因时写入超时原因，保留先发生的取消信息。
                control.stop.compareAndSet(null, LlmTexts.TASK_TIMEOUT);
                // 尝试完成对外结果；若已完成则不覆盖先前结果。
                result.complete(new Outcome(Status.CANCELLED, null, control.stop.get()));
                // 将配置时限换成毫秒，按毫秒单位交给计时器。
            }
        }, limits.timeout().toMillis(), TimeUnit.MILLISECONDS);
        // 保存真正的在途工作链；它可能比对外的取消结果晚结束。
        CompletableFuture<Outcome> work;
        try {
            // 从深度 0 进入根任务；true 表示允许进行唯一一次入口检索。
            work = task(control, null, request.goal(), request.arguments(), 0, true);
        } catch (RuntimeException failure) {
            // 把同步启动异常转换为统一失败结果。
            work = CompletableFuture.completedFuture(failed(failure));
        }
        // 真正工作链结束时执行收尾，即使对外已经返回取消也要收尾。
        work.whenComplete(new BiConsumer<Outcome, Throwable>() {
            @Override
            public void accept(Outcome outcome, Throwable failure) {
                // 取消尚未触发的计时任务；false 表示不强行中断正在执行的计时线程。
                timeout.cancel(false);
                // 只有真实在途链完成后才允许清理图；超时返回不代表调用已结束。
                traces.close(root);
                // 返回真实结果；若异步链异常结束，则转为明确失败。
                result.complete(failure == null ? outcome : failed(failure));
            }
        });
        // 同时返回根身份、异步结果和取消入口。
        return new Handle(root, result, new Runnable() {
            @Override
            public void run() {
                // 记录用户取消，供后续调度和资源等待检查。
                control.stop.compareAndSet(null, LlmTexts.TASK_CANCELLED);
                // 尝试完成对外结果；若已完成则不覆盖先前结果。
                result.complete(new Outcome(Status.CANCELLED, null, control.stop.get()));
            }
        });
    }

    // 建立一个根任务或子任务；只有根任务的 entry 为 true。
    private CompletableFuture<Outcome> task(Control control, String parent, String goal, Map<String, Object> args,
                                            int depth, boolean entry) {
        // 在继续调度前检查共享取消信号和截止时间。
        control.check();
        // 先限制递归层数，再累计本运行树创建的任务数量。
        if (depth > limits.maxDepth() || control.tasks.incrementAndGet() > limits.maxTasks()) {
            // 返回已完成的失败结果，明确本分支没有继续执行动作。
            return CompletableFuture.completedFuture(new Outcome(Status.FAILED, null, LlmTexts.TASK_LIMIT_REACHED));
        }
        // 每次递归创建独立任务身份，不与根身份或步骤身份混用。
        String run = UUID.randomUUID().toString();
        // 将本任务登记到执行图；规划版本 0 表示尚未调用模型。
        String node = node(control, run, parent, NodeKind.TASK, goal, 0, args);
        // 保存入口复用或模型求解这两条路径的异步结果。
        CompletableFuture<Outcome> result;
        // 仅根任务执行这条入口检索路径。
        if (entry) {
            // 异步查询入口固化，并用 phase 字段记录查询发生位置。
            result = async(control, run, node, NodeKind.SEARCH, goal, 0, Map.of("phase", CapabilityLookup.SearchPhase.ENTRY.name()), new Supplier<CapabilityLookup.Match>() {
                @Override
                public CapabilityLookup.Match get() {
                    // 以用户原始目标查询；入口保留 ID 与版本 0 不代表模型计划步骤。
                    return lookup.searchAtEntry(new CapabilityLookup.Query(run, 0, CapabilityLookup.ENTRY_QUERY_ID, goal, args));
                }
            })
                    // 查询完成后，决定直接执行整条固化还是调用模型。
                    .thenCompose(new Function<CapabilityLookup.Match, CompletionStage<Outcome>>() {
                        @Override
                        public CompletionStage<Outcome> apply(CapabilityLookup.Match match) {
                            // 入口已经匹配整条固化，直接进入能力执行。
                            if (match.selected().isPresent()) {
                                return capability(control, run, node, goal, args, match.selected().get(), depth, 0);
                            }
                            // 没有整条命中时，把入口候选交给第一轮模型。
                            return runDecisionRound(control, run, node, goal, args, depth, 0, match.candidates(), List.of());
                        }
                    });
        } else {
            // 递归任务直接进入第一轮模型，不重复根入口检索。
            result = runDecisionRound(control, run, node, goal, args, depth, 0, List.of(), List.of());
        }
        // 为任务完成结果附加执行图更新，再返回异步句柄。
        return finish(control, node, result);
    }

    /**
     * 执行一轮模型决策；原名 loop。它不是 Java 循环关键字，也不负责猜测用户意图。
     * run 为当前任务身份，parent 为链路父节点，goal/args 为目标和输入；
     * depth 限制递归层数，round 是从 0 开始的决策轮数，history 是已完成计划的反馈。
     */
    private CompletableFuture<Outcome> runDecisionRound(Control control, String run, String parent, String goal,
                                                        Map<String, Object> args, int depth, int round, List<CapabilityLookup.Candidate> candidates, List<Feedback> history) {
        // 先限制模型轮数，防止模型一直提交新计划而不结束。
        if (round >= limits.maxRounds()) {
            // 返回已完成的失败 Future，不再占用线程调用模型。
            return CompletableFuture.completedFuture(new Outcome(Status.FAILED, null, LlmTexts.PLANNING_ROUND_LIMIT_REACHED));
        }
        // 版本从 1 开始；版本 0 专门表示尚未进行模型规划的入口阶段。
        long revision = round + 1L;
        // 把这一轮模型请求交给工作池；async 还会记录对应的 MODEL 链路节点。
        return async(control, run, parent, NodeKind.MODEL, goal, revision, Map.of("round", revision), new Supplier<Decision>() {
            @Override
            public Decision get() {
                // 所有递归子任务共用根任务的模型调用预算。
                if (control.models.incrementAndGet() > limits.maxModelCalls()) {
                    // 达到预算后拒绝调用，不在这里偷偷换模型或重试。
                    throw new IllegalStateException(LlmTexts.MODEL_CALL_LIMIT_REACHED);
                }
                // 单次模型调用的输入：本任务目标、参数、允许工具、候选能力、历次实际结果。
                return planner.decide(new PlanningContext(run, goal, args, control.request.modelRef(),
                        // 把根工具权限、入口候选和本任务反馈交给模型。
                        control.request.toolNames(), candidates, history));
            }
        // 模型返回后接续异步工作，不占工作线程等待整棵任务树。
        }).thenCompose(new Function<Decision, CompletionStage<Outcome>>() {
            @Override
            public CompletionStage<Outcome> apply(Decision decision) {
                return switch (decision.kind()) {
                    // 模型缺少必要信息：直接把问题返回给调用方，暂停自动推进。
                    case NEEDS_INPUT -> CompletableFuture.completedFuture(
                            // 返回模型提出的补充问题，不把它当作已完成任务。
                            new Outcome(Status.NEEDS_INPUT, decision.output(), null));
                    // 模型认为任务完成：仍要经过验收，模型文字不能代替真实结果。
                    case COMPLETE -> async(control, run, parent, NodeKind.VERIFY, goal, revision, args,
                            // 在工作池中运行验收，验收异常不会被当作动作未发生。
                            new Supplier<Outcome>() {
                                @Override
                                public Outcome get() {
                                    return checked(goal, args, new Outcome(Status.SUCCESS, decision.output(), null));
                                }
                            });
                    // 模型提交计划：检索固化、执行计划，再按结果决定是否进入下一轮。
                    case PLAN ->
                            executeModelPlan(control, run, parent, goal, args, depth, round, candidates, history, decision.plan());
                };
            }
        });
    }

    /**
     * 只处理 PLAN 分支；与“完成”和“需要信息”分开，避免一个方法混入全部流程。
     */
    private CompletableFuture<Outcome> executeModelPlan(Control control, String run, String parent, String goal,
                                                        Map<String, Object> args, int depth, int round, List<CapabilityLookup.Candidate> candidates,
                                                        List<Feedback> history, TaskPlan plan) {
        // 使用当前模型轮次的版本，供查询、链路和反馈关联同一份计划。
        long revision = round + 1L;
        // 限制整份计划的步骤数，避免一次生成无限任务。
        if (plan.steps().size() > limits.maxSteps()) {
            // 拒绝整个计划，不能先执行前几步再发现计划过大。
            throw new IllegalArgumentException(LlmTexts.PLAN_STEP_LIMIT_REACHED);
        }
        // 整批检查工具名，保证模型不能借计划调用本任务未允许的工具。
        validateTools(plan, control.request.toolNames());
        // 每次真实 PLAN 都按新目标重新查库；不是筛选上轮缓存或入口候选。
        return async(control, run, parent, NodeKind.SEARCH, plan.goal(), revision,
                // phase 是链路协议字段，枚举名标记入口或规划后检索。
                Map.of("phase", CapabilityLookup.SearchPhase.AFTER_PLAN.name()),
                // 按本次模型计划重新检索总目标和所有子目标，完成后读取新匹配。
                new Supplier<CapabilityLookup.PlanMatches>() {
                    @Override
                    public CapabilityLookup.PlanMatches get() {
                        return lookup.searchAfterPlanning(run, revision, plan, args);
                    }
                }).thenCompose(new Function<CapabilityLookup.PlanMatches, CompletionStage<Outcome>>() {
                    @Override
                    public CompletionStage<Outcome> apply(CapabilityLookup.PlanMatches matches) {
                        // 整条命中则运行固化定义；否则执行原计划，并逐步骤复用命中的子能力。
                        CompletableFuture<Outcome> execution;
                        if (matches.whole().selected().isPresent()) {
                            // 整条计划已匹配：直接执行固定能力定义。
                            execution = capability(control, run, parent, plan.goal(), args, matches.whole().selected().get(), depth, revision);
                        } else {
                            // 否则执行原计划，并在各步骤使用已选子能力。
                            execution = executePlan(control, run, parent, plan, args, matches, depth, revision);
                        }
                        // 等待当前计划的所有步骤返回后，再让所属任务继续模型决策。
                        return execution.thenCompose(new Function<Outcome, CompletionStage<Outcome>>() {
                            @Override
                            public CompletionStage<Outcome> apply(Outcome outcome) {
                                return switch (outcome.status()) {
                                    // 这些状态不能自动继续；特别是 UNKNOWN 不能猜测动作没有发生。
                                    case CANCELLED, UNKNOWN, NEEDS_INPUT -> CompletableFuture.completedFuture(outcome);
                                    // 成功或明确失败都可反馈给模型：它可以结束，也可以规划剩余工作。
                                    case SUCCESS, FAILED -> {
                                        // 复制旧反馈，避免并发任务修改同一份历史。
                                        List<Feedback> next = new ArrayList<>(history);
                                        // 保存本次计划及每步实际结果，而不只保存“成功/失败”一句话。
                                        next.add(new Feedback(revision, plan, outcome));
                                        // 只进入下一轮决策；根入口检索不会在这里重复。
                                        yield runDecisionRound(control, run, parent, goal, args, depth, round + 1, candidates, List.copyOf(next));
                                    }
                                };
                            }
                        });
                    }
                });
    }

    // 运行已固定版本的能力定义；仍受本次门禁、验收和预算限制。
    private CompletableFuture<Outcome> capability(Control control, String run, String parent, String goal,
                                                  Map<String, Object> args, CapabilityLookup.Definition definition, int depth, long revision) {
        // 限制固化组合展开深度，避免无限递归。
        if (depth > limits.maxDepth()) {
            // 返回已完成的失败结果，明确本分支没有继续执行动作。
            return CompletableFuture.completedFuture(new Outcome(Status.FAILED, null, LlmTexts.CAPABILITY_DEPTH_LIMIT_REACHED));
        }
        // 建立能力执行节点，记录固定能力 ID。
        String unit = node(control, run, parent, NodeKind.UNIT, definition.ref().id(), revision,
                // 记录能力来源、固定修订以及本次调用参数，便于追溯。
                Map.of("namespace", definition.ref().namespace(), "revision", definition.ref().revision(), "arguments", args));
        // 异步检查能力资格，避免数据库读取占用调度调用栈。
        CompletableFuture<Outcome> result = async(control, run, unit, NodeKind.GATE, goal, revision, args, new Supplier<Boolean>() {
            @Override
            public Boolean get() {
                // 固化定义也不能使用本任务未允许的工具。
                validateTools(definition.plan(), control.request.toolNames());
                // 固化展开与模型计划使用同一份步骤数量上限。
                if (definition.plan().steps().size() > limits.maxSteps()) {
                    // 抛出参数或协议错误，阻止无效输入继续进入执行。
                    throw new IllegalArgumentException(LlmTexts.CAPABILITY_STEP_LIMIT_REACHED);
                }
                // 重新确认固定版本仍已发布，并检查本次实际参数是否允许执行。
                return lookup.isAvailable(definition.ref()) && checks.allow(goal, args, definition.ref());
            }
        // 资格检查完成后才决定是否启动能力中的动作。
        }).thenCompose(new Function<Boolean, CompletionStage<Outcome>>() {
            @Override
            public CompletionStage<Outcome> apply(Boolean allowed) {
                // 未通过资格或门禁时，不进入实际执行。
                if (!allowed) {
                    // 返回已完成的失败结果，明确本分支没有继续执行动作。
                    return CompletableFuture.completedFuture(new Outcome(Status.FAILED, null, LlmTexts.CAPABILITY_NOT_ALLOWED));
                }
                // 固化定义不是新的 LLM 规划，不在此处再次检索数据库候选。
                return executePlan(control, run, unit, definition.plan(), args, null, depth + 1, revision)
                        // 能力各步骤执行完成后，对组合结果做验收。
                        .thenCompose(new Function<Outcome, CompletionStage<Outcome>>() {
                            @Override
                            public CompletionStage<Outcome> apply(Outcome outcome) {
                                return async(control, run, unit, NodeKind.VERIFY, goal, revision, args, new Supplier<Outcome>() {
                                    @Override
                                    public Outcome get() {
                                        return checked(goal, args, outcome);
                                    }
                                });
                            }
                        });
            }
        });
        // 把组合结果写回该能力节点，并返回给上层步骤。
        return finish(control, unit, result);
    }

    // 创建有限 DAG 并按依赖调度；只等待各节点的 Future，不同步阻塞父线程。
    private CompletableFuture<Outcome> executePlan(Control control, String run, String parent, TaskPlan plan,
                                                   Map<String, Object> input, CapabilityLookup.PlanMatches matches, int depth, long revision) {
        // 建立逻辑步骤 ID 到本次实际执行节点 ID 的映射。
        Map<String, String> nodes = new LinkedHashMap<>();
        // 逐一处理计划步骤；数组顺序本身不代表执行依赖。
        for (TaskPlan.Step step : plan.steps()) {
            // 先为每一步建立节点，使后续关系都能找到端点。
            nodes.put(step.id(), node(control, run, parent, NodeKind.STEP, step.goal(), revision,
                    // 保存逻辑步骤、常量参数及参数来源，供固化模块追溯。
                    Map.of("stepId", step.id(), "arguments", step.arguments(), "bindingSources", bindingDetails(step))));
        }
        // 逐一处理计划步骤；数组顺序本身不代表执行依赖。
        for (TaskPlan.Step step : plan.steps()) {
            // 遍历本步骤声明的前驱；只有这些前驱成功后才能运行。
            for (String dependency : step.dependsOn()) {
                // 记录从前驱指向后继的成功依赖关系。
                traces.edge(control.root, new ExecutionTraceStore.Edge(nodes.get(dependency), nodes.get(step.id()), EdgeKind.DEPENDS_ON, Status.SUCCESS.name()));
            }
            // 遍历数据绑定，单独记录“结果从哪里来”。
            for (Map.Entry<String, TaskPlan.InputBinding> entryBinding : step.bindings().entrySet()) {
                String argument = entryBinding.getKey();
                TaskPlan.InputBinding binding = entryBinding.getValue();
                // 任务自身输入不属于前驱节点；只有步骤间取值才创建数据流边。
                if (!binding.source().equals(TaskPlan.TASK_INPUT_SOURCE)) {
                    // 以来源节点为起点，附上目标参数名和 JSON 路径。
                    traces.edge(control.root, new ExecutionTraceStore.Edge(
                            nodes.get(binding.source()), nodes.get(step.id()), EdgeKind.DATA_FROM, argument + "=" + binding.pointer()));
                }
            }
        }
        // 保存每个步骤的异步结果句柄；这里只登记，不调用阻塞等待。
        Map<String, CompletableFuture<Outcome>> futures = new LinkedHashMap<>();
        // 已通过 TaskPlan 的 DAG 校验；按拓扑创建 future，不等待其执行完成。
        while (futures.size() < plan.steps().size()) {
            // 逐一处理计划步骤；数组顺序本身不代表执行依赖。
            for (TaskPlan.Step step : plan.steps()) {
                // 已登记的步骤跳过；前驱 Future 尚未建立的步骤留到下一遍。
                if (futures.containsKey(step.id()) || !futures.keySet().containsAll(step.dependsOn())) {
                    // 当前项尚不需要处理，继续检查下一个项。
                    continue;
                }
                // 收集当前步骤所依赖的 Future，不共享可变的全计划结果表。
                Map<String, CompletableFuture<Outcome>> predecessors = new LinkedHashMap<>();
                // 通过前驱 ID 找到对应的异步完成句柄。
                for (String dependencyId : step.dependsOn()) {
                    predecessors.put(dependencyId, futures.get(dependencyId));
                }
                // 把全部前驱汇成一个完成信号；没有前驱时立即就绪。
                CompletableFuture<Outcome> future = CompletableFuture.allOf(predecessors.values().toArray(new CompletableFuture<?>[0]))
                        // 前驱都结束后才进入当前步骤，等待期间不占用工作线程。
                        .thenCompose(new Function<Void, CompletionStage<Outcome>>() {
                            @Override
                            public CompletionStage<Outcome> apply(Void ignored) {
                                return executeReadyStep(control, run, nodes, step, input, predecessors, matches, depth, revision);
                            }
                        });
                // 登记步骤结果，并在完成时更新该步骤的执行图状态。
                futures.put(step.id(), finish(control, nodes.get(step.id()), future));
            }
        }
        // 所有步骤都结束后汇总本轮计划结果；不提前遗漏慢分支。
        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture<?>[0])).thenApply(new Function<Void, Outcome>() {
            @Override
            public Outcome apply(Void ignored) {
                return collectPlanOutcome(futures);
            }
        });
    }


    // 仅在前驱 Future 全部结束后调用：检查依赖、绑定参数，再分派能力、子任务或工具。
    private CompletableFuture<Outcome> executeReadyStep(Control control, String run, Map<String, String> nodes,
                                                         TaskPlan.Step step, Map<String, Object> input,
                                                         Map<String, CompletableFuture<Outcome>> predecessors,
                                                         CapabilityLookup.PlanMatches matches, int depth, long revision) {
        // 在继续调度前检查共享取消信号和截止时间。
        control.check();
        // 这些 Future 已经完成，join 这里只读取结果；任一前驱失败就阻止后继。
        boolean predecessorFailed = false;
        for (CompletableFuture<Outcome> predecessor : predecessors.values()) {
            if (predecessor.join().status() != Status.SUCCESS) {
                predecessorFailed = true;
                break;
            }
        }
        if (predecessorFailed) {
            // 返回已完成的失败结果，明确本分支没有继续执行动作。
            return CompletableFuture.completedFuture(new Outcome(Status.FAILED, null, LlmTexts.PREDECESSOR_NOT_SUCCESSFUL));
        }
        // 将常量、任务输入和前驱结果拼成这一步最终的工具参数。
        Map<String, Object> args = bind(step, input, predecessors);
        // 读取本计划缓存中该步骤命中的固化；固化展开内部没有新的匹配表。
        var selected = matches == null ? null : matches.steps().get(step.id()).selected().orElse(null);
        // 优先执行已匹配能力，跳过该步骤原本需要的额外求解。
        if (selected != null) {
            // 步骤命中固化后直接执行能力，避免额外模型调用。
            return capability(control, run, nodes.get(step.id()), step.goal(), args, selected, depth, revision);
        }
        // 这里才区分“已有工具”和“需要模型求解的子任务”。
        return switch (step.kind()) {
            // 子任务递归进入任务入口，但不重复根入口固化检索。
            case TASK -> task(control, nodes.get(step.id()), step.goal(), args, depth + 1, false);
            // 普通工具直接执行回调，不额外调用模型。
            case TOOL -> executeToolStep(control, run, nodes.get(step.id()), step, args, revision);
        };
    }

    // 所有步骤完成后汇总结果；这里读取 Future 不会阻塞等待未完成任务。
    private Outcome collectPlanOutcome(Map<String, CompletableFuture<Outcome>> futures) {
        // 建立按步骤 ID 索引的结果，保留成功和失败步骤的实际反馈。
        Map<String, Object> output = new LinkedHashMap<>();
        // 先假定全部成功，再按更需要人工处理的状态逐步提升汇总状态。
        Status status = Status.SUCCESS;
        // 读取每个已完成步骤的结果。
        for (var item : futures.entrySet()) {
            // allOf 已确认结束，这里的 join 不会等待未完成步骤。
            Outcome outcome = item.getValue().join();
            // 每步保留 status/value/error，不能只返回整个计划的一句状态。
            output.put(item.getKey(), outcome.asMap());
            // 更新汇总状态，不改写各步骤独立保存的结果。
            status = mergeStatus(status, outcome.status());
        }
        // 返回完整步骤结果；失败原因由调用方从每步反馈继续查看。
        return new Outcome(status, output, status == Status.SUCCESS ? null : LlmTexts.PLAN_HAS_UNSUCCESSFUL_STEPS);
    }

    // 保持原有优先级：UNKNOWN、CANCELLED、NEEDS_INPUT、FAILED、SUCCESS。
    private static Status mergeStatus(Status current, Status next) {
        return switch (next) {
            case UNKNOWN -> Status.UNKNOWN;
            case CANCELLED -> current == Status.UNKNOWN ? current : Status.CANCELLED;
            case NEEDS_INPUT -> current == Status.UNKNOWN || current == Status.CANCELLED ? current : Status.NEEDS_INPUT;
            case FAILED -> current == Status.SUCCESS ? Status.FAILED : current;
            case SUCCESS -> current;
        };
    }

    /**
     * 执行一个普通工具步骤；参数已经由 bind 从常量和依赖结果中组装完毕。
     */
    private CompletableFuture<Outcome> executeToolStep(Control control, String run, String parent,
                                                       TaskPlan.Step step, Map<String, Object> args, long revision) {
        // 将实际工具动作提交工作池，同时记录 TOOL 节点。
        return async(control, run, parent, NodeKind.TOOL, step.goal(), revision,
                // 记录本次工具名和已经绑定好的真实入参。
                Map.of("tool", step.toolName(), "arguments", args), new Supplier<Outcome>() {
                    @Override
                    public Outcome get() {
                        // 普通工具也先过门禁；null ref 表示这次不是固化能力引用。
                        if (!checks.allow(step.goal(), args, null)) {
                            // 返回明确失败及原因，让所属任务统一决定后续处理。
                            return new Outcome(Status.FAILED, null, LlmTexts.TOOL_NOT_ALLOWED);
                        }
                        // 整棵树共用工具调用计数，超过预算就拒绝新动作。
                        if (control.tools.incrementAndGet() > limits.maxToolCalls()) {
                            // 明确报告当前状态不允许继续；不在这里自动重试。
                            throw new IllegalStateException(LlmTexts.TOOL_CALL_LIMIT_REACHED);
                        }
                        try {
                            // 按工具名和 Map 参数执行，并注入根身份、任务身份、步骤及取消上下文。
                            Outcome outcome = actions.execute(step.toolName(), args, new CallContext(control.root, run, parent, control.deadline, control.stop));
                            // 对实际工具结果做业务验收，不把回调正常返回当成整个任务成功。
                            return checked(step.goal(), args, outcome);
                        } catch (RuntimeException failure) {
                            // 自定义执行适配器也可能在副作用发生后抛错，不能转成可随意重试的普通失败。
                            return new Outcome(Status.UNKNOWN, null, LlmTexts.EXECUTION_UNKNOWN_PREFIX + message(failure));
                        }
                    }
                });
    }

    // 统一提交耗时工作并记录链路；T 可以是决策、检索结果或执行结果。
    private <T> CompletableFuture<T> async(Control control, String run, String parent, NodeKind kind, String goal,
                                           long revision, Map<String, Object> input, Supplier<T> action) {
        // 为即将提交的动作建立本次调用身份。
        String id = node(control, run, parent, kind, goal, revision, input);
        try {
            // 提交工作到调用方提供的执行器，不在当前调度线程执行耗时操作。
            return CompletableFuture.supplyAsync(new Supplier<T>() {
                @Override
                public T get() {
                    // 在继续调度前检查共享取消信号和截止时间。
                    control.check();
                    // 实际执行模型调用、检索、门禁或工具动作。
                    return action.get();
                }
            // 无论动作成功还是异常，都记录动作的结束情况。
            }, workers).whenComplete(new BiConsumer<T, Throwable>() {
                @Override
                public void accept(T value, Throwable failure) {
                    // 业务结果保留结构化内容；其他动作保存当前支持的摘要。
                    Object recorded = value instanceof Outcome outcome ? outcome.asMap()
                            // 模型节点保存决策类型和计划目标，尚不保存完整模型响应。
                            : value instanceof Decision decision ? Map.of("decision", decision.kind().name(), "goal", decision.plan() == null ? "" : decision.plan().goal())
                            // 门禁保留布尔结果；其他无专用结构的返回值暂记完成摘要。
                            : value instanceof Boolean flag ? flag : "完成";
                    // 正常返回记 COMPLETED；异常记 FAILED，这不同于业务 SUCCESS。
                    traces.finish(control.root, id, failure == null ? NodeStatus.COMPLETED : NodeStatus.FAILED, recorded,
                            // 仅异常路径保存错误原因，正常动作不伪造错误。
                            failure == null ? null : message(failure));
                }
            });
        } catch (RuntimeException rejected) {
            // 工作池拒绝接收任务时，仍把该动作标记为失败。
            traces.finish(control.root, id, NodeStatus.FAILED, null, message(rejected));
            // 把提交失败交回异步链，让所属任务统一收尾。
            return CompletableFuture.failedFuture(rejected);
        }
    }

    // 将异步异常转为 Outcome，再统一结束任务/步骤节点。
    private CompletableFuture<Outcome> finish(Control control, String node, CompletableFuture<Outcome> work) {
        // handle 同时接收正常结果和异常，保证两条路径都留下记录。
        return work.handle(new BiFunction<Outcome, Throwable, Outcome>() {
            @Override
            public Outcome apply(Outcome outcome, Throwable failure) {
                // 正常结果直接保留；异步异常转换为明确失败。
                Outcome actual = failure == null ? outcome : failed(failure);
                // 业务状态转换为同名链路枚举，保存实际结果和错误。
                traces.finish(control.root, node, NodeStatus.valueOf(actual.status().name()), actual.value(), actual.error());
                // 向父任务返回已经记录的统一结果。
                return actual;
            }
        });
    }

    // 创建一个执行节点并连接父节点；输入快照由 Node 构造器深复制。
    private String node(Control control, String run, String parent, NodeKind kind, String goal, long revision, Map<String, Object> input) {
        // 每次调用都生成新节点，不用逻辑步骤 ID 覆盖旧执行。
        String id = UUID.randomUUID().toString();
        // 登记为 RUNNING；当前该状态也包括排队和等待依赖。
        traces.add(control.root, new ExecutionTraceStore.Node(id, run, parent, kind, goal, revision, input, NodeStatus.RUNNING, null, null, Instant.now(), null));
        // 根节点没有父节点，只有后代需要建立创建关系。
        if (parent != null) {
            // SPAWNED 只表示创建归属；空 detail 表示该关系没有额外参数说明。
            traces.edge(control.root, new ExecutionTraceStore.Edge(parent, id, EdgeKind.SPAWNED, ""));
        }
        // 把节点身份返回给后续关联及完成记录。
        return id;
    }

    // 成功返回还要业务验收；非成功结果保留原状态。
    private Outcome checked(String goal, Map<String, Object> args, Outcome outcome) {
        // 执行器不能返回 null，否则无法判定真实结果。
        Objects.requireNonNull(outcome, LlmTexts.EMPTY_EXECUTOR_OUTCOME);
        // 失败、取消、未知等结果不再当成功结果验收。
        if (outcome.status() != Status.SUCCESS) {
            // 原样保留已有状态，避免丢失副作用未知等重要信息。
            return outcome;
        }
        try {
            // 验收通过才保留成功；明确不通过则保存原结果并标记失败。
            return checks.verify(goal, args, outcome.value()) ? outcome : new Outcome(Status.FAILED, outcome.value(), LlmTexts.VERIFICATION_FAILED);
        } catch (RuntimeException failure) {
            // 动作可能已经成功，验收服务故障不能触发自动重做动作。
            return new Outcome(Status.UNKNOWN, outcome.value(), LlmTexts.VERIFICATION_UNKNOWN_PREFIX + message(failure));
        }
    }

    // 在执行任意步骤之前统一校验工具白名单。
    private static void validateTools(TaskPlan plan, List<String> allowed) {
        // 逐一处理计划步骤；数组顺序本身不代表执行依赖。
        for (TaskPlan.Step step : plan.steps()) {
            // 只有 TOOL 使用实际工具名，TASK 由递归任务入口处理。
            if (step.kind() == TaskPlan.Kind.TOOL && !allowed.contains(step.toolName())) {
                // 抛出参数或协议错误，阻止无效输入继续进入执行。
                throw new IllegalArgumentException(LlmTexts.TOOL_NOT_ALLOWED_PREFIX + step.toolName());
            }
        }
    }

    // 把绑定来源转为普通 JSON 字段，便于快照导出和固化读取。
    private static Map<String, Object> bindingDetails(TaskPlan.Step step) {
        // 建立可顺序写入的临时结果表，返回前再做只读处理。
        Map<String, Object> result = new LinkedHashMap<>();
        // 保存每个目标参数对应的来源步骤和 JSON Pointer。
        for (Map.Entry<String, TaskPlan.InputBinding> entryBinding : step.bindings().entrySet()) {
            TaskPlan.InputBinding binding = entryBinding.getValue();
            result.put(entryBinding.getKey(), Map.of("source", binding.source(), "pointer", binding.pointer()));
        }
        // 返回刚组装的局部结果，由接收方进行快照封装。
        return result;
    }

    // 按明确数据路径绑定参数，不让执行器猜测自然语言中的参数关系。
    private static Map<String, Object> bind(TaskPlan.Step step, Map<String, Object> input, Map<String, CompletableFuture<Outcome>> dependencies) {
        // 从模型提供的常量参数开始；后续绑定覆盖同名参数。
        Map<String, Object> args = new LinkedHashMap<>(step.arguments());
        // 逐个读取需要从任务输入或前驱结果填充的参数。
        for (Map.Entry<String, TaskPlan.InputBinding> entryBinding : step.bindings().entrySet()) {
            String key = entryBinding.getKey();
            TaskPlan.InputBinding binding = entryBinding.getValue();
            // 保留字读取任务输入；其他来源读取已成功前驱的 value，而不是状态包装层。
            Object value = binding.source().equals(TaskPlan.TASK_INPUT_SOURCE) ? input : dependencies.get(binding.source()).join().value();
            // 空路径表示整个输入/结果；非空路径才继续逐层取字段。
            if (!binding.pointer().isEmpty()) {
                // 跳过开头的斜线并拆路径；split 的 -1 保留末尾空字段名。
                for (String segment : binding.pointer().substring(1).split("/", -1)) {
                    // JSON Pointer 约定 ~1 表示斜线、~0 表示波浪号，顺序不能颠倒。
                    String part = segment.replace("~1", "/").replace("~0", "~");
                    // 对象类型按字段名取值；containsKey 区分合法 null 和不存在字段。
                    if (value instanceof Map<?, ?> map && map.containsKey(part)) {
                        // 进入对象中的下一层字段。
                        value = map.get(part);
                    }
                    // 数组类型把路径段解析为下标；非法数字/越界会报错。
                    else if (value instanceof List<?> list) {
                        // 进入数组的指定元素。
                        value = list.get(Integer.parseInt(part));
                    }
                    // 路径和实际结果结构不一致时终止本步骤，不传错值给工具。
                    else throw new IllegalArgumentException(LlmTexts.ARGUMENT_PATH_MISSING_PREFIX + binding.pointer());
                }
            }
            // 写入最终绑定值；显式 bindings 优先于同名常量。
            args.put(key, value);
        }
        // 深复制最终参数，防止并发工具互相修改输入。
        return TaskPlan.immutableMap(args);
    }

    // 统一表示未明确进入动作副作用阶段的异步失败。
    private static Outcome failed(Throwable failure) {
        // 返回明确失败及原因，让所属任务统一决定后续处理。
        return new Outcome(Status.FAILED, null, message(failure));
    }

    // 提取真实异常原因，避免只看到异步包装异常。
    private static String message(Throwable failure) {
        // 解开 CompletionException 包装，保留最内层业务异常。
        while (failure.getCause() != null && failure instanceof java.util.concurrent.CompletionException) {
            // 沿着异步包装链读取原始异常。
            failure = failure.getCause();
        }
        // 保留异常类型和消息，便于定位故障来源。
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    @FunctionalInterface
    // 单次模型决策端口；实现必须只做一次模型决策。
    public interface Planner {
        // 输入本轮上下文，返回计划、完成或需要信息三者之一。
        Decision decide(PlanningContext context);
    }

    @FunctionalInterface
    // 普通工具执行端口；不负责自然语言拆分和递归任务。
    public interface ActionExecutor {
        // 接收已绑定参数与执行上下文，返回实际执行结果。
        Outcome execute(String tool, Map<String, Object> args, CallContext context);
    }

    // 调用方注入的门禁与结果验收接口。
    public interface Checks {
        /**
         * ref 为空表示普通工具。
         */
        boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref);

        /**
         * 业务验收，由调用方提供真实规则。
         */
        boolean verify(String goal, Map<String, Object> args, Object output);
    }

    // 业务结果状态，与模型决策类型分开保存。
    public enum Status {
        /**
         * 执行结果通过本次验收。
         */
        SUCCESS,
        /**
         * 已知没有达到要求，允许反馈给模型重新规划。
         */
        FAILED,
        /**
         * 副作用或验收结果未知，不能自动重做。
         */
        UNKNOWN,
        /**
         * 任务收到取消或超时信号。
         */
        CANCELLED,
        /**
         * 需要用户补充信息。
         */
        NEEDS_INPUT
    }

    // 模型可提出的决策类型；由 runDecisionRound 的 switch 分发。
    public enum DecisionKind {
        /**
         * 提交新的执行计划。
         */
        PLAN,
        /**
         * 提交最终结果，等待运行时验收。
         */
        COMPLETE,
        /**
         * 提交需要用户回答的问题。
         */
        NEEDS_INPUT
    }

    public record Request(
            /** 用户目标。 */
            String goal,
            /** 初始参数。 */
            Map<String, Object> arguments,
            /** 模型配置引用。 */
            String modelRef,
            /** 本根任务及后代允许使用的普通工具。 */
            List<String> toolNames) {
        // 校验并固定根请求数据，避免提交后被调用方修改。
        public Request {
            // 目标不能为空，字段名用于错误定位。
            TaskPlan.requireText(goal, "goal");
            // 必须指定可解析的模型引用。
            TaskPlan.requireText(modelRef, "modelRef");
            // 深复制参数，包括嵌套 Map/List，避免并发共享可变对象。
            arguments = TaskPlan.immutableMap(arguments);
            // 固定本任务允许工具名单，子任务不能自行扩大。
            toolNames = List.copyOf(toolNames);
        }
    }

    public record Limits(
            /** 每个任务最多模型决策轮数，包含结束决策。 */
            int maxRounds,
            /** 子任务及固化展开最大深度。 */
            int maxDepth,
            /** 整棵树最多任务数。 */
            int maxTasks,
            /** 每个计划的步骤上限。 */
            int maxSteps,
            /** 整棵树模型调用预算。 */
            int maxModelCalls,
            /** 整棵树工具调用预算。 */
            int maxToolCalls,
            /** 根任务共享总时限。 */
            Duration timeout) {
        // 检查预算配置是否合法，所有数量和时限都必须为正。
        public Limits {
            // 零预算或非正时限没有有效执行空间，装配时就拒绝。
            if (maxRounds < 1 || maxDepth < 1 || maxTasks < 1 || maxSteps < 1 || maxModelCalls < 1 || maxToolCalls < 1 || timeout.isNegative() || timeout.isZero() || timeout.toMillis() < 1) {
                // 抛出参数或协议错误，阻止无效输入继续进入执行。
                throw new IllegalArgumentException("无效执行限制");
            }
        }
    }

    public record Outcome(
            /** 实际执行状态；UNKNOWN 不触发自动重规划。 */
            Status status,
            /** 不可变结果。 */
            Object value,
            /** 错误原因，可为空。 */
            String error) {
        // 结果只允许有明确状态和可安全共享的 JSON 值。
        public Outcome {
            // 要求调用方明确成功、失败、未知、取消或需要信息。
            Objects.requireNonNull(status);
            // 把结果深复制为只读数据，防止反馈被后续调用改写。
            value = TaskPlan.immutable(value);
        }

        // 把业务结果转成可供模型和链路读取的稳定 JSON 字段。
        public Map<String, Object> asMap() {
            // 建立可顺序写入的临时结果表，返回前再做只读处理。
            Map<String, Object> result = new LinkedHashMap<>();
            // 协议字段 status 保存枚举名称。
            result.put("status", status.name());
            // 协议字段 value 保存实际输出，可以为空。
            result.put("value", value);
            // 协议字段 error 保存错误原因，可以为空。
            result.put("error", error);
            // 冻结完整输出对象，允许 null 值但禁止后续修改。
            return TaskPlan.immutableMap(result);
        }
    }

    public record Decision(
            /** 模型提出的操作类型。 */
            DecisionKind kind,
            /** PLAN 类型携带的完整有限计划。 */
            TaskPlan plan,
            /** 最终答复或需要用户补充的问题。 */
            Object output) {
        // 校验决策载荷：只有 PLAN 能携带计划。
        public Decision {
            // 决策必须指定类型，不能用空值让代码猜测。
            Objects.requireNonNull(kind);
            // 固定模型最终答复或补充问题的内容。
            output = TaskPlan.immutable(output);
            // PLAN 必须有计划，COMPLETE/NEEDS_INPUT 必须没有计划。
            if ((kind == DecisionKind.PLAN) != (plan != null)) {
                // 抛出参数或协议错误，阻止无效输入继续进入执行。
                throw new IllegalArgumentException("PLAN 决策必须且仅包含计划");
            }
        }
    }

    public record Feedback(
            /** 该反馈对应的模型计划版本。 */
            long revision,
            /** 已尝试执行的计划。 */
            TaskPlan plan,
            /** 每步结果及汇总状态。 */
            Outcome outcome) {
    }

    public record PlanningContext(
            /** 所属任务身份。 */
            String runId,
            /** 当前目标。 */
            String goal,
            /** 当前任务参数。 */
            Map<String, Object> arguments,
            /** 模型引用。 */
            String modelRef,
            /** 当前允许的普通工具名。 */
            List<String> toolNames,
            /** 入口检索候选；不能替代每次规划后的数据库检索。 */
            List<CapabilityLookup.Candidate> candidates,
            /** 本任务已完成轮次的实际反馈。 */
            List<Feedback> history) {
        // 固定这一轮看到的上下文；下一轮重新构造快照。
        public PlanningContext {
            // 深复制参数，包括嵌套 Map/List，避免并发共享可变对象。
            arguments = TaskPlan.immutableMap(arguments);
            // 固定本任务允许工具名单，子任务不能自行扩大。
            toolNames = List.copyOf(toolNames);
            // 固定入口检索候选集合，不让并发修改影响本轮模型。
            candidates = List.copyOf(candidates);
            // 固定历史反馈顺序，避免异步写入正在使用的上下文。
            history = List.copyOf(history);
        }
    }

    public record Handle(
            /** 供执行图查询使用的根身份。 */
            String rootRunId,
            /** 对外结果，超时/取消可以早于实际工具结束。 */
            CompletableFuture<Outcome> result,
            /** 请求停止继续调度，不承诺撤销副作用。 */
            Runnable cancel) {
    }

    public record CallContext(
            /** 根运行。 */
            String rootRunId,
            /** 当前子任务。 */
            String runId,
            /** 当前步骤。 */
            String nodeId,
            /** 根截止时间。 */
            Instant deadline,
            /** 本运行树共享取消原因。 */
            AtomicReference<String> stop) {
        // 允许执行器在资源等待或新动作开始前检查取消。
        public void check() {
            // 有停止原因或已超时都拒绝继续启动动作。
            if (stop.get() != null || Instant.now().isAfter(deadline)) {
                // 明确报告当前状态不允许继续；不在这里自动重试。
                throw new IllegalStateException(LlmTexts.TASK_STOPPED);
            }
        }
    }

    // 一棵运行树共享的控制数据；计数器及取消原因支持并发访问。
    private static final class Control {
        /**
         * 根任务身份。
         */
        private final String root;
        /**
         * 根输入及工具边界。
         */
        private final Request request;
        /**
         * 整棵任务树共用截止时间。
         */
        private final Instant deadline;
        /**
         * 后代共享取消信号。
         */
        private final AtomicReference<String> stop = new AtomicReference<>();
        /**
         * 整棵树的任务预算。
         */
        private final AtomicInteger tasks = new AtomicInteger();
        /**
         * 整棵树的模型预算。
         */
        private final AtomicInteger models = new AtomicInteger();
        /**
         * 整棵树的普通工具预算。
         */
        private final AtomicInteger tools = new AtomicInteger();

        // 保存根请求与总时限，不给每个子任务重置预算。
        private Control(String root, Request request, Instant deadline) {
            // 保存执行图的根身份。
            this.root = root;
            // 保存根请求的模型引用、参数与工具允许集合。
            this.request = request;
            // 保存根任务统一截止时间。
            this.deadline = deadline;
        }

        // 复用与工具上下文相同的取消/超时判断。
        private void check() {
            // 构建轻量上下文并检查，子任务不能自行忽略根停止信号。
            new CallContext(root, root, root, deadline, stop).check();
        }
    }
}
