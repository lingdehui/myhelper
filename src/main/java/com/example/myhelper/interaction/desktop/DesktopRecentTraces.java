package com.example.myhelper.interaction.desktop;

import com.example.myhelper.interaction.TaskEntry;
import com.example.myhelper.orchestration.ExecutionTraceStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** 短任务可能在桌面第一次读图前结束，保留有限条收尾图摘要。 */
public final class DesktopRecentTraces implements TaskEntry.TraceListener {
    /** 仅保留最近收尾的运行摘要，不占用执行图的运行容量。 */
    private final int maxRuns;
    /** 收尾次序的只读标题、状态与关系，不保存输入、输出和证据。 */
    private final LinkedHashMap<String, List<DesktopTaskGraph.Node>> traces = new LinkedHashMap<>();

    public DesktopRecentTraces(int maxRuns) {
        if (maxRuns < 1) {
            throw new IllegalArgumentException("桌面收尾图容量必须大于零");
        }
        this.maxRuns = maxRuns;
    }

    @Override
    public void afterClose(String rootRunId, ExecutionTraceStore.Snapshot snapshot) {
        List<DesktopTaskGraph.Node> view = DesktopTaskGraph.project(snapshot);
        synchronized (traces) {
            traces.putLast(rootRunId, view);
            if (traces.size() > maxRuns) {
                traces.pollFirstEntry();
            }
        }
    }

    Optional<List<DesktopTaskGraph.Node>> find(String rootRunId) {
        synchronized (traces) {
            return Optional.ofNullable(traces.get(rootRunId));
        }
    }
}
