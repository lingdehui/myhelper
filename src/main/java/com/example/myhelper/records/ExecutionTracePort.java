package com.example.myhelper.records;

import com.example.myhelper.orchestration.ExecutionTraceStore;

/**
 * 独立执行链路模块的写入和查询端口。
 * DTO 暂沿用现有执行图类型；实现可以保存内存图或转交外部记录服务。
 * 这些调用本身不提供任务进度、持久恢复或业务验收保证。
 */
public interface ExecutionTracePort {
    /** 建立本次根运行的图，不覆盖已有运行。 */
    void open(String rootRunId);

    /** 记录节点；节点创建并不表示实际工具已开始执行。 */
    void add(String rootRunId, ExecutionTraceStore.Node node);

    /** 记录明确的父子、依赖或数据来源关系。 */
    void edge(String rootRunId, ExecutionTraceStore.Edge edge);

    /** 保存实际完成结果；输出与错误同时保留，不把验收失败视为没有产物。 */
    void finish(String rootRunId, String nodeId, ExecutionTraceStore.NodeStatus status,
                Object output, String error);

    /** 只在真实在途工作结束后关闭，不随对外提前取消而关闭。 */
    void close(String rootRunId);

    /** 返回不可变快照，供固化、审计等模块只读使用。 */
    ExecutionTraceStore.Snapshot snapshot(String rootRunId);
}
