package com.example.myhelper.orchestration;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.capability.CapabilityUsePort;
import com.example.myhelper.capability.CheckedCapabilityUse;
import com.example.myhelper.gate.RuntimeGate;
import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.llm.ModelAdapter;
import com.example.myhelper.records.ExecutionTracePort;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.records.TaskInstructionPort;
import com.example.myhelper.records.TaskRecordPort.Disposition;
import com.example.myhelper.records.TaskRecordPort.EventKind;
import com.example.myhelper.records.TaskRecordPort.RecoverySnapshot;
import com.example.myhelper.records.TaskRecordPort.TaskSnapshot;
import com.example.myhelper.skill.ToolReliability.PlanningReference;
import com.example.myhelper.tasklog.TaskLogPort;
import com.example.myhelper.tasklog.TaskLogPort.Kind;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import com.example.myhelper.orchestration.ExecutionTraceStore.NodeKind;
import com.example.myhelper.orchestration.ExecutionTraceStore.EdgeKind;
import com.example.myhelper.orchestration.ExecutionTraceStore.NodeStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    /** 最近自动接续句柄的有界缓存；持久命令去重仍由外部记录端口负责。 */
    private static final int AMENDMENT_HANDLE_LIMIT = 128;
    /** 同一命令的自动接续只创建一次；缓存淘汰后仍须通过外部恢复资格检查。 */
    private final Map<AmendmentKey, PendingAmendment> amendments = new LinkedHashMap<>();
    /** 仅保留本实例仍在运行的控制句柄；权威指令和进度始终由记录模块保存。 */
    private final Map<String, Control> activeRuns = new ConcurrentHashMap<>();
    /**
     * 每次调用只产生一个决策的规划桥接器，与负责分配模型的 ModelAdapter 不同。
     */
    private final Planner planner;
    /**
     * 独立检索及内容缓存。
     */
    private final CapabilitySearchPort lookup;
    /** 外部模型模块；循环只提交分支需求，不维护模型候选、计数或客户端。 */
    private final ModelAdapter modelAdapter;
    /**
     * 普通工具执行端口，不负责递归任务。
     */
    private final ActionExecutor actions;
    /**
     * 本次执行条件与结果检查，不能默认缓存一次通过结论。
     */
    private final RuntimeGate checks;
    /**
     * 共享、只读可查询的执行关系记录。
     */
    private final ExecutionTracePort traces;
    /** 执行记录、进度和恢复事项由外部模块管理，循环只发送及查询。 */
    private final TaskRecordPort records;
    /** 与权威记录确认相互独立的实时观测输出。 */
    private final TaskLogPort taskLog;
    /** 根运行受理和实际收尾的本地活动通知。 */
    private final RunActivityPort activity;
    /** 固化适用判断来自独立端口，不由循环推断资格或业务影响。 */
    private final CapabilityUsePort capabilityUse;
    /** 调用技能模块查询分数，决定固化工具是否可以省略模型判断。 */
    private final ToolReliabilityGate toolReliability;
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
    public AgentOrchestrator(Planner planner, CapabilitySearchPort lookup, ActionExecutor actions, RuntimeGate checks,
                             ExecutionTracePort traces, Executor workers, ScheduledExecutorService timer, Limits limits) {
        // 保留旧构造签名；缺少可靠度查询会回模型，正式装配应使用完整构造。
        this(planner, lookup, actions, checks, traces, workers, timer, limits, null);
    }

    // 模型装配入口：分配和绑定由适配器负责，本类只管理分支拓扑及调用时机。
    public AgentOrchestrator(Planner planner, CapabilitySearchPort lookup, ActionExecutor actions, RuntimeGate checks,
                             ExecutionTracePort traces, Executor workers, ScheduledExecutorService timer, Limits limits,
                             ModelAdapter modelAdapter) {
        this(planner, lookup, actions, checks, traces, workers, timer, limits, modelAdapter, TaskRecordPort.disabled());
    }

    // 记录模块可独立接入；旧资格规则由独立适配对象承接。
    public AgentOrchestrator(Planner planner, CapabilitySearchPort lookup, ActionExecutor actions, RuntimeGate checks,
                             ExecutionTracePort traces, Executor workers, ScheduledExecutorService timer, Limits limits,
                             ModelAdapter modelAdapter, TaskRecordPort records) {
        this(planner, lookup, actions, checks, traces, workers, timer, limits, modelAdapter,
                records, new CheckedCapabilityUse(lookup, checks));
    }

    // 完整装配入口：模型、固化适用和记录管理均由独立模块提供。
    public AgentOrchestrator(Planner planner, CapabilitySearchPort lookup, ActionExecutor actions, RuntimeGate checks,
                             ExecutionTracePort traces, Executor workers, ScheduledExecutorService timer, Limits limits,
                             ModelAdapter modelAdapter, TaskRecordPort records, CapabilityUsePort capabilityUse) {
        this(planner, lookup, actions, checks, traces, workers, timer, limits, modelAdapter,
                records, capabilityUse, ToolReliabilityGate.unavailable());
    }

    // 可靠度查询独立注入；旧构造缺查询时回模型，不继续无评分的固化执行。
    public AgentOrchestrator(Planner planner, CapabilitySearchPort lookup, ActionExecutor actions, RuntimeGate checks,
                             ExecutionTracePort traces, Executor workers, ScheduledExecutorService timer, Limits limits,
                             ModelAdapter modelAdapter, TaskRecordPort records, CapabilityUsePort capabilityUse,
                             ToolReliabilityGate toolReliability) {
        this(planner, lookup, actions, checks, traces, workers, timer, limits, modelAdapter, records, capabilityUse,
                toolReliability, TaskLogPort.disabled());
    }

    public AgentOrchestrator(Planner planner, CapabilitySearchPort lookup, ActionExecutor actions, RuntimeGate checks,
                             ExecutionTracePort traces, Executor workers, ScheduledExecutorService timer, Limits limits,
                             ModelAdapter modelAdapter, TaskRecordPort records, CapabilityUsePort capabilityUse,
                             ToolReliabilityGate toolReliability, TaskLogPort taskLog) {
        this(planner, lookup, actions, checks, traces, workers, timer, limits, modelAdapter, records, capabilityUse,
                toolReliability, taskLog, RunActivityPort.disabled());
    }

    public AgentOrchestrator(Planner planner, CapabilitySearchPort lookup, ActionExecutor actions, RuntimeGate checks,
                             ExecutionTracePort traces, Executor workers, ScheduledExecutorService timer, Limits limits,
                             ModelAdapter modelAdapter, TaskRecordPort records, CapabilityUsePort capabilityUse,
                             ToolReliabilityGate toolReliability, TaskLogPort taskLog, RunActivityPort activity) {
        // 保存单次模型决策桥接器，编排器通过它调用模型。
        this.planner = Objects.requireNonNull(planner);
        // 保存独立固化检索组件，便于以后替换检索实现。
        this.lookup = Objects.requireNonNull(lookup);
        // 允许尚未装配，以便入口命中纯工具固化时完全跳过模型模块。
        this.modelAdapter = modelAdapter;
        // 保存普通工具执行器；递归任务仍由本类负责。
        this.actions = Objects.requireNonNull(actions);
        // 保存真实门禁和验收策略，不在编排器内默认放行。
        this.checks = Objects.requireNonNull(checks);
        // 保存共享执行图，其他模块通过它读取运行关系。
        this.traces = Objects.requireNonNull(traces);
        this.records = Objects.requireNonNull(records);
        this.taskLog = Objects.requireNonNull(taskLog);
        this.activity = Objects.requireNonNull(activity);
        this.capabilityUse = Objects.requireNonNull(capabilityUse);
        this.toolReliability = Objects.requireNonNull(toolReliability);
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
        var occupation = activity.occupy();
        try {
            return start(request, null, occupation);
        } catch (RuntimeException | Error failure) {
            occupation.close();
            throw failure;
        }
    }

    /** 从外部记录读取原任务和最新问题；不重新执行根入口整条固化。 */
    public Handle resume(String taskId) {
        var occupation = activity.occupy();
        try {
            if (!records.enabled()) {
                throw new IllegalStateException(LlmTexts.TASK_RECORDS_REQUIRED);
            }
            TaskSnapshot task = records.load(taskId);
            if (!taskId.equals(task.taskId())) {
                throw new IllegalStateException(LlmTexts.INVALID_RECORD_CLAIM);
            }
            TaskRecordPort.TaskSpec original = task.task();
            var request = new Request(original.goal(), original.arguments(), original.modelRef(), original.toolNames(),
                    original.internalValidation());
            return start(request, task, occupation);
        } catch (RuntimeException | Error failure) {
            occupation.close();
            throw failure;
        }
    }

    /** 应用退出只停止本次派发，不把用户目标改写为持久 CANCEL。 */
    public void stopForShutdown(String taskId, String rootRunId) {
        Control control = activeRuns.get(taskId);
        if (control != null && control.root.equals(rootRunId)) {
            control.requestLocalStop(LlmTexts.TASK_APPLICATION_STOPPING);
        }
    }
    /** 接收并持久保存指令；返回表示已接收，不表示在途工具已经结束。 */
    public TaskInstructionPort.Receipt instruct(TaskInstructionPort.Command command) {
        if (!records.enabled() || !records.instructions().enabled()) {
            throw new IllegalStateException(LlmTexts.TASK_INSTRUCTIONS_REQUIRED);
        }
        TaskInstructionPort.Receipt receipt = records.instructions().accept(command);
        Control running = activeRuns.get(receipt.taskId());
        if (running != null && receipt.revision() > running.records.instructions().revision()) {
            running.requestInstructionStop(receipt);
        }
        return receipt;
    }

    /** 修改同一任务；异步等待真实收尾后恢复，不占工作线程阻塞等待。 */
    public synchronized CompletableFuture<Handle> amend(TaskInstructionPort.Command command) {
        if (command.kind() != TaskInstructionPort.Kind.AMEND) {
            throw new IllegalArgumentException(LlmTexts.AMEND_COMMAND_REQUIRED);
        }
        AmendmentKey key = new AmendmentKey(command.taskId(), command.commandId());
        PendingAmendment existing = amendments.get(key);
        if (existing != null) {
            if (!existing.command().equals(command)) {
                throw new IllegalArgumentException(LlmTexts.INSTRUCTION_ID_CONFLICT);
            }
            return existing.continuation();
        }
        trimAmendments();
        Control beforeAcceptance = activeRuns.get(command.taskId());
        TaskInstructionPort.Receipt receipt = instruct(command);
        Control running = activeRuns.get(receipt.taskId());
        if (running == null) {
            running = beforeAcceptance;
        }
        CompletableFuture<Handle> continuation;
        if (running == null) {
            continuation = resumeAccepted(receipt.taskId());
        } else if (running.records.instructions().revision() >= receipt.revision()) {
            continuation = running.started;
        } else {
            // 同一旧运行收尾期间连续到来的修订共享一次接续；新运行 bind 读取全部最新输入。
            if (running.continuation == null) {
                running.continuation = running.settled.thenComposeAsync(new Function<Outcome, CompletionStage<Handle>>() {
                    @Override
                    public CompletionStage<Handle> apply(Outcome ignored) {
                        return resumeAccepted(receipt.taskId());
                    }
                }, workers);
            }
            continuation = running.continuation;
        }
        amendments.put(key, new PendingAmendment(command, continuation));
        return continuation;
    }

    // 只淘汰已完成句柄，不能丢掉正在等待实际动作结束的接续。
    private void trimAmendments() {
        if (amendments.size() < AMENDMENT_HANDLE_LIMIT) {
            return;
        }
        var entries = amendments.entrySet().iterator();
        while (entries.hasNext()) {
            if (entries.next().getValue().continuation().isDone()) {
                entries.remove();
                return;
            }
        }
        throw new IllegalStateException(LlmTexts.INSTRUCTION_CONTINUATION_LIMIT);
    }

    // 同实例多条连续修改可以共用已启动的新运行；跨实例冲突仍由外部领取原子拒绝。
    private synchronized CompletableFuture<Handle> resumeAccepted(String taskId) {
        Control running = activeRuns.get(taskId);
        if (running != null) {
            return running.started;
        }
        try {
            return CompletableFuture.completedFuture(resume(taskId));
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    // 新任务与恢复共用运行生命周期；跨运行任务身份和领取由外部管理。
    private Handle start(Request request, TaskSnapshot previous, RunActivityPort.Occupation occupation) {
        // 为新根任务生成身份，后续子任务和链路都归属于它。
        String root = UUID.randomUUID().toString();
        RunRecordAccess recordAccess = new RunRecordAccess(records, taskLog, root, request, previous);
        Control control;
        CompletableFuture<Outcome> result = new CompletableFuture<>();
        ScheduledFuture<?> timeout;
        // 尚未派发工作时的任何启动失败，都要归还已经取得的外部运行权。
        try {
            traces.open(root);
            control = new Control(root, request, Instant.now().plus(limits.timeout()), modelAdapter,
                    planner.requirements(), recordAccess, previous != null);
            timeout = timer.schedule(new Runnable() {
                @Override
                public void run() {
                    result.complete(control.requestLocalStop(LlmTexts.TASK_TIMEOUT));
                }
            }, limits.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException failure) {
            try {
                traces.close(root);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            try {
                recordAccess.end(failed(failure));
            } catch (RuntimeException recordFailure) {
                failure.addSuppressed(recordFailure);
            }
            throw failure;
        }
        activeRuns.put(recordAccess.taskId(), control);
        recordAccess.progress(recordAccess.logContext(root, root, root, 0), Kind.RUN_STARTED,
                previous == null ? "任务运行已受理" : "恢复运行已受理");
        // 保存真正的在途工作链；它可能比对外的取消结果晚结束。
        CompletableFuture<Outcome> work;
        try {
            // 从深度 0 进入根任务；true 表示允许进行唯一一次入口检索。
            work = task(control, null, request.goal(), request.arguments(), 0, true, control.modelAccess.rootContext());
        } catch (RuntimeException failure) {
            // 把同步启动异常转换为统一失败结果。
            work = CompletableFuture.completedFuture(failed(failure));
        }
        // 真正工作链结束时执行收尾，即使对外已经返回取消也要收尾。
        work.whenComplete(new BiConsumer<Outcome, Throwable>() {
            @Override
            public void accept(Outcome outcome, Throwable failure) {
                Outcome completed;
                try {
                    // 取消尚未触发的计时任务；false 表示不强行中断正在执行的计时线程。
                    timeout.cancel(false);
                    // 把模型会话回收放在真正工作结束后，取消提前返回时不执行它。
                    completed = endRun(control, failure == null ? outcome : failed(failure));
                } finally {
                    // 先释放本根运行，再通知接续。对外提前返回的结果不释放占用。
                    activeRuns.remove(recordAccess.taskId(), control);
                    occupation.close();
                }
                result.complete(completed);
                if (control.cleanupFailure == null) {
                    control.settled.complete(completed);
                } else {
                    control.settled.completeExceptionally(control.cleanupFailure);
                }
            }
        });
        // 同时返回根身份、异步结果和取消入口。
        Handle handle = new Handle(root, result, new Runnable() {
            @Override
            public void run() {
                if (control.settled.isDone()) {
                    return;
                }
                if (records.instructions().enabled()) {
                    TaskInstructionPort.Snapshot latest = records.instructions().query(recordAccess.taskId());
                    instruct(new TaskInstructionPort.Command(recordAccess.taskId(), UUID.randomUUID().toString(),
                            latest.revision(), TaskInstructionPort.Kind.CANCEL, LlmTexts.TASK_CANCELLED));
                    // 指令入口已持久保存停止请求；这里仅设置本地旧取消信号。
                    control.stop.compareAndSet(null, LlmTexts.TASK_CANCELLED);
                    result.complete(new Outcome(Status.CANCELLED, null, control.stop.get()));
                    return;
                }
                // 尝试完成对外结果；若已完成则不覆盖先前结果。
                result.complete(control.requestLocalStop(LlmTexts.TASK_CANCELLED));
            }
        }, recordAccess.taskId(), control.settled);
        control.started.complete(handle);
        return handle;
    }

    // 分别处理各项收尾；其中一项故障不能跳过另一项，也不能丢掉已完成动作的产物。
    private Outcome endRun(Control control, Outcome outcome) {
        Outcome completed = outcome;
        // 超时计时器只置停止信号；实际收尾在释放领取前保存该停止事实。
        control.recordLocalStop();
        // 这次运行的停止不覆盖已发生的产物；新版目标不能被旧 SUCCESS 宣告完成。
        try {
            control.checkForSettlement();
            if (completed.status() == Status.SUCCESS
                    && !control.records.complete(control.completionRevision.get(), completed)) {
                completed = new Outcome(Status.WAITING, completed.value(), LlmTexts.TASK_CONTROL_WAIT);
            }
        } catch (RunInterrupted interrupted) {
            completed = new Outcome(interrupted.status, completed.value(), interrupted.getMessage());
        } catch (RuntimeException failure) {
            completed = uncertain(completed, message(failure));
        }
        if (control.stopRecordFailure.get() != null) {
            control.cleanupFailure = control.stopRecordFailure.get();
            completed = uncertain(completed, message(control.stopRecordFailure.get()));
        }
        try {
            control.modelAccess.close();
        } catch (RuntimeException failure) {
            control.cleanupFailure = failure;
            completed = uncertain(completed, LlmTexts.MODEL_ADAPTER_CLOSE_FAILED_PREFIX + message(failure));
            try {
                String id = node(control, control.root, null, NodeKind.MODEL_SESSION,
                        LlmTexts.MODEL_ADAPTER_CLOSE_FAILED_PREFIX, 0, Map.of("phase", "close", "branchId", control.root));
                traces.finish(control.root, id, NodeStatus.UNKNOWN, completed.value(), completed.error());
            } catch (RuntimeException traceFailure) {
                completed = uncertain(completed, message(traceFailure));
            }
        }
        List<String> archiveGaps = new ArrayList<>();
        try {
            traces.close(control.root);
        } catch (RuntimeException traceFailure) {
            control.cleanupFailure = traceFailure;
            completed = uncertain(completed, message(traceFailure));
            archiveGaps.add(LlmTexts.ARCHIVE_CLOSE_FAILED);
        }
        ExecutionTraceStore.Snapshot finalGraph = null;
        if (control.records.enabled()) {
            try {
                finalGraph = traces.snapshot(control.root);
            } catch (RuntimeException traceFailure) {
                control.cleanupFailure = traceFailure;
                completed = uncertain(completed, message(traceFailure));
                archiveGaps.add(LlmTexts.ARCHIVE_SNAPSHOT_FAILED);
            }
        }
        try {
            control.records.end(completed, finalGraph, archiveGaps);
        } catch (RuntimeException recordFailure) {
            control.cleanupFailure = recordFailure;
            completed = uncertain(completed, LlmTexts.RECORD_END_FAILED_PREFIX + message(recordFailure));
        }
        return completed;
    }

    // 建立一个根任务或子任务；只有根任务的 entry 为 true。
    private CompletableFuture<Outcome> task(Control control, String parent, String goal, Map<String, Object> args,
                                            int depth, boolean entry, ModelAdapter.Binding branch) {
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
            // 先查询外部待处理数据；恢复运行不从入口固化重新执行原任务。
            result = queryRecovery(control, run, node, goal, 0).thenCompose(new Function<RecoverySnapshot, CompletionStage<Outcome>>() {
                @Override
                public CompletionStage<Outcome> apply(RecoverySnapshot snapshot) {
                    if (control.resuming || snapshot.disposition() != Disposition.CONTINUE) {
                        return runDecisionRound(control, run, node, goal, args, depth, 0, List.of(), List.of(), branch);
                    }
                    return executeEntry(control, run, node, goal, args, depth, branch, snapshot);
                }
            });
        } else {
            // 递归任务直接进入第一轮模型，不重复根入口检索。
            result = runDecisionRound(control, run, node, goal, args, depth, 0, List.of(), List.of(), branch);
        }
        // 为任务完成结果附加执行图更新，再返回异步句柄。
        return finish(control, node, result);
    }

    // 根入口的唯一固化查询；适用判断及恢复事项数据都来自外部模块。
    private CompletableFuture<Outcome> executeEntry(Control control, String run, String parent, String goal,
                                                     Map<String, Object> args, int depth, ModelAdapter.Binding branch,
                                                     RecoverySnapshot snapshot) {
        return async(control, run, parent, NodeKind.SEARCH, goal, 0,
                Map.of("phase", CapabilityLookup.SearchPhase.ENTRY.name(), "enabled", lookup.enabled()),
                new Supplier<CapabilityLookup.Match>() {
                    @Override
                    public CapabilityLookup.Match get() {
                        return lookup.searchAtEntry(new CapabilityLookup.Query(run, 0, CapabilityLookup.ENTRY_QUERY_ID, goal, args),
                                control.records.context(run, snapshot));
                    }
                }).thenCompose(new Function<CapabilityLookup.Match, CompletionStage<Outcome>>() {
                    @Override
                    public CompletionStage<Outcome> apply(CapabilityLookup.Match match) {
                        if (match.selected().isEmpty()) {
                            return runDecisionRound(control, run, parent, goal, args, depth, 0,
                                    match.candidates(), List.of(), branch);
                        }
                        CapabilityLookup.Definition definition = match.selected().get();
                        return capability(control, run, parent, goal, args, definition, depth, 0, branch)
                                .thenCompose(new Function<Outcome, CompletionStage<Outcome>>() {
                                    @Override
                                    public CompletionStage<Outcome> apply(Outcome outcome) {
                                        if (outcome.status() != Status.SUCCESS) {
                                            // 入口失败也带实际执行反馈求解；未知效果需外部提供专门处理事项。
                                            return continueAfterExecution(control, run, parent, goal, args, depth, 0,
                                                    match.candidates(), List.of(new Feedback(0, definition.plan(), outcome)), outcome, branch);
                                        }
                                        // 成功纯工具路径可直接结束；外部若发现问题则先交模型判断。
                                        return preserveResult(queryRecovery(control, run, parent, goal, 0)
                                                .thenCompose(new Function<RecoverySnapshot, CompletionStage<Outcome>>() {
                                                    @Override
                                                    public CompletionStage<Outcome> apply(RecoverySnapshot latest) {
                                                        if (latest.disposition() == Disposition.CONTINUE) {
                                                            if (depth == 0) {
                                                                control.completionRevision.set(latest.revision());
                                                            }
                                                            return CompletableFuture.completedFuture(outcome);
                                                        }
                                                        return runDecisionRound(control, run, parent, goal, args, depth, 0,
                                                                match.candidates(), List.of(new Feedback(0, definition.plan(), outcome)), branch);
                                                    }
                                                }), outcome);
                                    }
                                });
                    }
                });
    }

    // 查询不创建本地待办；未装配记录时明确只有本次运行能力。
    private CompletableFuture<RecoverySnapshot> queryRecovery(Control control, String run, String parent,
                                                               String goal, long revision) {
        if (!control.records.enabled()) {
            return CompletableFuture.completedFuture(RecoverySnapshot.empty());
        }
        return async(control, run, parent, NodeKind.RECORD_QUERY, goal, revision,
                Map.of("taskId", control.records.taskId()), new Supplier<RecoverySnapshot>() {
                    @Override
                    public RecoverySnapshot get() {
                        return control.records.query(run);
                    }
                });
    }

    /**
     * 执行一轮模型决策；原名 loop。它不是 Java 循环关键字，也不负责猜测用户意图。
     * run 为当前任务身份，parent 为链路父节点，goal/args 为目标和输入；
     * depth 限制递归层数，round 是从 0 开始的决策轮数，history 是已完成计划的反馈。
     */
    private CompletableFuture<Outcome> runDecisionRound(Control control, String run, String parent, String goal,
                                                        Map<String, Object> args, int depth, int round, List<CapabilityLookup.Candidate> candidates,
                                                        List<Feedback> history, ModelAdapter.Binding branchContext) {
        // 先限制模型轮数，防止模型一直提交新计划而不结束。
        if (round >= limits.maxRounds()) {
            // 返回已完成的失败 Future，不再占用线程调用模型。
            Object previousValue = history.isEmpty() ? null : history.getLast().outcome().value();
            return CompletableFuture.completedFuture(new Outcome(Status.FAILED, previousValue, LlmTexts.PLANNING_ROUND_LIMIT_REACHED));
        }
        return preserveResult(queryRecovery(control, run, parent, goal, round + 1L)
                .thenCompose(new Function<RecoverySnapshot, CompletionStage<Outcome>>() {
                    @Override
                    public CompletionStage<Outcome> apply(RecoverySnapshot snapshot) {
                        if (snapshot.disposition() == Disposition.WAIT) {
                            return CompletableFuture.completedFuture(waiting(control, run, parent, branchContext.id(), round + 1L, snapshot));
                        }
                        return invokeDecision(control, run, parent, goal, args, depth, round,
                                candidates, history, branchContext, snapshot);
                    }
                }), history.isEmpty() ? null : history.getLast().outcome());
    }

    // 一次模型请求使用外部快照；恢复方案由模型提出，循环只分发返回的决策。
    private CompletableFuture<Outcome> invokeDecision(Control control, String run, String parent, String goal,
                                                       Map<String, Object> args, int depth, int round,
                                                       List<CapabilityLookup.Candidate> candidates, List<Feedback> history,
                                                       ModelAdapter.Binding branchContext, RecoverySnapshot snapshot) {
        PlanningPhase phase = snapshot.disposition() == Disposition.CONSULT_MODEL ? PlanningPhase.RECOVERY : PlanningPhase.NORMAL;
        long revision = round + 1L;
        ModelAdapter.Binding branch;
        String callId;
        CompletableFuture<Decision> response;
        // 区分真正进入模型与预算、装配、调度失败，后者不登记为模型不可用。
        AtomicBoolean modelInvoked = new AtomicBoolean();
        // 返回数据落记录失败属于记录故障，不能据此给模型记一次能力失败。
        AtomicBoolean modelReturned = new AtomicBoolean();
        // 单调时钟只测本次规划调用，不把工作池排队及记录提交算进模型响应时间。
        AtomicLong modelElapsedNanos = new AtomicLong();
        try {
            // 先检查停止信号，再按逻辑分支读取模型绑定，不依赖工作线程身份。
            control.check();
            branch = control.modelAccess.forCall(branchContext);
            // 模型与任务在取得绑定时关联；每次调用有独立身份，不用 modelRef 覆盖并发任务。
            Map<String, Object> assignment = modelTaskFacts(control, branch, goal, args, phase, revision);
            callId = node(control, run, parent, NodeKind.MODEL, goal, revision, assignment);
            // 先向外部记录模块交付事实，再进入工作队列；循环不保存另一份能力画像表。
            recordModelAssignment(control, run, callId, branch, revision, assignment);
            response = asyncNode(control, callId, new Supplier<Decision>() {
                @Override
                public Decision get() {
                    // 所有递归子任务共用根任务的模型调用预算。
                    if (control.models.incrementAndGet() > limits.maxModelCalls()) {
                        // 达到预算后拒绝调用，不在这里偷偷换模型或重试。
                        throw new IllegalStateException(LlmTexts.MODEL_CALL_LIMIT_REACHED);
                    }
                    Map<String, List<PlanningReference>> references = planningToolReliability(control);
                    control.check();
                    Map<String, Object> started = new LinkedHashMap<>(assignment);
                    started.put("startedAt", Instant.now().toString());
                    control.records.emit(run, callId, branch.id(), revision, EventKind.MODEL_CALL_STARTED, started);
                    // 记录确认期间可能收到取消；没有实际进入规划器时不产生模型失败事件。
                    control.check();
                    // 单次模型调用的输入：本任务目标、参数、允许工具、候选能力、历次实际结果。
                    long startedNanos = System.nanoTime();
                    modelInvoked.set(true);
                    Decision decision;
                    try {
                        decision = Objects.requireNonNull(planner.decide(new PlanningContext(run, goal, args, branch.modelRef(),
                                // 把根工具权限、入口候选和本任务反馈交给模型。
                                control.request.toolNames(), candidates, history, control.records.taskId(), phase, snapshot,
                                control.records.instructions(), references), control.records.logContext(run, callId, branch.id(), revision)));
                    } finally {
                        // 先结束计时，再写返回记录和链路；失败样本也不混入存储耗时。
                        modelElapsedNanos.set(Math.max(0L, System.nanoTime() - startedNanos));
                    }
                    modelReturned.set(true);
                    Map<String, Object> returned = new LinkedHashMap<>(assignment);
                    returned.put("decisionKind", decision.kind().name());
                    if (decision.plan() != null) {
                        returned.put("stepCount", decision.plan().steps().size());
                    }
                    returned.put("elapsedNanos", modelElapsedNanos.get());
                    returned.put("completedAt", Instant.now().toString());
                    // 正常返回不是目标成功；后续工具结果及验收仍按各自的记录关联。
                    control.records.emit(run, callId, branch.id(), revision, EventKind.MODEL_CALL_RETURNED, returned);
                    return decision;
                }
            });
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(decisionFailure(control, history, failure));
        }
        // 只在这次模型请求失败时发 MODEL_FAILED，不把随后工具执行失败误当作网络故障。
        return response.handle(new BiFunction<Decision, Throwable, CompletionStage<Outcome>>() {
            @Override
            public CompletionStage<Outcome> apply(Decision decision, Throwable failure) {
                try {
                    // 模型思考期间可以接收新指令；迟到响应保存事实但不能安装旧计划。
                    control.check();
                } catch (RuntimeException stopped) {
                    return CompletableFuture.completedFuture(decisionFailure(control, history, stopped));
                }
                if (failure != null) {
                    if (!modelInvoked.get() || modelReturned.get() || unwrap(failure) instanceof TraceWriteFailure || control.stopped()) {
                        return CompletableFuture.completedFuture(decisionFailure(control, history, failure));
                    }
                    return CompletableFuture.completedFuture(modelFailed(control, run, callId, branch, revision, phase,
                            goal, modelElapsedNanos.get(), history, failure));
                }
                return switch (decision.kind()) {
                    // 模型缺少必要信息：直接把问题返回给调用方，暂停自动推进。
                    case NEEDS_INPUT -> CompletableFuture.completedFuture(
                            // 返回模型提出的补充问题，不把它当作已完成任务。
                            new Outcome(Status.NEEDS_INPUT, decision.output(), null));
                    // 模型认为任务完成：仍要经过验收，模型文字不能代替真实结果。
                    case COMPLETE -> completeDecision(control, run, parent, goal, args, depth, round,
                            candidates, history, branch, snapshot, decision.output());
                    // 模型提交计划：检索固化、执行计划，再按结果决定是否进入下一轮。
                    case PLAN ->
                            executeModelPlan(control, run, parent, goal, args, depth, round, candidates, history, decision.plan(), branch);
                };
            }
        }).thenCompose(new Function<CompletionStage<Outcome>, CompletionStage<Outcome>>() {
            @Override
            public CompletionStage<Outcome> apply(CompletionStage<Outcome> result) {
                return result;
            }
        });
    }

    // 分配事实写入发生在排队前；若接收方失败，要结束已创建的 MODEL 节点并停止派发。
    private void recordModelAssignment(Control control, String run, String callId, ModelAdapter.Binding branch,
                                       long revision, Map<String, Object> assignment) {
        try {
            control.records.emit(run, callId, branch.id(), revision, EventKind.MODEL_TASK_ASSIGNED, assignment);
        } catch (RuntimeException failure) {
            try {
                traces.finish(control.root, callId, NodeStatus.FAILED, null, message(failure));
            } catch (RuntimeException traceFailure) {
                failure.addSuppressed(traceFailure);
            }
            throw failure;
        }
    }

    // 原始任务语义交给分类/画像模块；本类不从供应商名、工具名或字符串规则猜任务标签。
    private Map<String, List<PlanningReference>> planningToolReliability(Control control) {
        Map<String, List<PlanningReference>> references = new LinkedHashMap<>();
        for (String name : new LinkedHashSet<>(control.request.toolNames())) {
            control.check();
            try {
                ToolRef ref = actions.resolveTool(name).orElse(null);
                if (ref != null && name.equals(ref.toolName())) {
                    List<PlanningReference> ranges = toolReliability.planningReferences(ref);
                    if (!ranges.isEmpty()) {
                        references.put(name, ranges);
                    }
                }
            } catch (RuntimeException unavailable) {
                // 参考数据读取失败时保留本轮的工具合同，按可靠度未知继续规划。
            }
        }
        return references;
    }

    private static Map<String, Object> modelTaskFacts(Control control, ModelAdapter.Binding branch, String goal,
                                                     Map<String, Object> args, PlanningPhase phase, long revision) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("goal", goal);
        facts.put("arguments", args);
        // 这是允许使用的工具，不表示模型实际成功调用过这些工具。
        facts.put("toolNames", control.request.toolNames());
        facts.put("modelRef", branch.modelRef());
        facts.put("branchId", branch.id());
        facts.put("parentBranchId", branch.parentId() == null ? "" : branch.parentId());
        facts.put("modelAssignment", branch.assignment().name());
        facts.put("phase", phase.name());
        facts.put("round", revision);
        facts.put("instructionRevision", control.records.instructions().revision());
        return TaskPlan.immutableMap(facts);
    }

    // 模型故障只报告事实；等待事项及其分类由外部记录模块维护。
    private Outcome modelFailed(Control control, String run, String callId, ModelAdapter.Binding branch,
                                long revision, PlanningPhase phase, String goal, long elapsedNanos,
                                List<Feedback> history, Throwable failure) {
        Object previousValue = history.isEmpty() ? null : history.getLast().outcome().value();
        Outcome waiting = new Outcome(Status.WAITING, previousValue, LlmTexts.MODEL_WAITING_PREFIX + message(failure));
        try {
            control.records.emit(run, callId, branch.id(), revision, EventKind.MODEL_FAILED,
                    Map.of("error", message(failure), "phase", phase.name(), "goal", goal,
                            "modelRef", branch.modelRef(), "elapsedNanos", elapsedNanos,
                            "completedAt", Instant.now().toString()));
            return control.records.enabled() ? waiting : decisionFailure(control, history, failure);
        } catch (RuntimeException recordFailure) {
            return uncertain(waiting, message(recordFailure));
        }
    }

    // 停止、预算和基础设施错误保留已有产物，不伪造 MODEL_FAILED 事件。
    private static Outcome decisionFailure(Control control, List<Feedback> history, Throwable failure) {
        Outcome result = failed(failure);
        Object previousValue = history.isEmpty() ? result.value() : history.getLast().outcome().value();
        if (control.stopped()) {
            return new Outcome(Status.CANCELLED, previousValue,
                    control.stop.get() == null ? LlmTexts.TASK_TIMEOUT : control.stop.get());
        }
        return new Outcome(result.status(), previousValue, result.error());
    }

    private static Outcome waiting(Control control, String run, String node, String branch, long revision,
                                   RecoverySnapshot snapshot) {
        control.records.progress(control.records.logContext(run, node, branch, revision), Kind.WAITING,
                "等待恢复条件或外部确认");
        return new Outcome(Status.WAITING, RunRecordAccess.snapshotData(snapshot),
                snapshot.reason() == null ? LlmTexts.RECOVERY_WAITING : snapshot.reason());
    }

    // 恢复 COMPLETE 交外部审核，普通 COMPLETE 交验收；循环不自行关闭问题。
    private CompletableFuture<Outcome> completeDecision(Control control, String run, String parent, String goal,
                                                         Map<String, Object> args, int depth, int round,
                                                         List<CapabilityLookup.Candidate> candidates, List<Feedback> history,
                                                         ModelAdapter.Binding branch, RecoverySnapshot snapshot, Object output) {
        long revision = round + 1L;
        if (snapshot.disposition() == Disposition.CONSULT_MODEL) {
            return async(control, run, parent, NodeKind.RECOVERY_REVIEW, goal, revision,
                    Map.of("snapshotRevision", snapshot.revision()), new Supplier<RecoverySnapshot>() {
                        @Override
                        public RecoverySnapshot get() {
                            return control.records.review(run, snapshot, output);
                        }
                    }).thenCompose(new Function<RecoverySnapshot, CompletionStage<Outcome>>() {
                        @Override
                        public CompletionStage<Outcome> apply(RecoverySnapshot reviewed) {
                            if (reviewed.disposition() == Disposition.WAIT) {
                                return CompletableFuture.completedFuture(waiting(control, run, parent, branch.id(), revision, reviewed));
                            }
                            return runDecisionRound(control, run, parent, goal, args, depth, round + 1, candidates, history, branch);
                        }
                    });
        }
        return async(control, run, parent, NodeKind.VERIFY, goal, revision, args, new Supplier<Outcome>() {
            @Override
            public Outcome get() {
                return checkedRecorded(control, run, parent, branch.id(), revision, goal, args,
                        new Outcome(Status.SUCCESS, output, null));
            }
        }).thenCompose(new Function<Outcome, CompletionStage<Outcome>>() {
            @Override
            public CompletionStage<Outcome> apply(Outcome outcome) {
                if (outcome.status() == Status.SUCCESS) {
                    // 模型思考期间外部状态可能变化，结束前再读取权威接续结论。
                    return preserveResult(queryRecovery(control, run, parent, goal, revision)
                            .thenCompose(new Function<RecoverySnapshot, CompletionStage<Outcome>>() {
                                @Override
                                public CompletionStage<Outcome> apply(RecoverySnapshot latest) {
                                    if (latest.disposition() == Disposition.CONTINUE) {
                                        if (depth == 0) {
                                            control.completionRevision.set(latest.revision());
                                        }
                                        return CompletableFuture.completedFuture(outcome);
                                    }
                                    return runDecisionRound(control, run, parent, goal, args, depth, round + 1,
                                            candidates, history, branch);
                                }
                            }), outcome);
                }
                List<Feedback> next = new ArrayList<>(history);
                // 最终验收没有新计划，保留空 plan 及实际结果，避免伪造工具执行。
                next.add(new Feedback(revision, null, outcome));
                return continueAfterExecution(control, run, parent, goal, args, depth, round + 1,
                        candidates, List.copyOf(next), outcome, branch);
            }
        });
    }

    /**
     * 只处理 PLAN 分支；与“完成”和“需要信息”分开，避免一个方法混入全部流程。
     */
    private CompletableFuture<Outcome> executeModelPlan(Control control, String run, String parent, String goal,
                                                        Map<String, Object> args, int depth, int round, List<CapabilityLookup.Candidate> candidates,
                                                        List<Feedback> history, TaskPlan plan, ModelAdapter.Binding branch) {
        // 使用当前模型轮次的版本，供查询、链路和反馈关联同一份计划。
        long revision = round + 1L;
        // 限制整份计划的步骤数，避免一次生成无限任务。
        if (plan.steps().size() > limits.maxSteps()) {
            // 拒绝整个计划，不能先执行前几步再发现计划过大。
            throw new IllegalArgumentException(LlmTexts.PLAN_STEP_LIMIT_REACHED);
        }
        // 整批检查工具名，保证模型不能借计划调用本任务未允许的工具。
        validateTools(plan, control.request.toolNames());
        // 这里只校验任务合同；实际固化替换完成后，需要模型的计划才分析模型分支归属。
        // 每次真实 PLAN 都按新目标重新查库；不是筛选上轮缓存或入口候选。
        return async(control, run, parent, NodeKind.SEARCH, plan.goal(), revision,
                // phase 是链路协议字段，枚举名标记入口或规划后检索。
                Map.of("phase", CapabilityLookup.SearchPhase.AFTER_PLAN.name(), "enabled", lookup.enabled()),
                // 按本次模型计划重新检索总目标和所有子目标，完成后读取新匹配。
                new Supplier<CapabilityLookup.PlanMatches>() {
                    @Override
                    public CapabilityLookup.PlanMatches get() {
                        RecoverySnapshot snapshot = control.records.query(run);
                        return lookup.searchAfterPlanning(run, revision, plan, args, control.records.context(run, snapshot));
                    }
                }).thenCompose(new Function<CapabilityLookup.PlanMatches, CompletionStage<Outcome>>() {
                    @Override
                    public CompletionStage<Outcome> apply(CapabilityLookup.PlanMatches found) {
                        control.check();
                        // 检索仍每次执行；本轮已拒绝的版本不能再次替换模型的新计划。
                        CapabilityLookup.PlanMatches matches = withoutRejectedCapabilities(control, found);
                        CapabilityLookup.Definition whole = matches.whole().selected().orElse(null);
                        // 整条替换由固化定义自己的执行计划校验模型路由。
                        if (whole == null && planNeedsModels(plan, matches)) {
                            PlanBranches.analyze(plan);
                        }
                        if (depth == 0 && !control.records.applyPlan(run, revision, planData(plan))) {
                            throw new RunInterrupted(Status.WAITING, LlmTexts.TASK_INSTRUCTION_CHANGED);
                        }
                        control.records.progress(control.records.logContext(run, parent, branch.id(), revision),
                                Kind.PLAN_APPLIED, "采用计划 steps=" + plan.steps().size());
                        // 整条命中则运行固化定义；否则执行原计划，并逐步骤复用命中的子能力。
                        CompletableFuture<Outcome> execution;
                        if (whole != null) {
                            // 整条计划已匹配：直接执行固定能力定义。
                            execution = capability(control, run, parent, plan.goal(), args, whole, depth, revision, branch);
                        } else {
                            // 否则执行原计划，并在各步骤使用已选子能力。
                            execution = executePlan(control, run, parent, plan, args, matches, depth, revision, branch, null);
                        }
                        // 等待当前计划的所有步骤返回后，再让所属任务继续模型决策。
                        return execution.thenCompose(new Function<Outcome, CompletionStage<Outcome>>() {
                            @Override
                            public CompletionStage<Outcome> apply(Outcome outcome) {
                                List<Feedback> next = new ArrayList<>(history);
                                next.add(new Feedback(revision, plan, outcome));
                                return continueAfterExecution(control, run, parent, goal, args, depth, round + 1,
                                        candidates, List.copyOf(next), outcome, branch);
                            }
                        });
                    }
                });
    }

    // 已知结果可继续规划；未知效果仅在外部要求专门核对时再请求模型，不能盲目重跑。
    private CompletableFuture<Outcome> continueAfterExecution(Control control, String run, String parent, String goal,
                                                              Map<String, Object> args, int depth, int nextRound,
                                                              List<CapabilityLookup.Candidate> candidates, List<Feedback> history,
                                                              Outcome outcome, ModelAdapter.Binding branch) {
        return preserveResult(switch (outcome.status()) {
            case CANCELLED, NEEDS_INPUT, WAITING -> CompletableFuture.completedFuture(outcome);
            case SUCCESS, FAILED -> runDecisionRound(control, run, parent, goal, args, depth, nextRound, candidates, history, branch);
            case UNKNOWN -> queryRecovery(control, run, parent, goal, nextRound)
                    .thenCompose(new Function<RecoverySnapshot, CompletionStage<Outcome>>() {
                        @Override
                        public CompletionStage<Outcome> apply(RecoverySnapshot snapshot) {
                            if (snapshot.disposition() == Disposition.CONSULT_MODEL) {
                                return runDecisionRound(control, run, parent, goal, args, depth, nextRound, candidates, history, branch);
                            }
                            if (snapshot.disposition() == Disposition.WAIT) {
                                return CompletableFuture.completedFuture(waiting(control, run, parent, branch.id(), nextRound, snapshot));
                            }
                            return CompletableFuture.completedFuture(outcome);
                        }
                    });
        }, outcome);
    }

    // 运行已固定版本的能力定义；仍受本次门禁、验收和预算限制。
    private CompletableFuture<Outcome> capability(Control control, String run, String parent, String goal,
                                                  Map<String, Object> args, CapabilityLookup.Definition definition, int depth, long revision,
                                                  ModelAdapter.Binding branch) {
        // 本根运行已交回模型的版本不再自动复用；新运行会重新查询评分。
        if (control.modelRequiredCapabilities.contains(definition.ref())) {
            return CompletableFuture.completedFuture(new Outcome(Status.FAILED, null,
                    LlmTexts.TOOL_RELIABILITY_REPLAN));
        }
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
        CompletableFuture<Outcome> result = async(control, run, unit, NodeKind.GATE, goal, revision, args, new Supplier<CapabilityUsePort.UseDecision>() {
            @Override
            public CapabilityUsePort.UseDecision get() {
                // 固化定义也不能使用本任务未允许的工具。
                validateTools(definition.plan(), control.request.toolNames());
                // 固化展开与模型计划使用同一份步骤数量上限。
                if (definition.plan().steps().size() > limits.maxSteps()) {
                    // 抛出参数或协议错误，阻止无效输入继续进入执行。
                    throw new IllegalArgumentException(LlmTexts.CAPABILITY_STEP_LIMIT_REACHED);
                }
                // 循环传递数据，具体资格和适用判断由独立模块给出。
                RecoverySnapshot snapshot = control.records.query(run);
                return capabilityUse.evaluate(new CapabilityUsePort.UseRequest(control.records.taskId(), control.root,
                        run, goal, args, definition.ref(), control.records.context(run, snapshot)));
            }
        // 资格检查完成后才决定是否启动能力中的动作。
        }).thenCompose(new Function<CapabilityUsePort.UseDecision, CompletionStage<Outcome>>() {
            @Override
            public CompletionStage<Outcome> apply(CapabilityUsePort.UseDecision allowed) {
                // 未通过资格或门禁时，不进入实际执行。
                if (!allowed.usable()) {
                    // 返回已完成的失败结果，明确本分支没有继续执行动作。
                    return CompletableFuture.completedFuture(new Outcome(Status.FAILED, null,
                            allowed.reason() == null ? LlmTexts.CAPABILITY_NOT_ALLOWED : allowed.reason()));
                }
                // 固化定义不是新的 LLM 规划，不在此处再次检索数据库候选。
                return executePlan(control, run, unit, definition.plan(), args, null, depth + 1, revision, branch, definition.ref())
                        // 能力各步骤执行完成后，对组合结果做验收。
                        .thenCompose(new Function<Outcome, CompletionStage<Outcome>>() {
                            @Override
                            public CompletionStage<Outcome> apply(Outcome outcome) {
                                return preserveResult(async(control, run, unit, NodeKind.VERIFY, goal, revision, args, new Supplier<Outcome>() {
                                    @Override
                                    public Outcome get() {
                                        return checkedRecorded(control, run, unit, branch.id(), revision, goal, args, outcome);
                                    }
                                }), outcome);
                            }
                        });
            }
        });
        // 把组合结果写回该能力节点，并返回给上层步骤。
        return finish(control, unit, result);
    }

    // 创建有限 DAG 并按依赖调度；只等待各节点的 Future，不同步阻塞父线程。
    private CompletableFuture<Outcome> executePlan(Control control, String run, String parent, TaskPlan plan,
                                                   Map<String, Object> input, CapabilityLookup.PlanMatches matches, int depth, long revision,
                                                   ModelAdapter.Binding branch, CapabilityLookup.Ref directCapability) {
        // 分析本计划的分叉/汇合；不同计划作用域不会共享可变的分支映射。
        PlanModelBindings modelBindings = new PlanModelBindings(plan, branch, control.modelAccess,
                routingTasksByStep(plan, matches, control.request.toolNames()));
        // 建立逻辑步骤 ID 到本次实际执行节点 ID 的映射。
        Map<String, String> nodes = new LinkedHashMap<>();
        // 逐一处理计划步骤；数组顺序本身不代表执行依赖。
        for (TaskPlan.Step step : plan.steps()) {
            // 先为每一步建立节点，使后续关系都能找到端点。
            nodes.put(step.id(), node(control, run, parent, NodeKind.STEP, step.goal(), revision,
                    // 保存逻辑步骤、常量参数及参数来源，供固化模块追溯。
                    Map.of("stepId", step.id(), "arguments", step.arguments(), "bindingSources", bindingDetails(step),
                            "branchId", modelBindings.branchId(step.id()), "parentBranchId", modelBindings.parentBranchId(step.id()))));
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
                                return executeReadyStep(control, run, nodes, step, input, predecessors, matches, depth, revision, modelBindings, directCapability);
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
        }).handle(new BiFunction<Outcome, Throwable, Outcome>() {
            @Override
            public Outcome apply(Outcome outcome, Throwable failure) {
                // 所有真实步骤返回后才释放本计划的模型绑定；对外取消不提前归还。
                Outcome completed = failure == null ? outcome : failed(failure);
                try {
                    modelBindings.close();
                    return completed;
                } catch (RuntimeException cleanupFailure) {
                    // 已执行输出保留；绑定回收状态不明时停止重规划，等待核对。
                    String originalError = completed.error() == null ? "" : completed.error() + " | ";
                    Outcome uncertain = new Outcome(Status.UNKNOWN, completed.value(),
                            originalError + LlmTexts.MODEL_ADAPTER_RELEASE_FAILED_PREFIX + cleanupFailure.getMessage());
                    // 独立记录资源异常，不把它计为一次 LLM 请求或工具调用。
                    try {
                        String cleanupNode = node(control, run, parent, NodeKind.MODEL_SESSION,
                                LlmTexts.MODEL_ADAPTER_RELEASE_FAILED_PREFIX, revision,
                                Map.of("phase", "release", "branchId", branch.id()));
                        traces.finish(control.root, cleanupNode, NodeStatus.UNKNOWN, uncertain.value(), uncertain.error());
                    } catch (RuntimeException traceFailure) {
                        return uncertain(uncertain, message(traceFailure));
                    }
                    return uncertain;
                }
            }
        });
    }

    // 纯工具或纯工具固化不依赖模型适配器，避免未装配模型打断直接执行路径。
    private static boolean planNeedsModels(TaskPlan plan, CapabilityLookup.PlanMatches matches) {
        for (TaskPlan.Step step : plan.steps()) {
            for (TaskPlan.Step effectiveStep : effectiveSteps(step, matches)) {
                if (effectiveStep.kind() == TaskPlan.Kind.TASK) {
                    return true;
                }
            }
        }
        // 所有真实步骤均可直接执行，只记录逻辑分支，不申请外部模型绑定。
        return false;
    }

    // 固化替换后，以真正留给模型求解的目标准备分支选型输入。
    private static Map<String, List<ModelAdapter.RoutingTask>> routingTasksByStep(
            TaskPlan plan, CapabilityLookup.PlanMatches matches, List<String> allowedTools) {
        Map<String, List<ModelAdapter.RoutingTask>> tasksByStep = new LinkedHashMap<>();
        for (TaskPlan.Step step : plan.steps()) {
            List<ModelAdapter.RoutingTask> tasks = new ArrayList<>();
            for (TaskPlan.Step effectiveStep : effectiveSteps(step, matches)) {
                if (effectiveStep.kind() == TaskPlan.Kind.TASK) {
                    tasks.add(new ModelAdapter.RoutingTask(effectiveStep.goal(), allowedTools));
                }
            }
            if (!tasks.isEmpty()) {
                tasksByStep.put(step.id(), List.copyOf(tasks));
            }
        }
        return tasksByStep;
    }

    // 固化内部执行固定步骤，不再使用外层步骤匹配表。
    private static List<TaskPlan.Step> effectiveSteps(TaskPlan.Step step, CapabilityLookup.PlanMatches matches) {
        CapabilityLookup.Definition selected = selectedCapability(matches, step.id());
        return selected == null ? List.of(step) : selected.plan().steps();
    }

    // 选型输入与实际派发读取同一份已确定的步骤匹配。
    private static CapabilityLookup.Definition selectedCapability(CapabilityLookup.PlanMatches matches, String stepId) {
        return matches == null ? null : matches.steps().get(stepId).selected().orElse(null);
    }

    // 仅在前驱 Future 全部结束后调用：检查依赖、绑定参数，再分派能力、子任务或工具。
    private CompletableFuture<Outcome> executeReadyStep(Control control, String run, Map<String, String> nodes,
                                                         TaskPlan.Step step, Map<String, Object> input,
                                                         Map<String, CompletableFuture<Outcome>> predecessors,
                                                         CapabilityLookup.PlanMatches matches, int depth, long revision,
                                                         PlanModelBindings modelBindings, CapabilityLookup.Ref directCapability) {
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
        // 依赖已经结束：局部汇合恢复父模型，首次分叉则一次分配全部子绑定。
        ModelAdapter.Binding branch = modelBindings.ready(step.id());
        // 读取本计划缓存中该步骤命中的固化；固化展开内部没有新的匹配表。
        CapabilityLookup.Definition selected = selectedCapability(matches, step.id());
        // 优先执行已匹配能力，跳过该步骤原本需要的额外求解。
        if (selected != null) {
            // 步骤命中固化后直接执行能力，避免额外模型调用。
            return capability(control, run, nodes.get(step.id()), step.goal(), args, selected, depth, revision, branch);
        }
        // 这里才区分“已有工具”和“需要模型求解的子任务”。
        return switch (step.kind()) {
            // 子任务递归进入任务入口，但不重复根入口固化检索。
            case TASK -> task(control, nodes.get(step.id()), step.goal(), args, depth + 1, false, branch);
            // 模型明确规划的工具保留原执行路径；固化工具先查询可靠度。
            case TOOL -> directCapability == null
                    ? executeToolStep(control, run, nodes.get(step.id()), step, args, revision, branch)
                    : executeReliableTool(control, run, nodes.get(step.id()), step, args, revision, branch, directCapability);
        };
    }

    // 已查询到的候选仍可作为参考，只取消当前运行已拒绝版本的自动选中。
    private CapabilityLookup.PlanMatches withoutRejectedCapabilities(Control control, CapabilityLookup.PlanMatches found) {
        Map<String, CapabilityLookup.Match> steps = new LinkedHashMap<>();
        for (Map.Entry<String, CapabilityLookup.Match> entry : found.steps().entrySet()) {
            steps.put(entry.getKey(), withoutRejectedCapability(control, entry.getValue()));
        }
        return new CapabilityLookup.PlanMatches(withoutRejectedCapability(control, found.whole()), Map.copyOf(steps));
    }

    private CapabilityLookup.Match withoutRejectedCapability(Control control, CapabilityLookup.Match match) {
        if (match.selected().isPresent()
                && control.modelRequiredCapabilities.contains(match.selected().get().ref())) {
            return new CapabilityLookup.Match(match.candidates(), java.util.Optional.empty());
        }
        return match;
    }

    // 参数已绑定，数据库查询异步运行；评分未通过时不会产生工具执行意图或调用计数。
    private CompletableFuture<Outcome> executeReliableTool(Control control, String run, String parent,
                                                           TaskPlan.Step step, Map<String, Object> args, long revision,
                                                           ModelAdapter.Binding branch, CapabilityLookup.Ref ref) {
        ToolRef toolBinding = actions.resolveTool(step.toolName()).orElse(null);
        return async(control, run, parent, NodeKind.GATE, step.goal(), revision,
                Map.of("phase", "TOOL_RELIABILITY", "tool", step.toolName(), "arguments", args,
                        "capabilityId", ref.id(), "capabilityRevision", ref.revision()),
                new Supplier<ToolReliabilityGate.Decision>() {
                    @Override
                    public ToolReliabilityGate.Decision get() {
                        ToolReliabilityGate.Decision decision = toolBinding == null
                                ? toolReliability.evaluateUnavailable() : toolReliability.evaluate(toolBinding, args);
                        if (!decision.direct()) {
                            control.modelRequiredCapabilities.add(ref);
                        }
                        // 这是派发前判断事实，不能把拒绝计成工具已经执行失败。
                        control.records.emit(run, parent, branch.id(), revision, EventKind.OBSERVED,
                                Map.of("phase", "TOOL_RELIABILITY", "tool", step.toolName(), "arguments", args,
                                        "namespace", ref.namespace(), "capabilityId", ref.id(),
                                        "capabilityRevision", ref.revision(), "decision", decision.asMap()));
                        return decision;
                    }
                }).thenCompose(new Function<ToolReliabilityGate.Decision, CompletionStage<Outcome>>() {
                    @Override
                    public CompletionStage<Outcome> apply(ToolReliabilityGate.Decision decision) {
                        control.check();
                        if (decision.direct()) {
                            // 高分仅省去模型；原有工具门禁、记录、预算与验收继续执行。
                            return executeToolStep(control, run, parent, step, args, revision, branch, toolBinding);
                        }
                        // 原有父循环收集所有实际结果后交给模型，保留此前成功步骤且不盲目重放。
                        return CompletableFuture.completedFuture(new Outcome(Status.FAILED,
                                Map.of("executed", false, "tool", step.toolName(), "arguments", args,
                                        "reliability", decision.asMap()), decision.reason()));
                    }
                });
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

    // 未知与取消优先保留；等待外部条件高于普通失败，不能误触发自动重规划。
    private static Status mergeStatus(Status current, Status next) {
        return switch (next) {
            case UNKNOWN -> Status.UNKNOWN;
            case CANCELLED -> current == Status.UNKNOWN ? current : Status.CANCELLED;
            case WAITING -> current == Status.UNKNOWN || current == Status.CANCELLED ? current : Status.WAITING;
            case NEEDS_INPUT -> current == Status.UNKNOWN || current == Status.CANCELLED || current == Status.WAITING ? current : Status.NEEDS_INPUT;
            case FAILED -> current == Status.SUCCESS ? Status.FAILED : current;
            case SUCCESS -> current;
        };
    }

    /**
     * 执行一个普通工具步骤；参数已经由 bind 从常量和依赖结果中组装完毕。
     */
    private CompletableFuture<Outcome> executeToolStep(Control control, String run, String parent,
                                                       TaskPlan.Step step, Map<String, Object> args, long revision,
                                                       ModelAdapter.Binding branch) {
        return executeToolStep(control, run, parent, step, args, revision, branch, null);
    }

    private CompletableFuture<Outcome> executeToolStep(Control control, String run, String parent,
                                                       TaskPlan.Step step, Map<String, Object> args, long revision,
                                                       ModelAdapter.Binding branch, ToolRef toolBinding) {
        // 工具调用身份同时用于事件及外部回执，不再把父 STEP 身份当成实际 TOOL 身份。
        String operation = node(control, run, parent, NodeKind.TOOL, step.goal(), revision,
                Map.of("tool", step.toolName(), "arguments", args));
        return asyncNode(control, operation, new Supplier<Outcome>() {
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
                        Outcome executed = null;
                        boolean entered = false;
                        try {
                            // 外部确认已记录执行意图后才派发；失败不降级成无记录执行。
                            control.records.emit(run, operation, branch.id(), revision, EventKind.INTENT,
                                    Map.of("goal", step.goal(), "tool", step.toolName(), "arguments", args));
                            CallContext context = new CallContext(control.root, run, operation, control.deadline, control.stop,
                                    control.records.taskId(), new BiConsumer<String, Map<String, Object>>() {
                                        @Override
                                        public void accept(String phase, Map<String, Object> evidence) {
                                            control.records.emit(run, operation, branch.id(), revision, EventKind.OBSERVED,
                                                    Map.of("phase", phase, "evidence", evidence));
                                        }
                                    }, new Runnable() {
                                        @Override
                                        public void run() {
                                            control.check();
                                        }
                                    });
                            // 记录确认和排队期间可能有新指令；自定义执行器的入口也受版本检查。
                            context.check();
                            // 按工具名和 Map 参数执行，并注入根身份、任务身份、步骤及取消上下文。
                            entered = true;
                            executed = toolBinding == null ? actions.execute(step.toolName(), args, context)
                                    : actions.execute(toolBinding, args, context);
                            // 保存真实返回，再独立记录验收，避免“修改已完成但验收失败”被混为未执行。
                            control.records.emit(run, operation, branch.id(), revision, EventKind.RETURNED,
                                    Map.of("goal", step.goal(), "tool", step.toolName(), "arguments", args, "outcome", executed.asMap()));
                            // 对实际工具结果做业务验收，不把回调正常返回当成整个任务成功。
                            return checkedRecorded(control, run, operation, branch.id(), revision, step.goal(), args, executed);
                        } catch (RuntimeException failure) {
                            if (!entered && failure instanceof RunInterrupted) {
                                Outcome skipped = failed(failure);
                                // 意图已保存但尚未进入执行器，明确记录未派发，避免恢复误当在途效果。
                                control.records.emit(run, operation, branch.id(), revision, EventKind.RETURNED,
                                        Map.of("goal", step.goal(), "tool", step.toolName(), "arguments", args,
                                                "dispatchStarted", false, "outcome", skipped.asMap()));
                                return skipped;
                            }
                            // 自定义执行适配器也可能在副作用发生后抛错，不能转成可随意重试的普通失败。
                            Outcome uncertain = new Outcome(Status.UNKNOWN, executed == null ? null : executed.value(),
                                    LlmTexts.EXECUTION_UNKNOWN_PREFIX + message(failure));
                            if (entered && executed == null) {
                                try {
                                    control.records.emit(run, operation, branch.id(), revision, EventKind.RETURNED,
                                            Map.of("goal", step.goal(), "tool", step.toolName(), "arguments", args, "outcome", uncertain.asMap()));
                                } catch (RuntimeException recordFailure) {
                                    // 原执行异常与记录错误同时保留；已派发的效果不能改写成未发生。
                                    return new Outcome(Status.UNKNOWN, uncertain.value(), uncertain.error() + " | " + message(recordFailure));
                                }
                            }
                            return uncertain;
                        }
                    }
                });
    }

    // 统一提交耗时工作并记录链路；T 可以是决策、检索结果或执行结果。
    private <T> CompletableFuture<T> async(Control control, String run, String parent, NodeKind kind, String goal,
                                           long revision, Map<String, Object> input, Supplier<T> action) {
        // 为即将提交的动作建立本次调用身份。
        try {
            String id = node(control, run, parent, kind, goal, revision, input);
            return asyncNode(control, id, action);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    // 对已登记身份派发工作，工具可在提交前取得与记录一致的 operationId。
    private <T> CompletableFuture<T> asyncNode(Control control, String id, Supplier<T> action) {
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
                            : value instanceof ToolReliabilityGate.Decision reliability ? reliability.asMap()
                            : value instanceof Boolean flag ? flag : "完成";
                    // 正常返回记 COMPLETED；异常记 FAILED，这不同于业务 SUCCESS。
                    try {
                        traces.finish(control.root, id, failure == null ? NodeStatus.COMPLETED : NodeStatus.FAILED, recorded,
                                failure == null ? null : message(failure));
                    } catch (RuntimeException traceFailure) {
                        // Future 不能把写链路失败转成“工具未成功”；沿异常链保留真实执行结果。
                        throw new TraceWriteFailure(value instanceof Outcome outcome ? outcome : null, traceFailure);
                    }
                }
            });
        } catch (RuntimeException rejected) {
            // 工作池拒绝接收任务时，仍把该动作标记为失败。
            try {
                traces.finish(control.root, id, NodeStatus.FAILED, null, message(rejected));
            } catch (RuntimeException traceFailure) {
                rejected.addSuppressed(traceFailure);
            }
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
                try {
                    traces.finish(control.root, node, NodeStatus.valueOf(actual.status().name()), actual.value(), actual.error());
                } catch (RuntimeException traceFailure) {
                    return uncertain(actual, message(traceFailure));
                }
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
    private Outcome checked(String goal, Map<String, Object> args, Outcome outcome, Map<String, Object> instructions) {
        // 执行器不能返回 null，否则无法判定真实结果。
        Objects.requireNonNull(outcome, LlmTexts.EMPTY_EXECUTOR_OUTCOME);
        // 失败、取消、未知等结果不再当成功结果验收。
        if (outcome.status() != Status.SUCCESS) {
            // 原样保留已有状态，避免丢失副作用未知等重要信息。
            return outcome;
        }
        try {
            // 验收通过才保留成功；明确不通过则保存原结果并标记失败。
            return checks.verify(goal, args, outcome.value(), instructions) ? outcome : new Outcome(Status.FAILED, outcome.value(), LlmTexts.VERIFICATION_FAILED);
        } catch (RuntimeException failure) {
            // 动作可能已经成功，验收服务故障不能触发自动重做动作。
            return new Outcome(Status.UNKNOWN, outcome.value(), LlmTexts.VERIFICATION_UNKNOWN_PREFIX + message(failure));
        }
    }

    // 验收结果作为独立事件交给记录模块，由它决定进度和待处理事项变化。
    private Outcome checkedRecorded(Control control, String run, String operation, String branch, long revision,
                                    String goal, Map<String, Object> args, Outcome execution) {
        if (execution != null && execution.status() == Status.SUCCESS) {
            control.records.progress(control.records.logContext(run, operation, branch, revision), Kind.VERIFY_STARTED,
                    "开始验收结果");
        }
        Outcome result = checked(goal, args, execution, control.records.instructionData());
        try {
            control.records.emit(run, operation, branch, revision, EventKind.VERIFIED,
                    Map.of("goal", goal, "arguments", args, "performed", execution.status() == Status.SUCCESS,
                            "outcome", result.asMap()));
            return result;
        } catch (RuntimeException recordFailure) {
            return uncertain(result, message(recordFailure));
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
        if (unwrap(failure) instanceof RunInterrupted interrupted) {
            return new Outcome(interrupted.status, null, interrupted.getMessage());
        }
        if (unwrap(failure) instanceof TraceWriteFailure traceFailure) {
            return uncertain(traceFailure.outcome, message(traceFailure));
        }
        // 返回明确失败及原因，让所属任务统一决定后续处理。
        return new Outcome(Status.FAILED, null, message(failure));
    }

    // 记录、查询或验收故障不抹掉已经产生的输出，也不把未知影响改成可直接重试的 FAILED。
    private static Outcome uncertain(Outcome outcome, String error) {
        String previousError = outcome == null || outcome.error() == null ? "" : outcome.error() + " | ";
        return new Outcome(Status.UNKNOWN, outcome == null ? null : outcome.value(), previousError + error);
    }

    // 接续查询或审核失败时保留上一阶段的实际结果，不能让异常转换吞掉已发生的修改。
    private static CompletableFuture<Outcome> preserveResult(CompletableFuture<Outcome> work, Outcome previous) {
        return work.handle(new BiFunction<Outcome, Throwable, Outcome>() {
            @Override
            public Outcome apply(Outcome result, Throwable failure) {
                if (failure == null) {
                    return result;
                }
                Outcome converted = failed(failure);
                if (previous == null || converted.value() != null) {
                    return converted;
                }
                if (converted.status() == Status.WAITING || converted.status() == Status.CANCELLED) {
                    return new Outcome(converted.status(), previous.value(), converted.error());
                }
                return uncertain(previous, message(failure));
            }
        });
    }

    // 已执行结果跨过异步记录失败边界时，随异常一同带回父任务。
    private static final class TraceWriteFailure extends RuntimeException {
        /** 链路记录失败前已经取得的实际产物；模型、检索等非执行节点可为空。 */
        private final Outcome outcome;

        private TraceWriteFailure(Outcome outcome, RuntimeException failure) {
            super(failure.getMessage(), failure);
            this.outcome = outcome;
        }
    }

    // 提取真实异常原因，避免只看到异步包装异常。
    private static String message(Throwable failure) {
        failure = unwrap(failure);
        // 保留异常类型和消息，便于定位故障来源。
        return failure.getClass().getSimpleName() + ": " + failure.getMessage();
    }

    private static Throwable unwrap(Throwable failure) {
        // 解开 CompletionException 包装，保留最内层业务异常。
        while (failure.getCause() != null && failure instanceof java.util.concurrent.CompletionException) {
            // 沿着异步包装链读取原始异常。
            failure = failure.getCause();
        }
        return failure;
    }

    @FunctionalInterface
    // 单次模型决策端口；实现必须只做一次模型决策。
    public interface Planner {
        /** 规划协议明确声明所需能力，不根据普通业务工具数量推断。 */
        default Set<ModelAdapter.Requirement> requirements() {
            return Set.of();
        }

        // 输入本轮上下文，返回计划、完成或需要信息三者之一。
        Decision decide(PlanningContext context);

        /** 观测关联由编排器显式传递，不序列化到模型请求。 */
        default Decision decide(PlanningContext context, TaskLogPort.Context logContext) {
            return decide(context);
        }
    }

    @FunctionalInterface
    // 普通工具执行端口；不负责自然语言拆分和递归任务。
    public interface ActionExecutor {
        // 接收已绑定参数与执行上下文，返回实际执行结果。
        Outcome execute(String tool, Map<String, Object> args, CallContext context);

        /** 无可核验的执行绑定时，固化工具转回模型判断。 */
        default Optional<ToolRef> resolveTool(String name) {
            return Optional.empty();
        }

        /** 固化直接执行沿用打分时固定的工具绑定。 */
        default Outcome execute(ToolRef ref, Map<String, Object> args, CallContext context) {
            if (!resolveTool(ref.toolName()).equals(Optional.of(ref))) {
                return new Outcome(Status.FAILED, null, LlmTexts.TOOL_BINDING_CHANGED);
            }
            return execute(ref.toolName(), args, context);
        }
    }

    /** 兼容原有调用方；新装配直接实现独立的运行门禁。 */
    public interface Checks extends RuntimeGate {
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
        /** 外部记录保留事项，等待模型、审核或其他接续条件。 */
        WAITING,
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
            List<String> toolNames,
            /** 受信任应用入口标记内部验证；此来源会保留到恢复运行。 */
            boolean internalValidation) {
        public Request(String goal, Map<String, Object> arguments, String modelRef, List<String> toolNames) {
            this(goal, arguments, modelRef, toolNames, false);
        }

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
            List<Feedback> history,
            /** 稳定任务身份；恢复时不随本次 runId 改变。 */
            String taskId,
            /** 本轮请求用途，由外部查询结论决定，不推断如何回滚。 */
            PlanningPhase phase,
            /** 外部模块的最新问题和进度，模型据此提出处理方案。 */
            RecoverySnapshot recovery,
            /** 本运行固定采用的指令及其版本；历史实际进度来自 recovery。 */
            TaskInstructionPort.Snapshot instructions,
            /** 按允许工具名称附带的历史范围估计，缺项表示未知。 */
            Map<String, List<PlanningReference>> toolReliability) {
        public PlanningContext(String runId, String goal, Map<String, Object> arguments, String modelRef,
                               List<String> toolNames, List<CapabilityLookup.Candidate> candidates, List<Feedback> history,
                               String taskId, PlanningPhase phase, RecoverySnapshot recovery,
                               TaskInstructionPort.Snapshot instructions) {
            this(runId, goal, arguments, modelRef, toolNames, candidates, history, taskId, phase, recovery,
                    instructions, Map.of());
        }

        public PlanningContext(String runId, String goal, Map<String, Object> arguments, String modelRef,
                               List<String> toolNames, List<CapabilityLookup.Candidate> candidates, List<Feedback> history,
                               String taskId, PlanningPhase phase, RecoverySnapshot recovery) {
            this(runId, goal, arguments, modelRef, toolNames, candidates, history, taskId, phase, recovery,
                    TaskInstructionPort.Snapshot.empty());
        }
        // 保留原规划器检查和调用方式，显式表示没有接入恢复数据。
        public PlanningContext(String runId, String goal, Map<String, Object> arguments, String modelRef,
                               List<String> toolNames, List<CapabilityLookup.Candidate> candidates, List<Feedback> history) {
            this(runId, goal, arguments, modelRef, toolNames, candidates, history,
                    runId, PlanningPhase.NORMAL, RecoverySnapshot.empty());
        }

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
            TaskPlan.requireText(taskId, "taskId");
            Objects.requireNonNull(phase);
            Objects.requireNonNull(recovery);
            Objects.requireNonNull(instructions);
            Map<String, List<PlanningReference>> visibleReferences = new LinkedHashMap<>();
            for (String name : toolNames) {
                List<PlanningReference> ranges = toolReliability.get(name);
                if (ranges != null && !ranges.isEmpty()) {
                    visibleReferences.put(name, List.copyOf(ranges));
                }
            }
            toolReliability = Map.copyOf(visibleReferences);
        }
    }

    /** 模型用途只影响本轮上下文和结束提案，不直接决定业务回滚。 */
    public enum PlanningPhase {
        NORMAL,
        RECOVERY
    }

    public record Handle(
            /** 供执行图查询使用的根身份。 */
            String rootRunId,
            /** 对外结果，超时/取消可以早于实际工具结束。 */
            CompletableFuture<Outcome> result,
            /** 请求停止继续调度，不承诺撤销副作用。 */
            Runnable cancel,
            /** 外部登记的稳定任务身份，可交给 resume。未装配时仅有本次运行身份。 */
            String taskId,
            /** 真实工作及资源/记录收尾完成；收尾失败则异常完成，不能据此自动恢复。 */
            CompletableFuture<Outcome> settled) {
        public Handle(String rootRunId, CompletableFuture<Outcome> result, Runnable cancel, String taskId) {
            this(rootRunId, result, cancel, taskId, result);
        }
        public Handle(String rootRunId, CompletableFuture<Outcome> result, Runnable cancel) {
            this(rootRunId, result, cancel, rootRunId);
        }
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
            AtomicReference<String> stop,
            /** 稳定任务身份，便于工具回执关联恢复。 */
            String taskId,
            /** 工具可报告执行前后证据；真实状态采集由工具负责。 */
            BiConsumer<String, Map<String, Object>> evidenceReporter,
            /** 执行器拿锁或排队后再次核对指令版本；不负责存储或判断业务影响。 */
            Runnable dispatchCheck) {
        public CallContext(String rootRunId, String runId, String nodeId, Instant deadline, AtomicReference<String> stop,
                           String taskId, BiConsumer<String, Map<String, Object>> evidenceReporter) {
            this(rootRunId, runId, nodeId, deadline, stop, taskId, evidenceReporter, null);
        }
        // 保留原调用方式；没有记录接入时不伪造持久化保证。
        public CallContext(String rootRunId, String runId, String nodeId, Instant deadline, AtomicReference<String> stop) {
            this(rootRunId, runId, nodeId, deadline, stop, rootRunId, null);
        }

        /** 工具在实际变更前提交必要快照；返回表示外部已接受，异常时不应继续变更。 */
        public void reportBefore(Map<String, Object> evidence) {
            report("BEFORE", evidence);
        }

        /** 工具提交实际差异、版本、备份或外部回执，不由循环猜测。 */
        public void reportAfter(Map<String, Object> evidence) {
            report("AFTER", evidence);
        }

        /** 专供宿主执行器提交派发绑定，工具公开报告只使用 BEFORE 或 AFTER。 */
        void reportToolBinding(Map<String, Object> binding) {
            report("TOOL_BINDING", binding);
        }

        private void report(String phase, Map<String, Object> evidence) {
            if (evidenceReporter != null) {
                evidenceReporter.accept(phase, TaskPlan.immutableMap(evidence));
            }
        }
        // 允许执行器在资源等待或新动作开始前检查取消。
        public void check() {
            // 有停止原因或已超时都拒绝继续启动动作。
            if (stop.get() != null || Instant.now().isAfter(deadline)) {
                // 明确报告当前状态不允许继续；不在这里自动重试。
                throw new RunInterrupted(Status.CANCELLED, LlmTexts.TASK_STOPPED);
            }
            if (dispatchCheck != null) {
                dispatchCheck.run();
            }
        }
    }

    // 一棵运行树共享的控制数据；计数器及取消原因支持并发访问。
    private static final class Control {
        /** 仅本根运行的回退选择，防止拒绝的固化再次抢占模型方案；不修改发布状态。 */
        private final Set<CapabilityLookup.Ref> modelRequiredCapabilities = ConcurrentHashMap.newKeySet();
        /** 应用可等待真实收尾；取消的对外结果不会提前完成它。 */
        private final CompletableFuture<Outcome> settled = new CompletableFuture<>();
        /** 允许重复修订返回同一个已经启动的运行句柄。 */
        private final CompletableFuture<Handle> started = new CompletableFuture<>();
        /** 由外层同步 amend 方法管理，同一次旧运行只安装一个自动接续回调。 */
        private CompletableFuture<Handle> continuation;
        /** 根结果验收后的记录版本，最终提交仍由外部做原子检查。 */
        private final AtomicLong completionRevision = new AtomicLong();
        /** 指令引起的停止与旧取消/超时原因分开。 */
        private final AtomicReference<InstructionStop> instructionStop = new AtomicReference<>();
        /** 任一收尾失败阻止自动重启；对外结果仍保留真实产物。 */
        private Throwable cleanupFailure;
        /** 停止请求写入故障仍保留，不把本地停止伪装成持久记录成功。 */
        private final AtomicReference<RuntimeException> stopRecordFailure = new AtomicReference<>();
        /** 计时线程只登记本地停止请求，持久写入由真实收尾完成。 */
        private String pendingStopReason;
        /** 保存实际请求时间，不能将收尾落盘时间伪装为超时发生时刻。 */
        private Instant stopRequestedAt;
        /** 收尾已取得停止快照后，拒绝追加本次运行的本地停止请求。 */
        private boolean stopRequestsClosed;
        /**
         * 根任务身份。
         */
        private final String root;
        /**
         * 根输入及工具边界。
         */
        private final Request request;
        /** 本根运行的模型会话接线；实际绑定与分配状态由外部适配器持有。 */
        private final RunModelAccess modelAccess;
        /** 本次运行的外部记录接线，不保存另一份权威业务进度。 */
        private final RunRecordAccess records;
        /** 恢复从原任务及待办进入模型，不从入口固化重新执行。 */
        private final boolean resuming;
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
        private Control(String root, Request request, Instant deadline, ModelAdapter modelAdapter,
                        Set<ModelAdapter.Requirement> requirements, RunRecordAccess records, boolean resuming) {
            // 保存执行图的根身份。
            this.root = root;
            // 保存根请求的模型引用、参数与工具允许集合。
            this.request = request;
            this.records = records;
            this.resuming = resuming;
            // 这里只保存端口及根身份；入口检索和纯工具路径不打开外部模型会话。
            this.modelAccess = new RunModelAccess(modelAdapter, new ModelAdapter.RunRequest(
                    root, root, request.modelRef(), request.toolNames(), requirements));
            // 保存根任务统一截止时间。
            this.deadline = deadline;
        }

        // 复用与工具上下文相同的取消/超时判断。
        private void check() {
            InstructionStop latest = instructionStop.get();
            checkDirective(latest == null ? records.directive() : latest.directive());
        }

        // 结束状态以外部最新指令为准，派发路径仍可用本地停止信号直接拒绝。
        private void checkForSettlement() {
            TaskInstructionPort.Directive authoritative = records.directive();
            InstructionStop latest = instructionStop.get();
            if (authoritative == TaskInstructionPort.Directive.PROCEED && latest != null) {
                authoritative = latest.directive();
            }
            checkDirective(authoritative);
        }

        private void checkDirective(TaskInstructionPort.Directive directive) {
            switch (directive) {
                case PROCEED -> {
                    if (stop.get() != null || Instant.now().isAfter(deadline)) {
                        throw new RunInterrupted(Status.CANCELLED, LlmTexts.TASK_STOPPED);
                    }
                }
                case REPLAN -> throw new RunInterrupted(Status.WAITING, LlmTexts.TASK_INSTRUCTION_CHANGED);
                case PAUSE -> throw new RunInterrupted(Status.WAITING, LlmTexts.TASK_PAUSED);
                case CANCEL -> throw new RunInterrupted(Status.CANCELLED, LlmTexts.TASK_CANCELLED);
                case WAIT -> throw new RunInterrupted(Status.WAITING, LlmTexts.TASK_CONTROL_WAIT);
            }
        }

        private synchronized void requestInstructionStop(TaskInstructionPort.Receipt receipt) {
            InstructionStop latest = instructionStop.get();
            if (latest != null && receipt.revision() <= latest.revision()) {
                return;
            }
            TaskInstructionPort.Directive directive = switch (receipt.kind()) {
                case AMEND, CONTINUE -> TaskInstructionPort.Directive.REPLAN;
                case PAUSE -> TaskInstructionPort.Directive.PAUSE;
                case CANCEL -> TaskInstructionPort.Directive.CANCEL;
            };
            instructionStop.set(new InstructionStop(receipt.revision(), directive));
            records.progress(records.logContext(root, root, root, 0), Kind.STOP_REQUESTED,
                    "停止新派发，等待在途工作 directive=" + directive.name());
        }

        private synchronized Outcome requestLocalStop(String reason) {
            if (!stopRequestsClosed && stop.compareAndSet(null, reason)) {
                pendingStopReason = reason;
                stopRequestedAt = Instant.now();
                String stopKind = "CANCEL";
                if (LlmTexts.TASK_TIMEOUT.equals(reason)) {
                    stopKind = "TIMEOUT";
                } else if (LlmTexts.TASK_APPLICATION_STOPPING.equals(reason)) {
                    stopKind = "SHUTDOWN";
                }
                records.progress(records.logContext(root, root, root, 0), Kind.STOP_REQUESTED,
                        "停止新派发，等待在途工作 reason=" + stopKind);
            }
            return new Outcome(Status.CANCELLED, null, reason);
        }

        private void recordLocalStop() {
            String reason;
            Instant requestedAt;
            synchronized (this) {
                stopRequestsClosed = true;
                reason = pendingStopReason;
                requestedAt = stopRequestedAt;
            }
            if (reason == null) {
                return;
            }
            try {
                records.emit(root, root, root, 0, EventKind.RUN_INTERRUPTION_REQUESTED,
                        Map.of("reason", reason, "source", "RUNTIME", "requestedAt", requestedAt.toString()));
            } catch (RuntimeException failure) {
                stopRecordFailure.compareAndSet(null, failure);
            }
        }

        private boolean stopped() {
            return instructionStop.get() != null || stop.get() != null || Instant.now().isAfter(deadline);
        }
    }

    /** 本实例已观察到的最高版本及其停止指令。 */
    private record InstructionStop(
            /** 持久接收回执的版本。 */
            long revision,
            /** 当前运行收到该版本后采用的停止结论。 */
            TaskInstructionPort.Directive directive) {}

    /** 执行控制异常只结束旧调度，不冒充模型故障或外部副作用失败。 */
    static final class RunInterrupted extends RuntimeException {
        /** 取消与等待新指令接续分别返回。 */
        final Status status;

        private RunInterrupted(Status status, String reason) {
            super(reason);
            this.status = status;
        }
    }

    /** 最近指令句柄的定位键，不作为权威任务身份仓储。 */
    private record AmendmentKey(
            /** 稳定任务身份。 */
            String taskId,
            /** 输入幂等身份。 */
            String commandId) {}

    /** 缓存只保留原请求和对应的接续句柄。 */
    private record PendingAmendment(
            /** 用于拒绝同 ID 的不同内容。 */
            TaskInstructionPort.Command command,
            /** 同一命令已有的接续。 */
            CompletableFuture<Handle> continuation) {}

    // 保存结构化计划供外部指令应用记录关联，不把模型任意文本当作指令已执行。
    private static Map<String, Object> planData(TaskPlan plan) {
        List<Map<String, Object>> steps = new ArrayList<>();
        for (TaskPlan.Step step : plan.steps()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", step.id());
            data.put("goal", step.goal());
            data.put("kind", step.kind().name());
            data.put("toolName", step.toolName());
            data.put("arguments", step.arguments());
            data.put("dependsOn", step.dependsOn());
            Map<String, Object> bindings = new LinkedHashMap<>();
            for (Map.Entry<String, TaskPlan.InputBinding> entry : step.bindings().entrySet()) {
                bindings.put(entry.getKey(), Map.of("source", entry.getValue().source(), "pointer", entry.getValue().pointer()));
            }
            data.put("bindings", bindings);
            steps.add(TaskPlan.immutableMap(data));
        }
        return Map.of("goal", plan.goal(), "steps", steps);
    }
}
