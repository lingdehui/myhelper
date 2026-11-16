package com.example.myhelper.skill;

import com.example.myhelper.records.RunArchivePort;
import com.example.myhelper.records.RunArchivePort.ArchiveRef;
import com.example.myhelper.records.RunArchivePort.ArchivedRun;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;
import java.util.concurrent.Executor;

/** 归档提交后唤醒，执行事实和固化待办可以按原运行身份重放。 */
public final class ArchivedSkillProcessing implements RunArchivePort.Listener {
    /** 延迟获取归档，避免归档仓储为装配监听器而形成依赖环。 */
    private final ObjectProvider<RunArchivePort> archives;
    /** 后台参数保存。 */
    private final ArchivedToolParameterRecorder recorder;
    /** 新候选等其它固化工作。 */
    private final ObjectProvider<CompletedRunConsolidation> consolidation;
    /** 专用后台线程，不占用用户任务调度器。 */
    private final Executor executor;

    public ArchivedSkillProcessing(ObjectProvider<RunArchivePort> archives, ArchivedToolParameterRecorder recorder,
                                   ObjectProvider<CompletedRunConsolidation> consolidation, Executor executor) {
        this.archives = Objects.requireNonNull(archives);
        this.recorder = Objects.requireNonNull(recorder);
        this.consolidation = Objects.requireNonNull(consolidation);
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public void afterCommit(ArchiveRef ref) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                process(ref);
            }
        });
    }

    /** 重新提交本批待办，供启动恢复或重试入口使用。 */
    public void retryPending(int limit) {
        for (ArchiveRef ref : archives.getObject().pendingConsolidations(limit)) {
            afterCommit(ref);
        }
    }

    private void process(ArchiveRef ref) {
        RunArchivePort archive = archives.getObject();
        ArchivedRun run = archive.readArchive(ref).orElse(null);
        if (run == null) {
            throw new IllegalStateException("收到提交信号但找不到工具执行归档 " + ref);
        }
        if (run.internalValidation() || !run.graphComplete() || !run.evidenceComplete()) {
            return;
        }
        recorder.record(run);
        CompletedRunConsolidation handler = consolidation.getIfAvailable();
        if (handler != null && handler.process(ref.rootRunId())) {
            archive.acknowledgeConsolidation(ref);
        }
    }
}
