package com.example.desktopbrain.service;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * ToolCallback 包装器：在每次工具调用前后打印日志，
 * 让用户能看到 AI 每一步在干什么。
 */
public class LoggingToolCallback implements ToolCallback {

    private final ToolCallback delegate;

    public LoggingToolCallback(ToolCallback delegate) {
        this.delegate = delegate;
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
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("     ❌ (" + elapsed + "ms) " + e.getMessage());
            throw e;
        }
    }

    private static String preview(String s, int max) {
        if (s == null) return "(null)";
        String oneLine = s.replace("\n", " ").trim();
        return oneLine.length() > max ? oneLine.substring(0, max) + "..." : oneLine;
    }
}
