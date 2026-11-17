package com.example.myhelper.records;

import com.example.myhelper.records.RunArchivePort.ArchivedRun;
import com.example.myhelper.records.TaskInstructionPort.Command;
import com.example.myhelper.records.TaskInstructionPort.Kind;
import com.example.myhelper.records.TaskInstructionPort.Receipt;
import com.example.myhelper.records.TaskInstructionPort.Snapshot;
import com.example.myhelper.records.TaskRecordPort.Disposition;
import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.records.TaskRecordPort.RecoveryItem;
import com.example.myhelper.records.TaskRecordPort.ResolutionProposal;
import com.example.myhelper.records.TaskRecordPort.RunClaim;
import com.example.myhelper.records.TaskRecordPort.TaskSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Jackson 专用聚合载荷；每次读取创建新对象，不作为跨调用缓存。 */
final class RecordState {
    /** 当前持久聚合格式，保持兼容既存 schema 1。 */
    static final int SCHEMA = 1;
    /** 数据库中该模块的实体范围。 */
    static final String ENTITY_KIND = "TASK_RECORD";
    /** 已记录但尚未独立审核的问题。 */
    static final String OPEN = "OPEN";
    /** 只影响模型重试调度，不表示动作影响已经解决。 */
    static final String WAITING_MODEL = "WAITING_MODEL";
    /** 已有派发意图但没有返回确认。 */
    static final String UNCONFIRMED = "UNCONFIRMED";
    /** 执行成功返回但尚未验收。 */
    static final String UNVERIFIED = "UNVERIFIED";
    /** 结果或影响无法确认。 */
    static final String UNKNOWN = "UNKNOWN";
    /** 明确返回失败，不自动等同于未知影响。 */
    static final String FAILED = "FAILED";
    /** 已明确返回成功。 */
    static final String SUCCESS = "SUCCESS";
    /** 旧端口未提供最终图。 */
    static final String NO_FINAL_GRAPH = "NO_FINAL_GRAPH";
    /** 旧本地进程退出，无法重建其内存图。 */
    static final String PROCESS_INTERRUPTED_NO_FINAL_GRAPH = "PROCESS_INTERRUPTED_NO_FINAL_GRAPH";
    /** 宿主已确认本地旧运行停止。 */
    static final String STOP_CONFIRMED = "STOP_CONFIRMED";

    /** 稳定任务身份。 */
    public String taskId;
    /** 原始目标与来源。 */
    public TaskSpec task;
    /** 当前唯一运行领取。 */
    public RunClaim active;
    /** 当前领取所属宿主，结束时清空。 */
    public String activeOwner;
    /** 历史领取用于禁止运行身份重用。 */
    public Map<String, RunClaim> claims = new LinkedHashMap<>();
    /** 各运行固定采用的指令快照。 */
    public Map<String, Snapshot> bindings = new LinkedHashMap<>();
    /** 稳定身份对应的全部事件。 */
    public Map<String, ExecutionEvent> events = new LinkedHashMap<>();
    /** 明确的提交顺序；分页不能依赖 JSON 对象字段的物理顺序。 */
    public List<String> eventOrder = new ArrayList<>();
    /** 已接收指令的幂等记录。 */
    public Map<String, Command> commands = new LinkedHashMap<>();
    /** 每项指令原回执。 */
    public Map<String, Receipt> receipts = new LinkedHashMap<>();
    /** 已应用计划及其固定身份。 */
    public Map<String, Map<String, Object>> plans = new LinkedHashMap<>();
    /** 已结束运行的固定归档。 */
    public Map<String, ArchivedRun> archives = new LinkedHashMap<>();
    /** 持久固化待办；唤醒失败不会丢失。 */
    public LinkedHashSet<String> pendingArchives = new LinkedHashSet<>();
    /** 待核对事项，由独立审核关闭。 */
    public Map<String, RecoveryItem> incidents = new LinkedHashMap<>();
    /** 审核提案保留审计。 */
    public List<ResolutionProposal> reviewHistory = new ArrayList<>();
    /** 与存储CAS版本分开的恢复依据版本。 */
    public long recoveryRevision;
    /** 当前接续结论。 */
    public Disposition disposition = Disposition.CONTINUE;
    /** 接续或等待原因。 */
    public String reason;
    /** 当前已接收指令版本。 */
    public long instructionRevision;
    /** 已应用到计划/完成的指令版本。 */
    public long appliedRevision;
    /** 最新控制意图。 */
    public Kind latestKind = Kind.CONTINUE;
    /** 当前目标是否完成。 */
    public boolean completed;
    /** 完成提交所属运行。 */
    public String completedRun;
    /** 已验收完成的具体结果。 */
    public Map<String, Object> completion = Map.of();
}
