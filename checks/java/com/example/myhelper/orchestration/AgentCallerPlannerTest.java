package com.example.myhelper.orchestration;

import com.example.myhelper.agent.AgentCaller;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 记录模型实际收到的合同，防止移动提示词时意外改动请求内容。 */
class AgentCallerPlannerTest {
    @Test
    void requestMatchesContractCapturedBeforeTextExtraction() throws Exception {
        RecordingModel model = new RecordingModel();
        AgentCaller caller = new AgentCaller(new Function<String, ChatModel>() {
            @Override
            public ChatModel apply(String reference) {
                return model;
            }
        }, List.of());
        AgentCallerPlanner planner = new AgentCallerPlanner(caller);
        planner.decide(new AgentOrchestrator.PlanningContext("run-fixed", "准备睡眠环境", Map.of("room", "卧室"),
                "test-model", List.of(), List.of(), List.of()));

        ObjectMapper json = new ObjectMapper();
        Map<String, Object> actual = new LinkedHashMap<>();
        actual.put("system", model.prompt.getInstructions().get(0).getText());
        // JSON 对象键顺序不属于合同；系统提示及 Schema 的字符串仍逐字符比较。
        actual.put("user", json.readTree(model.prompt.getInstructions().get(1).getText()));
        ToolCallingChatOptions options = (ToolCallingChatOptions) model.prompt.getOptions();
        List<Map<String, String>> definitions = new ArrayList<>();
        for (ToolCallback tool : options.getToolCallbacks()) {
            var definition = tool.getToolDefinition();
            definitions.add(Map.of("name", definition.name(), "description", definition.description(), "schema", definition.inputSchema()));
        }
        actual.put("tools", definitions);
        actual.put("internalExecution", options.getInternalToolExecutionEnabled());
        // 期望值来自重构前请求快照，不能从当前 LlmTexts 动态生成，否则无法发现文本变化。
        try (InputStream expected = getClass().getResourceAsStream("/llm/planner-request.json")) {
            assertNotNull(expected);
            assertEquals(json.readTree(expected), json.valueToTree(actual));
        }
    }

    private static final class RecordingModel implements ChatModel {
        // 保存模型实际收到的 Prompt，不依赖提示词类的具体实现。
        private Prompt prompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt = prompt;
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("finish-1", "function", "finish_task", "{\"kind\":\"COMPLETE\",\"output\":\"done\"}")))
                    .build())));
        }
    }
}
