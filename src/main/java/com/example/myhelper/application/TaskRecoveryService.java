package com.example.myhelper.application;

import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.records.TaskInstructionPort;
import com.example.myhelper.records.TaskInstructionPort.RecoveryCandidate;
import com.example.myhelper.records.TaskRecordPort;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 应用启动或外部唤醒时使用的有界恢复入口；不是应用 main，也不是后台轮询器。
 * 记录模块先核对中断、未知动作与恢复资格，本类只把候选交给循环的 resume。
 */
public final class TaskRecoveryService {
    /** 已装配的循环；恢复时由它内部唯一一次领取运行权。 */
    private final AgentOrchestrator orchestrator;
    /** 与循环使用同一权威记录来源，不能用另一个任务仓储扫描候选。 */
    private final TaskRecordPort records;

    public TaskRecoveryService(AgentOrchestrator orchestrator, TaskRecordPort records) {
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.records = Objects.requireNonNull(records);
    }

    /**
     * 最多读取 limit 条候选，返回每个不同任务的接续受理结果；不等待任务执行结束。
     * 候选扫描不领取运行权，避免与 resume 内部 beginRun 重复领取。
     */
    public List<RecoveryAttempt> resumeAvailable(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("恢复批次数量必须为正");
        }
        TaskInstructionPort instructions = Objects.requireNonNull(records.instructions());
        if (!records.enabled() || !instructions.enabled()) {
            throw new IllegalStateException("恢复扫描需要已启用的任务记录与指令模块");
        }
        // 先核对整批返回合同，再开启任一任务，避免发现超限时已经执行了部分工作。
        List<RecoveryCandidate> candidates = validatedCandidates(instructions.recoverable(limit), limit);
        List<RecoveryAttempt> attempts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RecoveryCandidate candidate : candidates) {
            if (!seen.add(candidate.taskId())) {
                continue;
            }
            try {
                // 当前资格仍由领取和绑定再次校验；扫描后被暂停或被其他实例领取会明确失败。
                AgentOrchestrator.Handle handle = orchestrator.resume(candidate.taskId());
                attempts.add(new RecoveryAttempt(candidate.taskId(), candidate.reason(), handle, null));
            } catch (RuntimeException failure) {
                // 保留该任务的接续错误，继续处理其他候选；不据失败删除外部待办。
                attempts.add(new RecoveryAttempt(candidate.taskId(), candidate.reason(), null,
                        failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            }
        }
        return List.copyOf(attempts);
    }

    private static List<RecoveryCandidate> validatedCandidates(List<RecoveryCandidate> candidates, int limit) {
        Objects.requireNonNull(candidates, "恢复模块不能返回空候选列表引用");
        if (candidates.size() > limit) {
            throw new IllegalStateException("恢复模块返回的候选数量超过本次上限");
        }
        List<RecoveryCandidate> snapshot = List.copyOf(candidates);
        for (RecoveryCandidate candidate : snapshot) {
            if (candidate.taskId() == null || candidate.taskId().isBlank()) {
                throw new IllegalStateException("恢复候选缺少稳定任务身份");
            }
        }
        return snapshot;
    }

    /** 恢复受理结果；有 handle 仅表示已进入循环，不表示任务完成或副作用已核对。 */
    public record RecoveryAttempt(
            /** 跨中断保持不变的任务身份。 */
            String taskId,
            /** 记录模块提供的恢复原因，不由应用猜测租约失效的含义。 */
            String reason,
            /** 已受理运行的句柄，调用方按其真实结果与收尾信号展示进度。 */
            AgentOrchestrator.Handle handle,
            /** 受理失败原因；失败项没有 handle，待办仍由记录模块维护。 */
            String error) {
    }
}
