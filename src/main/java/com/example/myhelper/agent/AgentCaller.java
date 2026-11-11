package com.example.myhelper.agent;

import com.example.myhelper.llm.LlmTexts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.chat.model.ToolContext;

/** 单次模型调用及可选工具执行；下一轮由调用方组织。 */
public final class AgentCaller {
    // 模型解析函数：根据入参中的 modelRef 获取已配置的 ChatModel。
    private final Function<String, ChatModel> models;
    // 工具注册表：工具名对应一个可执行回调。
    private final Map<String, ToolCallback> tools;

    // 装配单次调用所用的模型解析器和全局工具注册表。
    public AgentCaller(Function<String, ChatModel> models, Collection<ToolCallback> tools) {
        // 保存外部传入的模型解析函数，不在此处创建模型或配置密钥。
        this.models = Objects.requireNonNull(models, "models");
        // 建立临时工具表，注册时检查名称冲突。
        Map<String, ToolCallback> registry = new LinkedHashMap<>();
        // 逐个读取调用方提供的工具。
        for (ToolCallback tool : tools) {
            // 从工具定义取出模型使用的工具名。
            String name = tool.getToolDefinition().name();
            // 同名工具不允许覆盖，避免执行到错误实现。
            if (registry.putIfAbsent(name, tool) != null) {
                // 将工具注册冲突明确报告给调用方。
                throw new IllegalArgumentException("重复工具名: " + name);
            }
        }
        // 保存不可修改的注册表，后续请求共用它。
        this.tools = Map.copyOf(registry);
    }

    // CALL_ONLY 只调用模型；CALL_AND_EXECUTE 还执行模型请求的工具。
    public enum Mode {
        /** 只调用模型；工具请求留给上层处理。 */
        CALL_ONLY,
        /** 调用一次模型，再执行这次响应中的工具。 */
        CALL_AND_EXECUTE
    }

    public record Request(
            /** 已配置模型的引用，由 models 解析。 */
            String modelRef,
            /** 本轮完整消息，含用户文本及上一轮工具结果或失败反馈。 */
            List<Message> messages,
            /** 本轮可用工具名；空列表表示不提供工具。 */
            List<String> toolNames,
            /** 是否执行本轮模型返回的工具调用。 */
            Mode mode) {
        // 校验本轮请求并固定消息、工具列表。
        public Request {
            // 要求指定本轮被调用的模型。
            if (modelRef == null || modelRef.isBlank()) {
                // 请求或工具协议不合法时明确报错，不进入实际调用。
                throw new IllegalArgumentException("modelRef 不能为空");
            }
            // 复制消息列表，避免外部增删影响请求或返回值。
            messages = List.copyOf(messages);
            // 没有消息时无法形成有效的本轮模型请求。
            if (messages.isEmpty()) {
                // 请求或工具协议不合法时明确报错，不进入实际调用。
                throw new IllegalArgumentException("messages 不能为空");
            }
            // 复制本轮可用工具名，上轮失败原因保留在 messages 中。
            toolNames = List.copyOf(toolNames);
            // 调用方必须明确是否执行工具，不隐式选择。
            Objects.requireNonNull(mode, "mode");
        }
    }

    public record Result(
            /** 原始模型响应，含文本与结构化工具调用。 */
            ChatResponse response,
            /** 可用于下一轮的消息；仅调用模式下，工具调用仍待调用方处理。 */
            List<Message> messages,
            /** 是否已进入工具执行阶段；不表示所有工具均成功。 */
            boolean executionAttempted) {
        // 结果消息列表只读，上层下一轮应创建新的消息列表。
        public Result {
            // 冻结结果历史；下一轮由上层另建消息列表。
            messages = List.copyOf(messages);
        }
    }

    // 兼容原有单次调用入口，不额外提供临时工具。
    public Result call(Request request) {
        // 原有入口保持兼容，没有本轮专用编排工具。
        return call(request, List.of());
    }

    /** 本轮临时工具只进入本次注册表，供外层编排使用，不污染其他并发请求。 */
    public Result call(Request request, Collection<ToolCallback> scopedTools) {
        // 先检查请求本身，避免空请求在读取模型引用时产生不明确的错误。
        Objects.requireNonNull(request, "request");
        // 每个调用使用自己的合并表，允许不同任务并发携带不同编排上下文。
        Map<String, ToolCallback> available = new LinkedHashMap<>(tools);
        // 逐个合并本次调用独有的编排工具，避免污染其他任务。
        for (ToolCallback tool : scopedTools) {
            // 禁止临时工具覆盖已有普通工具。
            if (available.putIfAbsent(tool.getToolDefinition().name(), tool) != null) {
                // 请求或工具协议不合法时明确报错，不进入实际调用。
                throw new IllegalArgumentException("重复本轮工具名: " + tool.getToolDefinition().name());
            }
        }
        // 用本轮模型引用获取模型；解析失败时不继续执行。
        ChatModel model = Objects.requireNonNull(models.apply(request.modelRef()), "未找到模型: " + request.modelRef());
        // 去重本轮工具名，并解析成 Spring AI 可识别的工具定义与回调。
        List<ToolCallback> callbacks = new ArrayList<>();
        // 保留首次出现的顺序，同名工具只添加一次。
        Set<String> resolvedNames = new HashSet<>();
        for (String name : request.toolNames()) {
            if (!resolvedNames.add(name)) {
                continue;
            }
            // 从本次可用工具表查找实际实现。
            ToolCallback tool = available.get(name);
            if (tool == null) {
                throw new IllegalArgumentException(LlmTexts.UNREGISTERED_TOOL_PREFIX + name);
            }
            // 失败仍转为原来的工具反馈，不在这里重试。
            callbacks.add(feedbackCallback(tool));
        }
        // 创建本轮模型调用选项，使用项目锁定的 Spring AI 2.0.0-M3 API。
        var options = ToolCallingChatOptions.builder()
                // 只提供本轮工具；禁止框架自动执行和继续调用模型，下面自行按模式执行。
                .toolCallbacks(Collections.unmodifiableList(callbacks)).internalToolExecutionEnabled(false).build();
        // 将当前文本、历史反馈和工具选项组成请求。
        Prompt prompt = new Prompt(request.messages(), options);
        // 仅在这一行调用一次 LLM；本方法后面不会再调用模型。
        ChatResponse response = Objects.requireNonNull(model.call(prompt), LlmTexts.EMPTY_MODEL_RESPONSE);
        // 本模块只处理一个候选响应，不擅自选择或执行多个候选。
        if (response.getResults().size() != 1) {
            // 候选数不符合约定时抛出协议错误。
            throw new IllegalStateException(LlmTexts.SINGLE_RESPONSE_REQUIRED);
        }
        // 复制输入历史，为返回值追加本轮响应。
        List<Message> history = new ArrayList<>(request.messages());
        // 保留模型文本及工具调用编号、名称和参数。
        history.add(response.getResult().getOutput());
        // 本轮只允许执行入参指定的工具。
        Set<String> allowed = new HashSet<>(request.toolNames());
        // 调用 ID 必须在本响应中唯一，才能正确配对工具结果。
        Set<String> callIds = new HashSet<>();
        // 整批预检，避免先执行部分工具后才发现越界调用。
        for (var call : response.getResult().getOutput().getToolCalls()) {
            // CALL_ONLY 同样需要协议预检，不能将错误调用交给外层执行。
            if (call.id() == null || call.id().isBlank() || !callIds.add(call.id())) {
                // 请求或工具协议不合法时明确报错，不进入实际调用。
                throw new IllegalArgumentException(LlmTexts.INVALID_TOOL_CALL_ID);
            }
            // 模型请求了本轮未提供的工具时拒绝整个批次。
            if (!allowed.contains(call.name())) {
                // 报告越界调用，不执行它。
                throw new IllegalArgumentException(LlmTexts.UNAVAILABLE_TOOL_REQUESTED_PREFIX + call.name());
            }
        }
        // 仅调用模式或无工具调用时，预检后直接返回，不执行任何工具。
        if (request.mode() == Mode.CALL_ONLY || !response.hasToolCalls()) {
            // 返回模型响应；false 表示没有尝试执行工具，不等于模型没有请求工具。
            return new Result(response, history, false);
        }
        // 构造 Spring AI 工具执行器，它负责执行工具而不是再次请求模型。
        var manager = DefaultToolCallingManager.builder()
                // 工具解析同样限定在本轮允许集合内。
                .toolCallbackResolver(new ToolCallbackResolver() {
                    @Override
                    public ToolCallback resolve(String name) {
                        if (!allowed.contains(name)) {
                            return null;
                        }
                        return available.get(name);
                    }
                })
                // 框架包装的执行异常转成工具响应，交给上层处理，本模块不重试。
                .toolExecutionExceptionProcessor(new ToolExecutionExceptionProcessor() {
                    @Override
                    public String process(ToolExecutionException exception) {
                        return LlmTexts.TOOL_EXECUTION_FAILED_PREFIX + exception.getMessage();
                    }
                })
                // 完成执行器配置。
                .build();
        // 根据模型响应中的工具名和参数执行工具，不调用 LLM。
        var execution = manager.executeToolCalls(prompt, response);
        // 返回包含工具结果的完整消息，下一轮由上层发起。
        return new Result(response, execution.conversationHistory(), true);
    }

    /** 供规划器读取默认工具合同，不暴露可修改注册表。 */
    public List<ToolDefinition> toolDefinitions(List<String> names) {
        // 去重所需工具名，并逐个读取其公开合同。
        List<ToolDefinition> definitions = new ArrayList<>();
        // 去重时保留输入顺序，与原来的 distinct 一致。
        Set<String> resolvedNames = new HashSet<>();
        for (String name : names) {
            if (!resolvedNames.add(name)) {
                continue;
            }
            ToolCallback tool = tools.get(name);
            if (tool == null) {
                throw new IllegalArgumentException(LlmTexts.UNREGISTERED_TOOL_PREFIX + name);
            }
            definitions.add(tool.getToolDefinition());
        }
        // 原 Stream.toList 返回只读列表，继续保留这一合同及其 null 元素行为。
        return Collections.unmodifiableList(definitions);
    }

    // 给普通工具增加异常转反馈的适配，单次调用本身不自动重试。
    private static ToolCallback feedbackCallback(ToolCallback delegate) {
        // 使用代理包装现有工具，不改变工具定义。
        return new ToolCallback() {
            @Override
            // 把被包装工具的原始合同提供给框架。
            public ToolDefinition getToolDefinition() {
                // 保持原工具名称、说明和参数 JSON Schema。
                return delegate.getToolDefinition();
            }
            @Override
            // 转交工具执行元数据，不在代理里修改原语义。
            public ToolMetadata getToolMetadata() {
                // 保持原工具的执行元数据。
                return delegate.getToolMetadata();
            }
            @Override
            // 不带上下文的调用使用空工具上下文。
            public String call(String input) {
                // 没有额外上下文时，转到带空上下文的执行入口。
                return call(input, new ToolContext(Map.of()));
            }
            @Override
            // 带上下文的调用把参数和上下文交给原工具。
            public String call(String input, ToolContext context) {
                // 捕获实际工具运行产生的异常。
                try {
                    // 将模型生成的参数和工具上下文传给原工具。
                    return delegate.call(input, context);
                // 运行失败时只生成反馈，不在此处重试。
                } catch (RuntimeException failure) {
                    // 返回错误原因；这段文本表示失败，不代表工具执行成功。
                    return LlmTexts.TOOL_EXECUTION_FAILED_PREFIX + failure.getMessage();
                }
            }
        };
    }
}

