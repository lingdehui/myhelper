package com.example.myhelper.interaction;

import com.example.myhelper.orchestration.AgentOrchestrator.Handle;
import com.example.myhelper.orchestration.AgentOrchestrator.Outcome;
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

    // 退出前等待读到的输入全部产生对外结果。
    private final Set<CompletableFuture<Void>> reports = ConcurrentHashMap.newKeySet();

    public ConsoleInput(TaskEntry entry, BufferedReader input, PrintWriter output) {
        this.entry = entry;
        this.input = input;
        this.output = output;
    }

    public void run() throws IOException {
        print("请输入任务，每行一条。输入 /exit 结束输入并等待已提交的结果。");
        String line;
        while ((line = input.readLine()) != null && !line.strip().equals("/exit")) {
            if (!line.isBlank()) {
                submit(line);
            }
        }
        CompletableFuture.allOf(reports.toArray(CompletableFuture<?>[]::new)).join();
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
