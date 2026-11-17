package com.example.myhelper.interaction;

import com.example.myhelper.orchestration.AgentOrchestrator.Handle;
import com.example.myhelper.orchestration.AgentOrchestrator.Outcome;
import com.example.myhelper.records.RunArchivePort.ArchiveRef;
import com.example.myhelper.tasklog.TaskLogHistory;
import com.example.myhelper.tasklog.TaskLogPort;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/** 读取终端输入，并将异步任务结果按运行身份输出。 */
public final class ConsoleInput {
    // 文字输入与未来的语音输入使用同一个入口。
    private final TaskEntry entry;

    // 输入和输出流由应用启动方管理。
    private final BufferedReader input;
    private final PrintWriter output;

    /** 本机操作终端的归档读取入口。未启用记录时为空。 */
    private final TaskLogHistory history;

    // 退出前等待读到的输入全部产生对外结果。
    private final Set<CompletableFuture<Void>> reports = ConcurrentHashMap.newKeySet();

    public ConsoleInput(TaskEntry entry, BufferedReader input, PrintWriter output) {
        this(entry, input, output, null);
    }

    public ConsoleInput(TaskEntry entry, BufferedReader input, PrintWriter output, TaskLogHistory history) {
        this.entry = entry;
        this.input = input;
        this.output = output;
        this.history = history;
    }

    public void run() throws IOException {
        print("请输入任务，每行一条。输入 /exit 结束输入并等待已提交的结果。"
                + (history == null ? "" : " 使用 /history <taskId> <rootRunId> 查看已结束运行的日志。"));
        String line;
        while ((line = input.readLine()) != null && !line.strip().equals("/exit")) {
            String[] parts = line.strip().split("\\s+", 3);
            if (parts[0].equals("/history")) {
                printHistory(parts);
            } else if (!line.isBlank()) {
                submit(line);
            }
        }
        CompletableFuture.allOf(reports.toArray(CompletableFuture<?>[]::new)).join();
    }

    private void printHistory(String[] parts) {
        if (parts.length != 3 || "-".equals(TaskLogPort.safeLabel(parts[1]))
                || "-".equals(TaskLogPort.safeLabel(parts[2]))) {
            print("用法：/history <taskId> <rootRunId>");
            return;
        }
        if (history == null) {
            print("任务归档未装配，当前只能查看实时日志。");
            return;
        }
        try {
            var result = history.read(new ArchiveRef(parts[1], parts[2]));
            if (result.isEmpty()) {
                print("未找到已提交的运行归档。");
                return;
            }
            TaskLogHistory.History archive = result.get();
            print("归档 graphComplete=" + archive.graphComplete() + " evidenceComplete=" + archive.evidenceComplete());
            for (TaskLogPort.Event event : archive.entries()) {
                print(event.line());
            }
        } catch (RuntimeException failure) {
            print("任务归档读取失败，可检查记录服务。错误类型="
                    + TaskLogPort.safeLabel(failure.getClass().getSimpleName()));
        }
    }

    private void submit(String line) {
        Handle handle;
        try {
            handle = entry.submit(line);
        } catch (RuntimeException failure) {
            print("任务提交失败：" + failure.getMessage());
            return;
        }
        String id = "[" + handle.rootRunId() + "] ";
        print(id + "已提交");
        CompletableFuture<Void> report = handle.result().handle(new BiFunction<Outcome, Throwable, Void>() {
            @Override
            public Void apply(Outcome outcome, Throwable failure) {
                if (failure != null) {
                    print(id + "失败：" + failure.getMessage());
                    return null;
                }
                StringBuilder message = new StringBuilder(id).append(outcome.status());
                if (outcome.value() != null) {
                    message.append(System.lineSeparator()).append(outcome.value());
                }
                if (outcome.error() != null) {
                    message.append(System.lineSeparator()).append(outcome.error());
                }
                print(message.toString());
                return null;
            }
        });
        reports.add(report);
        report.whenComplete(new BiConsumer<Void, Throwable>() {
            @Override
            public void accept(Void ignored, Throwable failure) {
                reports.remove(report);
            }
        });
    }

    private synchronized void print(String message) {
        output.println(message);
        output.flush();
    }
}
