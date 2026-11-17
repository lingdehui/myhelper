package com.example.myhelper.interaction;

import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.orchestration.AgentOrchestrator.Handle;
import com.example.myhelper.orchestration.AgentOrchestrator.Outcome;
import com.example.myhelper.orchestration.AgentOrchestrator.Request;
import com.example.myhelper.orchestration.ExecutionTraceStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 终端和运行中接入的输入适配器共用的任务提交入口。 */
public final class TaskEntry implements AutoCloseable {
    // 记录停机时无法确认收尾的运行。
    private static final Logger LOG = LoggerFactory.getLogger(TaskEntry.class);

    // 输入入口使用同一个应用级编排器。
    private final AgentOrchestrator orchestrator;

    // 新任务使用的已配置模型和允许的业务工具。
    private final String modelRef;

    // 可用于终端及扩展输入任务的业务工具。
    private final List<String> toolNames;

    // 入口拥有的内存图在编排完成最终提交后释放。
    private final ExecutionTraceStore traces;

    // 可选桌面观察端在执行图释放前取得最终只读快照。
    private final TraceListener traceListener;

    // 已报告超时的任务仍保留在这里，直到实际工作和收尾结束。
    private final Map<String, Handle> active = new ConcurrentHashMap<>();

    // 在同一把锁内停止受理并取得剩余任务快照。
    private boolean closed;

    public TaskEntry(AgentOrchestrator orchestrator, String modelRef, List<String> toolNames,
                     ExecutionTraceStore traces) {
        this(orchestrator, modelRef, toolNames, traces, null);
    }

    public TaskEntry(AgentOrchestrator orchestrator, String modelRef, List<String> toolNames,
                     ExecutionTraceStore traces, TraceListener traceListener) {
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.modelRef = Objects.requireNonNull(modelRef);
        this.toolNames = List.copyOf(toolNames);
        this.traces = Objects.requireNonNull(traces);
        this.traceListener = traceListener;
    }

    /**
     * 将一条完整文字作为新任务提交。语音适配器可以提交最终转写并消费同样的异步结果。
     */
    public synchronized Handle submit(String text) {
        if (closed) {
            throw new IllegalStateException("任务入口已关闭");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("任务文字不能为空");
        }
        Handle handle = orchestrator.submit(new Request(text, Map.of(), modelRef, toolNames));
        return adopt(handle);
    }

    /** 启动恢复和新任务共享句柄管理；此处不重复提交或领取任务。 */
    public synchronized Handle adopt(Handle handle) {
        if (closed) {
            orchestrator.stopForShutdown(handle.taskId(), handle.rootRunId());
            throw new IllegalStateException("任务入口已关闭");
        }
        if (active.putIfAbsent(handle.rootRunId(), handle) != null) { return handle; }
        CompletableFuture<Outcome> released = handle.settled().whenComplete(new BiConsumer<Outcome, Throwable>() {
            @Override
            public void accept(Outcome outcome, Throwable failure) {
                try {
                    reportFinalTrace(handle.rootRunId());
                    traces.remove(handle.rootRunId());
                } catch (RuntimeException cleanupFailure) {
                    LOG.warn("无法释放任务 {} 的内存执行图", handle.rootRunId(), cleanupFailure);
                } finally {
                    active.remove(handle.rootRunId());
                }
                if (failure != null) {
                    LOG.warn("任务 {} 收尾失败", handle.rootRunId(), failure);
                }
            }
        });
        return new Handle(handle.rootRunId(), handle.result(), handle.cancel(), handle.taskId(), released);
    }

    /** 为进程内的调试视图提供正在管理的句柄，包括已受理的启动恢复。 */
    public List<Handle> activeHandles() {
        return List.copyOf(active.values());
    }

    private void reportFinalTrace(String rootRunId) {
        if (traceListener == null) { return; }
        try {
            var snapshot = traces.findSnapshot(rootRunId).orElse(null);
            if (snapshot != null && snapshot.closed()) {
                traceListener.afterClose(rootRunId, snapshot);
            }
        } catch (RuntimeException failure) {
            LOG.warn("无法投影任务 {} 的最终执行图", rootRunId, failure);
        }
    }

    @FunctionalInterface
    public interface TraceListener {
        void afterClose(String rootRunId, ExecutionTraceStore.Snapshot snapshot);
    }

    @Override
    public void close() {
        List<Handle> remaining;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            remaining = List.copyOf(active.values());
        }
        List<CompletableFuture<Outcome>> settlements = new ArrayList<>(remaining.size());
        for (Handle handle : remaining) {
            settlements.add(handle.settled());
            try {
                orchestrator.stopForShutdown(handle.taskId(), handle.rootRunId());
            } catch (RuntimeException failure) {
                LOG.warn("无法请求停止任务 {}", handle.rootRunId(), failure);
            }
        }
        try {
            CompletableFuture.allOf(settlements.toArray(CompletableFuture<?>[]::new)).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException timeout) {
            LOG.warn("停机等待已超时，仍有任务未确认收尾");
        } catch (ExecutionException failure) {
            // 单项收尾异常已经由提交时注册的回调记录。
        }
    }
}
