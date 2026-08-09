package com.example.desktopbrain.service;

import com.example.desktopbrain.common.AiResponseUtils;
import com.example.desktopbrain.memory.vector.episode.ToolCallLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(LoggingToolCallback.class);

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

        // 过滤空参数调用：toolInput 为空/null/仅空白/仅 {} → 跳过
        if (toolInput == null || toolInput.isBlank() 
                || toolInput.replaceAll("[{}\"\\s:,]", "").isEmpty()) {
            log.info("  ⏭️ [{}] 跳过（参数为空）", name);
            collector.add(new ToolCallLog(name, "(空)", "(已跳过)", true, 0));
            return "已跳过（参数为空）";
        }

        log.info("  🔧 [{}] 参数: {}", name, AiResponseUtils.truncate(toolInput, 300));

        long start = System.currentTimeMillis();
        try {
            String result = delegate.call(toolInput);
            long elapsed = System.currentTimeMillis() - start;
            log.info("     ✅ ({}ms) {}", elapsed, AiResponseUtils.truncate(result, 300));
            // 收集到 Episode 轨迹（args/result 截断到 500 字符）
            collector.add(new ToolCallLog(name, AiResponseUtils.truncate(toolInput, 500),
                    AiResponseUtils.truncate(result, 500), true, elapsed));
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("     ❌ ({}ms) {}", elapsed, e.getMessage());
            // 失败也收集（result 字段存异常 message）
            collector.add(new ToolCallLog(name, AiResponseUtils.truncate(toolInput, 500),
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), false, elapsed));
            throw e;
        }
    }
}
