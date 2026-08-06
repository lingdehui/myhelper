package com.example.desktopbrain.service;

import com.example.desktopbrain.memory.vector.episode.ToolCallLog;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

/**
 * ToolCallback 包装器：在每次工具调用前后打印日志，
 * 让用户能看到 AI 每一步在干什么。
 *
 * <p>同时把每次工具调用信息收集到 {@code collector}（List&lt;ToolCallLog&gt;），
 * 供 Episode 系统持久化为完整执行轨迹（ExpeL/MUSE 经验学习的最小单元）。</p>
 *
 * <p>collector 由调用方（{@code DesktopBrainApplication.processAITurn}）创建并传入，
 * 每个 AI turn 一个 collector，turn 结束后作为 Episode 的 toolCalls 字段写入 Qdrant。
 * args 和 result 截断到 500 字符，避免 OCR 全屏文字等长结果撑爆 Qdrant payload。</p>
 */
public class LoggingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    /** 工具调用日志收集器（每个 AI turn 一个，由调用方传入） */
    private final List<ToolCallLog> collector;

    public LoggingToolCallback(ToolCallback delegate, List<ToolCallLog> collector) {
        this.delegate = delegate;
        this.collector = collector;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        String name = delegate.getToolDefinition().name();
        System.out.println("  🔧 [" + name + "] 参数: " + preview(toolInput, 300));

        long start = System.currentTimeMillis();
        try {
            String result = delegate.call(toolInput);
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("     ✅ (" + elapsed + "ms) " + preview(result, 300));
            // 收集到 Episode 轨迹（args/result 截断到 500 字符）
            collector.add(new ToolCallLog(name, preview(toolInput, 500), preview(result, 500), true, elapsed));
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("     ❌ (" + elapsed + "ms) " + e.getMessage());
            // 失败也收集（result 字段存异常 message）
            collector.add(new ToolCallLog(name, preview(toolInput, 500), e.getMessage(), false, elapsed));
            throw e;
        }
    }

    private static String preview(String s, int max) {
        if (s == null) return "(null)";
        String oneLine = s.replace("\n", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "..." : oneLine;
    }
}
