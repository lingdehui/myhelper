package com.example.myhelper.service;

import com.example.myhelper.autogen.GeneratedToolRegistry;
import com.example.myhelper.common.AiResponseUtils;
import com.example.myhelper.memory.vector.episode.ToolCallLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

/**
 * ToolCallback 包装器：在每次工具调用前后打印日志，
 * 让用户能看到 AI 每一步在干什么。
 *
 * <p>同时把每次工具调用信息收集到 {@code collector}（List&lt;ToolCallLog&gt;），
 * 供 Episode 系统持久化为完整执行轨迹（ExpeL/MUSE 经验学习的最小单元）。</p>
 *
 * <p>collector 由调用方（{@code MyHelperApplication.processAITurn}）创建并传入，
 * 每个 AI turn 一个 collector，turn 结束后作为 Episode 的 toolCalls 字段写入 Qdrant。
 * args 和 result 截断到 500 字符，避免 OCR 全屏文字等长结果撑爆 Qdrant payload。</p>
 *
 * <p>集成生成工具自动清理：每次工具调用成功/失败时上报到 GeneratedToolRegistry，
 * 连续失败 3 次的生成工具会被自动删除（源码+运行时）。</p>
 */
public class LoggingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(LoggingToolCallback.class);

    private final ToolCallback delegate;
    /** 工具调用日志收集器（每个 AI turn 一个，由调用方传入） */
    private final List<ToolCallLog> collector;
    /** 生成工具注册中心（用于上报成功/失败，触发自动清理） */
    private final GeneratedToolRegistry generatedToolRegistry;

    public LoggingToolCallback(ToolCallback delegate, List<ToolCallLog> collector,
                                GeneratedToolRegistry generatedToolRegistry) {
        this.delegate = delegate;
        this.collector = collector;
        this.generatedToolRegistry = generatedToolRegistry;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        String name = delegate.getToolDefinition().name();

        // 只过滤"有必填参数"工具的空调用；无参工具（listWindows/getMousePosition 等）的空调用是合法的，放行执行
        if (hasRequiredParams(delegate.getToolDefinition())
                && (toolInput == null || toolInput.isBlank()
                    || toolInput.replaceAll("[{}\"\\s:,]", "").isEmpty())) {
            log.info("  ⏭️ [{}] 跳过（参数为空）", name);
            collector.add(new ToolCallLog(name, "(空)", "(已跳过)", true, 0));
            return "已跳过（参数为空）";
        }

        log.info("  🔧 [{}] 参数: {}", name, AiResponseUtils.truncate(toolInput, 300));

        long start = System.currentTimeMillis();
        try {
            String result = delegate.call(toolInput);
            long elapsed = System.currentTimeMillis() - start;
            // 检查返回字符串是否包含失败标识（工具自己catch了异常返回字符串的情况）
            boolean isActuallySuccess = !isFailureResultStr(result);
            log.info("     {} ({}ms) {}", isActuallySuccess ? "✅" : "❌", elapsed, AiResponseUtils.truncate(result, 300));
            // 收集到 Episode 轨迹
            collector.add(new ToolCallLog(name, AiResponseUtils.truncate(toolInput, 500),
                    AiResponseUtils.truncate(result, 500), isActuallySuccess, elapsed));
            // 生成工具：根据实际成败上报
            if (isActuallySuccess) {
                generatedToolRegistry.reportToolSuccess(name);
            } else {
                generatedToolRegistry.reportToolFailure(name);
            }
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.warn("     ❌ ({}ms) {}", elapsed, e.getMessage());
            // 失败也收集（result 字段存异常 message）
            collector.add(new ToolCallLog(name, AiResponseUtils.truncate(toolInput, 500),
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), false, elapsed));
            // 生成工具失败 → 递增失败计数，连续3次自动删除
            generatedToolRegistry.reportToolFailure(name);
            throw e;
        }
    }

    /** 判断工具是否有必填参数（用于区分无参工具的空调用与有参工具的误调用） */
    private static boolean hasRequiredParams(ToolDefinition def) {
        try {
            // Spring AI 2.0 的 ToolDefinition 不直接暴露 inputSchema，需反射获取
            Method m = def.getClass().getMethod("inputSchema");
            Object schema = m.invoke(def);
            if (schema instanceof Map) {
                Object required = ((Map<?, ?>) schema).get("required");
                return required instanceof List && !((List<?>) required).isEmpty();
            }
        } catch (Exception ignored) {
            // 反射失败则视为无必填参数，放行执行
        }
        return false;
    }

    /** 检查返回字符串是否包含失败标识（工具自己catch异常返回字符串的情况） */
    private static boolean isFailureResultStr(String result) {
        if (result == null || result.isBlank()) return false;
        return result.contains("失败") || result.contains("错误") 
                || result.contains("不存在") || result.contains("❌")
                || result.contains("error") || result.contains("Error");
    }
}
