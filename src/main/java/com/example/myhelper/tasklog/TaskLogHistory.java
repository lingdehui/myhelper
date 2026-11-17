package com.example.myhelper.tasklog;

import com.example.myhelper.records.RunArchivePort;
import com.example.myhelper.records.RunArchivePort.ArchiveRef;
import com.example.myhelper.records.RunArchivePort.ArchivedRun;
import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.tasklog.TaskLogPort.Event;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 对已结束运行提供只读日志摘要。调用方须先校验访问该任务的权限。 */
public final class TaskLogHistory {
    /** 权威归档来源。 */
    private final RunArchivePort archives;

    public TaskLogHistory(RunArchivePort archives) {
        this.archives = Objects.requireNonNull(archives);
    }

    /** 复用归档提交顺序并保留完整性标记。 */
    public Optional<History> read(ArchiveRef ref) {
        return archives.readArchive(ref).map(TaskLogHistory::project);
    }

    private static History project(ArchivedRun archive) {
        List<Event> entries = new ArrayList<>();
        for (ExecutionEvent event : archive.events()) {
            entries.add(TaskLogProjection.from(event));
        }
        return new History(archive.ref(), archive.graphComplete(), archive.evidenceComplete(), entries);
    }

    public record History(
            /** 已提交归档的任务与运行身份。 */
            ArchiveRef ref,
            /** 归档执行图是否完整。 */
            boolean graphComplete,
            /** 运行事实是否全部取得提交确认。 */
            boolean evidenceComplete,
            /** 按归档顺序排列的安全摘要。 */
            List<Event> entries) {
        public History {
            entries = List.copyOf(entries);
        }
    }
}
