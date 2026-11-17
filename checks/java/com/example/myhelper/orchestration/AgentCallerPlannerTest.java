package com.example.myhelper.orchestration;

import com.example.myhelper.agent.AgentCaller;
import com.example.myhelper.records.TaskInstructionPort;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.skill.ToolReliability.PlanningReference;
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
import org.springframework.ai.tool.definition.ToolDefinition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 比较实际请求与已确认协议快照，防止提示词或 Schema 意外偏离。 */
class AgentCallerPlannerTest {
    @Test
    void requestMatchesReviewedPlanningContract() throws Exception {
        RecordingModel model = new RecordingModel();
        AgentCaller caller = new AgentCaller(new Function<String, ChatModel>() {
            @Override
            public ChatModel apply(String reference) {
                return model;
            }
        }, List.of(new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("read_temperature").description("读取室温").inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                throw new AssertionError("规划只读取工具定义");
            }
        }));
        AgentCallerPlanner planner = new AgentCallerPlanner(caller);
        planner.decide(new AgentOrchestrator.PlanningContext("run-fixed", "准备睡眠环境", Map.of("room", "卧室"),
                "test-model", List.of("read_temperature"), List.of(), List.of(), "run-fixed",
                AgentOrchestrator.PlanningPhase.NORMAL, TaskRecordPort.RecoverySnapshot.empty(),
                TaskInstructionPort.Snapshot.empty(), Map.of(
                        "read_temperature", List.of(new PlanningReference("indoor", 0.92, 12, false)),
                        "restricted_tool", List.of(new PlanningReference("private", 1.0, 5, true)))));

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
        // 从独立快照核对提示、允许工具及其可靠度，排除未授权的分数。
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
