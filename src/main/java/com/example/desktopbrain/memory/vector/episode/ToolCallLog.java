package com.example.desktopbrain.memory.vector.episode;

/**
 * 单次工具调用日志（episode 轨迹的最小单元）。
 *
 * <p>每次 AI 调用一个工具时，{@code LoggingToolCallback} 会把调用信息
 * 封装成 ToolCallLog 收集到一个 List 里，最终作为 Episode 的工具轨迹持久化到 Qdrant。</p>
 *
 * @param toolName   工具名（ToolCallback.getToolDefinition().name()）
 * @param args       工具入参 JSON 字符串（截断到 500 字符，避免撑爆 Qdrant payload）
 * @param result     工具返回值（截断到 500 字符；失败时为异常 message）
 * @param success    是否调用成功
 * @param durationMs 调用耗时（毫秒）
 */
public record ToolCallLog(
        String toolName,
        String args,
        String result,
        boolean success,
        long durationMs
) {}
