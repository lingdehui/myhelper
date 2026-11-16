package com.example.myhelper.orchestration;

import com.example.myhelper.agent.AgentCaller;
import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.llm.ModelAdapter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** 把外层规划请求接到已有单次调用；模型通过 submit_plan 工具提交执行/递归任务计划。 */
public final class AgentCallerPlanner implements AgentOrchestrator.Planner {
    /** 模型提交计划时使用的协议工具名，不是执行图节点类型。 */
    public static final String SUBMIT_PLAN_TOOL = LlmTexts.SUBMIT_PLAN_TOOL;
    /** 模型返回最终答复/缺失信息时使用的协议工具名。 */
    public static final String FINISH_TASK_TOOL = LlmTexts.FINISH_TASK_TOOL;

    /** 已配置模型和默认工具的单次调用器。 */
    private final AgentCaller caller;
    /** 本适配器专用 JSON 解析器，拒绝未知字段以尽早发现模型协议错误。 */
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    /** 提交计划的工具定义，回调不会直接执行任务。 */
    private final ToolCallback submit;
    /** 结束当前任务或请求用户补充的工具定义。 */
    private final ToolCallback finish;

    // 准备本轮模型可见的编排工具，不把它们注册成实际业务动作。
    public AgentCallerPlanner(AgentCaller caller) {
        // 保存已配置好的单次调用器，不新建模型连接。
        this.caller = Objects.requireNonNull(caller);
        // 从统一文本类读取工具说明和 JSON Schema，避免在调用代码中散落提示文本。
        // required 列出必填字段，enum 限定 TOOL/TASK，bindings 声明参数的来源和路径。
        this.submit = controlTool(SUBMIT_PLAN_TOOL, LlmTexts.SUBMIT_PLAN_DESCRIPTION, LlmTexts.SUBMIT_PLAN_SCHEMA);
        // 建立结束工具；kind 只允许 COMPLETE/NEEDS_INPUT，output 承载答复或补充问题。
        this.finish = controlTool(FINISH_TASK_TOOL, LlmTexts.FINISH_TASK_DESCRIPTION, LlmTexts.FINISH_TASK_SCHEMA);
    }

    @Override
    // 规划协议始终使用 submit_plan/finish_task，与是否开放普通业务工具无关。
    public Set<ModelAdapter.Requirement> requirements() {
        // 把协议要求交给外层会话建立流程，使根和所有候选分支均支持工具调用。
        return Set.of(ModelAdapter.Requirement.TOOL_CALLING);
    }

    @Override
    // 调用一次模型，并把它的原生工具请求解析成运行时决策。
    public AgentOrchestrator.Decision decide(AgentOrchestrator.PlanningContext context) {
        try {
            String systemPrompt = LlmTexts.PLANNER_SYSTEM_PROMPT;
            if (!context.instructions().instructions().isEmpty()) {
                systemPrompt = systemPrompt + "\n" + LlmTexts.INSTRUCTION_PLANNING_PROMPT;
            }
            // 每次组织完整的结构化反馈快照，不把未配对的工具请求塞进下一次历史。
            String input = json.writeValueAsString(Map.of(LlmTexts.PLANNING_TASK_FIELD, context, LlmTexts.AVAILABLE_TOOLS_FIELD, caller.toolDefinitions(context.toolNames())));
            // 使用 CALL_ONLY 获取工具请求；控制工具由外层解释，不由框架执行。
            var result = caller.call(new AgentCaller.Request(context.modelRef(), List.of(
                    // 说明模型的规划职责；真正的线程调度和参数取值由代码执行。
                    new SystemMessage(systemPrompt), new UserMessage(input)), List.of(SUBMIT_PLAN_TOOL, FINISH_TASK_TOOL), AgentCaller.Mode.CALL_ONLY), List.of(submit, finish));
            // 直接读取结构化工具调用，不从模型自然语言文本中猜测动作。
            var calls = result.response().getResult().getOutput().getToolCalls();
            // 一次决策必须只有一个控制请求，避免同轮既提交计划又结束。
            if (calls.size() != 1) {
                // 明确报告当前状态不允许继续；不在这里自动重试。
                throw new IllegalStateException(LlmTexts.SINGLE_CONTROL_CALL_REQUIRED);
            }
            // 读取已经校验为唯一的工具请求。
            var call = calls.getFirst();
            // 根据固定协议工具名识别“提交计划”。
            if (call.name().equals(SUBMIT_PLAN_TOOL)) {
                // 把计划 JSON 解析为 TaskPlan，并生成 PLAN 决策。
                return new AgentOrchestrator.Decision(AgentOrchestrator.DecisionKind.PLAN,
                        // 读取工具 arguments 字段；计划构造器同时校验步骤和依赖。
                        json.readValue(call.arguments(), TaskPlan.class), null);
            }
            // 结束工具的参数解析为 JSON 对象，随后读取 kind 和 output。
            var decision = json.readTree(call.arguments());
            // 将协议中的类型名转成枚举；不认识的类型明确报错。
            var kind = AgentOrchestrator.DecisionKind.valueOf(decision.path("kind").asText());
            // 结束工具只允许完成或需要信息，并且必须携带 output。
            if (kind == AgentOrchestrator.DecisionKind.PLAN || !decision.has("output")) {
                // 抛出参数或协议错误，阻止无效输入继续进入执行。
                throw new IllegalArgumentException(LlmTexts.INVALID_FINISH_RESPONSE);
            }
            // 把模型的最终答复或补充问题作为 JSON 值传回编排器。
            return new AgentOrchestrator.Decision(kind, null, json.convertValue(decision.get("output"), Object.class));
        } catch (java.io.IOException failure) {
            // 抛出参数或协议错误，阻止无效输入继续进入执行。
            throw new IllegalArgumentException(LlmTexts.INVALID_PLAN_JSON, failure);
        }
    }

    // 只构造控制工具定义；真实任务调度由外层编排器接管。
    private static ToolCallback controlTool(String name, String description, String schema) {
        // 用匿名回调实现框架所需的工具接口。
        return new ToolCallback() {
            @Override
            // 向模型提供名称、用途和 JSON 参数结构。
            public ToolDefinition getToolDefinition() {
                // 生成 Spring AI 的工具合同，不在此执行动作。
                return ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
            }
            @Override
            // 若框架误执行控制工具，明确报错，避免同步递归造成线程等待。
            public String call(String input) {
                // 控制请求由编排器解释和调度，禁止框架同步递归执行。
                throw new IllegalStateException(LlmTexts.CONTROL_TOOL_RUNTIME_ONLY);
            }
        };
    }
}
