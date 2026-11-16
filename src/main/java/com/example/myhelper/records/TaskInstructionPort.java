package com.example.myhelper.records;

import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.records.TaskRecordPort.RunClaim;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 独立记录模块提供的用户指令和执行版本合同；循环不保存权威指令日志。
 * 实现方须与 TaskRecordPort 共用任务、运行领取及一致性边界。
 * 方法返回持久提交或原子校验的结果，不表示任意远端动作已经停止。
 */
public interface TaskInstructionPort {
    /** 明确区分尚未装配与已装配但当前没有用户修订。 */
    boolean enabled();

    /**
     * 应用鉴权后提交指令，无需工作运行的 RunClaim。
     * 同 commandId 同内容重试返回原回执；同 ID 不同内容必须拒绝。
     * 先检查幂等记录，再比较 expectedRevision；新版本与停止派发要求一起持久提交。
     * 接受新的修改可重新打开已完成任务，不能被旧完成状态吞掉。
     */
    Receipt accept(Command command);

    /**
     * 为有效领取原子固定当前指令快照，首次派发前调用；重复调用返回同一快照。
     * 暂停、取消、旧领取以及尚未满足恢复资格的任务不得绑定。
     * beginRun 与本方法之间若收到新指令，以本次固定的快照为准；此前不能派发动作。
     */
    Snapshot bind(RunClaim claim);

    /**
     * 派发前、等锁后及处理模型返回前校验有效领取和固定版本。
     * 与 accept 的原子先后决定哪些动作已获准；获准动作仍可能在途，不能承诺远端即刻停止。
     * 领取失效必须拒绝或返回 WAIT，不能把未知状态当作 PROCEED。
     */
    Directive check(RunClaim claim, long instructionRevision);

    /** 原子记录已校验的根计划采用当前指令；版本过期返回 false，不覆盖新要求。 */
    boolean applied(RunClaim claim, long instructionRevision, String runId,
                    long planRevision, Map<String, Object> plan);

    /**
     * 根任务验收后提交完成；同时比较指令版本、领取和验收依据的记录快照版本。
     * 直接 COMPLETE 可同时标记指令已应用；提交失败返回 false，保留新指令和已有产物。
     * 成功不释放运行领取，真实收尾仍由 TaskRecordPort.endRun 完成。
     */
    boolean complete(RunClaim claim, long instructionRevision, long expectedRecoveryRevision,
                     Map<String, Object> outcome);

    /**
     * 返回至多 limit 个外部判定可接续的任务；不在这里领取，不包含明确暂停或取消的原目标。
     * 异常失联的检测、远端效果核对及接管资格由实现方处理，不能仅凭租约过期重放动作。
     */
    List<RecoveryCandidate> recoverable(int limit);

    /** 应用查询最新接受及应用版本；应用层仍需校验当前用户能否读取该任务。 */
    Snapshot query(String taskId);

    /** 未装配时明确拒绝修改，不用内存指令队列伪装持久接收。 */
    static TaskInstructionPort disabled() {
        return DisabledTaskInstructions.INSTANCE;
    }

    /** 用户或入口明确给出的控制类型；循环不自行从自然语言猜测。 */
    enum Kind {
        /** 打断当前任务并按新增要求重新规划。 */
        AMEND,
        /** 停止新增派发，等待明确继续。 */
        PAUSE,
        /** 取消原目标，保留已发生效果和待核对事项。 */
        CANCEL,
        /** 明确要求接续原任务，可附补充信息。 */
        CONTINUE
    }

    /** 外部记录模块对当前运行的接续结论。 */
    enum Directive {
        /** 当前领取及指令版本仍允许推进。 */
        PROCEED,
        /** 新指令已经接受，旧运行应收束后用新运行重新规划。 */
        REPLAN,
        /** 用户已暂停，不自动恢复原目标。 */
        PAUSE,
        /** 用户已取消，不自动恢复原目标。 */
        CANCEL,
        /** 领取或接续条件不满足，等待外部处理。 */
        WAIT
    }

    /** 一次用户输入，原文与去重身份共同保留。 */
    record Command(
            /** 跨运行保持不变的任务身份。 */
            String taskId,
            /** 本任务内唯一的输入去重身份。 */
            String commandId,
            /** 提交方所依据的最新接受版本。 */
            long expectedRevision,
            /** 明确的控制类型，不从正文中猜测。 */
            Kind kind,
            /** 用户原文或入口给出的控制说明。 */
            String text) {
        public Command {
            TaskPlan.requireText(taskId, "taskId");
            TaskPlan.requireText(commandId, "commandId");
            requireRevision(expectedRevision);
            Objects.requireNonNull(kind);
            TaskPlan.requireText(text, "text");
        }
    }

    /** 已持久接收的回执，不表示旧运行已停止或新要求已执行。 */
    record Receipt(
            /** 指令所属任务。 */
            String taskId,
            /** 已接受的输入身份。 */
            String commandId,
            /** 接受这条指令后产生的版本。 */
            long revision,
            /** 本条输入的控制类型。 */
            Kind kind) {
        public Receipt {
            TaskPlan.requireText(taskId, "taskId");
            TaskPlan.requireText(commandId, "commandId");
            requireRevision(revision);
            Objects.requireNonNull(kind);
        }
    }

    /** 查询时代表最新快照；bind 返回时代表本次运行固定使用的不可变快照。 */
    record Snapshot(
            /** 快照包含的最新接受版本；bind 后成为该运行的固定指令版本。 */
            long revision,
            /** 已经纳入可执行计划或明确处理结论的版本。 */
            long appliedRevision,
            /** 按接受顺序保存的用户指令，包含控制命令和补充原文。 */
            List<Command> instructions,
            /** 最新输入明确给出的控制类型；原始任务默认 CONTINUE。 */
            Kind latestKind) {
        public Snapshot {
            requireRevision(revision);
            requireRevision(appliedRevision);
            if (appliedRevision > revision) {
                throw new IllegalArgumentException(LlmTexts.INVALID_RECORD_REVISION);
            }
            instructions = List.copyOf(instructions);
            Objects.requireNonNull(latestKind);
        }

        /** 原始任务尚无追加指令，不代表记录服务已经持久化。 */
        public static Snapshot empty() {
            return new Snapshot(0, 0, List.of(), Kind.CONTINUE);
        }
    }

    /** 外部恢复扫描返回的候选，只供应用触发，不自动持有运行权。 */
    record RecoveryCandidate(
            /** 需要接续的稳定任务身份。 */
            String taskId,
            /** 外部给出的接续依据。 */
            String reason) {
        public RecoveryCandidate {
            TaskPlan.requireText(taskId, "taskId");
            TaskPlan.requireText(reason, "reason");
        }
    }

    /** 所有版本均为非负值；版本身份与模型输出分开校验。 */
    private static void requireRevision(long revision) {
        if (revision < 0) {
            throw new IllegalArgumentException(LlmTexts.INVALID_RECORD_REVISION);
        }
    }
}

/** 未装配实现只表达不可用，不创建指令、领取或恢复状态。 */
enum DisabledTaskInstructions implements TaskInstructionPort {
    /** 无状态单例。 */
    INSTANCE;

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Receipt accept(Command command) {
        throw new IllegalStateException(LlmTexts.TASK_INSTRUCTIONS_REQUIRED);
    }

    @Override
    public Snapshot bind(RunClaim claim) {
        throw new IllegalStateException(LlmTexts.TASK_INSTRUCTIONS_REQUIRED);
    }

    @Override
    public Directive check(RunClaim claim, long instructionRevision) {
        return Directive.WAIT;
    }

    @Override
    public boolean applied(RunClaim claim, long instructionRevision, String runId,
                           long planRevision, Map<String, Object> plan) {
        throw new IllegalStateException(LlmTexts.TASK_INSTRUCTIONS_REQUIRED);
    }

    @Override
    public boolean complete(RunClaim claim, long instructionRevision, long expectedRecoveryRevision,
                            Map<String, Object> outcome) {
        throw new IllegalStateException(LlmTexts.TASK_INSTRUCTIONS_REQUIRED);
    }

    @Override
    public List<RecoveryCandidate> recoverable(int limit) {
        return List.of();
    }

    @Override
    public Snapshot query(String taskId) {
        throw new IllegalStateException(LlmTexts.TASK_INSTRUCTIONS_REQUIRED);
    }
}
