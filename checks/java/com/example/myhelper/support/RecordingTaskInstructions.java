package com.example.myhelper.support;

import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.records.TaskInstructionPort;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.records.TaskRecordPort.RunClaim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.time.Instant;

/**
 * 指令合同的进程内检查替身，与 RecordingTaskRecords 共用同一监视器。
 * 本类不提供持久化、跨进程租约或远端取消保证，禁止装配到生产任务。
 */
public final class RecordingTaskInstructions implements TaskInstructionPort {
    /** 实际的任务、领取、事实和恢复快照检查来源。 */
    private final RecordingTaskRecords records;
    /** 各任务的用户输入和版本检查状态。 */
    private final Map<String, InstructionState> states = new LinkedHashMap<>();

    RecordingTaskInstructions(RecordingTaskRecords records) {
        this.records = Objects.requireNonNull(records);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Receipt accept(Command command) {
        Objects.requireNonNull(command);
        synchronized (records) {
            InstructionState state = state(command.taskId());
            Command existing = state.commands.get(command.commandId());
            if (existing != null) {
                if (!existing.equals(command)) {
                    throw new IllegalArgumentException("Command id has different content");
                }
                return state.receipts.get(command.commandId());
            }
            if (command.expectedRevision() != state.revision) {
                throw new IllegalStateException("Stale instruction revision");
            }
            long revision = state.revision + 1;
            Receipt receipt = new Receipt(command.taskId(), command.commandId(), revision, command.kind());
            RunClaim active = records.activeClaim(command.taskId());
            if (active != null) {
                // 同一监视器内先确认运行中断事实；记录拒绝时不能接受命令或递增版本。
                Map<String, Object> details = Map.of("commandId", command.commandId(),
                        "instructionRevision", revision, "kind", command.kind().name(),
                        "text", command.text(), "recordedAt", Instant.now().toString());
                TaskRecordPort.ExecutionEvent interruption = new TaskRecordPort.ExecutionEvent(
                        "instruction-" + active.rootRunId() + "-" + command.commandId(), command.taskId(),
                        active.rootRunId(), active.rootRunId(), null, null, 0,
                        TaskRecordPort.EventKind.RUN_INTERRUPTION_REQUESTED, details);
                records.append(active, interruption);
            }
            state.commands.put(command.commandId(), command);
            state.receipts.put(command.commandId(), receipt);
            state.revision = revision;
            state.latestKind = command.kind();
            // 新输入重新打开待处理工作，不能因旧版本完成而丢弃。
            state.completed = false;
            return receipt;
        }
    }

    @Override
    public Snapshot bind(RunClaim claim) {
        Objects.requireNonNull(claim);
        synchronized (records) {
            requireActive(claim);
            InstructionState state = state(claim.taskId());
            Snapshot existing = state.bindings.get(claim.rootRunId());
            if (existing != null) {
                return existing;
            }
            if (state.latestKind == Kind.PAUSE || state.latestKind == Kind.CANCEL || state.completed) {
                throw new IllegalStateException("Task cannot resume without a new continue or amend command");
            }
            Snapshot selected = snapshot(state);
            state.bindings.put(claim.rootRunId(), selected);
            return selected;
        }
    }

    @Override
    public Directive check(RunClaim claim, long instructionRevision) {
        Objects.requireNonNull(claim);
        synchronized (records) {
            if (!claim.equals(records.activeClaim(claim.taskId()))) {
                return Directive.WAIT;
            }
            InstructionState state = state(claim.taskId());
            Snapshot binding = state.bindings.get(claim.rootRunId());
            if (binding == null || binding.revision() != instructionRevision) {
                return Directive.WAIT;
            }
            if (state.latestKind == Kind.PAUSE) {
                return Directive.PAUSE;
            }
            if (state.latestKind == Kind.CANCEL) {
                return Directive.CANCEL;
            }
            if (state.revision != instructionRevision) {
                return Directive.REPLAN;
            }
            if (state.completed) {
                return Directive.WAIT;
            }
            return Directive.PROCEED;
        }
    }

    @Override
    public boolean applied(RunClaim claim, long instructionRevision, String runId,
                           long planRevision, Map<String, Object> plan) {
        TaskPlan.requireText(runId, "runId");
        Map<String, Object> immutablePlan = TaskPlan.immutableMap(plan);
        synchronized (records) {
            if (check(claim, instructionRevision) != Directive.PROCEED) {
                return false;
            }
            InstructionState state = state(claim.taskId());
            PlanKey key = new PlanKey(claim.rootRunId(), runId, planRevision);
            Map<String, Object> previous = state.plans.get(key);
            if (previous != null && !previous.equals(immutablePlan)) {
                throw new IllegalArgumentException("Plan identity has different contents");
            }
            state.plans.put(key, immutablePlan);
            state.appliedRevision = instructionRevision;
            return true;
        }
    }

    @Override
    public boolean complete(RunClaim claim, long instructionRevision, long expectedRecoveryRevision,
                            Map<String, Object> outcome) {
        Map<String, Object> immutableOutcome = TaskPlan.immutableMap(outcome);
        synchronized (records) {
            InstructionState state = state(claim.taskId());
            if (state.completed && claim.rootRunId().equals(state.completedRun)
                    && instructionRevision == state.revision && immutableOutcome.equals(state.completion)) {
                return claim.equals(records.activeClaim(claim.taskId()));
            }
            if (check(claim, instructionRevision) != Directive.PROCEED
                    || records.recovery(claim.taskId()).revision() != expectedRecoveryRevision
                    || records.recovery(claim.taskId()).disposition() != TaskRecordPort.Disposition.CONTINUE
                    || !"SUCCESS".equals(immutableOutcome.get("status"))) {
                return false;
            }
            state.appliedRevision = instructionRevision;
            state.completion = immutableOutcome;
            state.completedRun = claim.rootRunId();
            state.completed = true;
            return true;
        }
    }

    @Override
    public List<RecoveryCandidate> recoverable(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("Negative candidate limit");
        }
        synchronized (records) {
            List<RecoveryCandidate> candidates = new ArrayList<>();
            for (TaskRecordPort.TaskSnapshot task : records.tasks()) {
                if (candidates.size() == limit) {
                    break;
                }
                InstructionState state = state(task.taskId());
                if (records.activeClaim(task.taskId()) == null && !state.completed
                        && state.latestKind != Kind.PAUSE && state.latestKind != Kind.CANCEL) {
                    candidates.add(new RecoveryCandidate(task.taskId(), "Fixture found an unclaimed unfinished task"));
                }
            }
            return List.copyOf(candidates);
        }
    }

    @Override
    public Snapshot query(String taskId) {
        synchronized (records) {
            return snapshot(state(taskId));
        }
    }

    /** 检查当前最新目标是否通过原子完成提交，不把 endRun 当成任务成功。 */
    public boolean completed(String taskId) {
        synchronized (records) {
            return state(taskId).completed;
        }
    }

    /** 检查规划确实采用过指令，保留各次已校验计划的独立身份。 */
    public List<Map<String, Object>> applications(String taskId) {
        synchronized (records) {
            List<Map<String, Object>> applications = new ArrayList<>();
            for (Map.Entry<PlanKey, Map<String, Object>> entry : state(taskId).plans.entrySet()) {
                Map<String, Object> application = new LinkedHashMap<>();
                application.put("rootRunId", entry.getKey().rootRunId());
                application.put("runId", entry.getKey().runId());
                application.put("planRevision", entry.getKey().planRevision());
                application.put("plan", entry.getValue());
                applications.add(TaskPlan.immutableMap(application));
            }
            return List.copyOf(applications);
        }
    }

    private InstructionState state(String taskId) {
        // 先让真正的记录替身验证任务存在，不能为未知任务暗建一份指令状态。
        records.load(taskId);
        InstructionState state = states.get(taskId);
        if (state == null) {
            state = new InstructionState();
            states.put(taskId, state);
        }
        return state;
    }

    private void requireActive(RunClaim claim) {
        if (!claim.equals(records.activeClaim(claim.taskId()))) {
            throw new IllegalStateException("Claim is not active");
        }
    }

    private static Snapshot snapshot(InstructionState state) {
        return new Snapshot(state.revision, state.appliedRevision,
                List.copyOf(state.commands.values()), state.latestKind);
    }

    /** 计划身份属于一次运行，不把重启后的同一轮次当作旧计划重放。 */
    private record PlanKey(
            /** 根运行身份。 */
            String rootRunId,
            /** 根任务的递归运行身份。 */
            String runId,
            /** 本次计划轮次。 */
            long planRevision) {}

    /** 只用于检查，不保存线程、Future 或生产数据库对象。 */
    private static final class InstructionState {
        /** 最新已接受版本。 */
        private long revision;
        /** 最新已纳入计划或完成结论的版本。 */
        private long appliedRevision;
        /** 原始指令，保持接受顺序。 */
        private final Map<String, Command> commands = new LinkedHashMap<>();
        /** 同一输入重试返回同一回执。 */
        private final Map<String, Receipt> receipts = new LinkedHashMap<>();
        /** 各运行固定使用的快照。 */
        private final Map<String, Snapshot> bindings = new LinkedHashMap<>();
        /** 已验证的计划采用记录。 */
        private final Map<PlanKey, Map<String, Object>> plans = new LinkedHashMap<>();
        /** 原任务允许进入首次运行。 */
        private Kind latestKind = Kind.CONTINUE;
        /** 最新目标是否已经成功提交完成。 */
        private boolean completed;
        /** 提交完成的根运行身份。 */
        private String completedRun;
        /** 最近一次完成结果，用于验证幂等提交。 */
        private Map<String, Object> completion;
    }
}
