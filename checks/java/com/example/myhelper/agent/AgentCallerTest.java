package com.example.myhelper.agent;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import static org.junit.jupiter.api.Assertions.*;

class AgentCallerTest {
    private final AtomicInteger modelCalls = new AtomicInteger();
    private final AtomicInteger toolCalls = new AtomicInteger();

    private AgentCaller caller(boolean fail, String requestedTool) {
        ToolCallback tool = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("echo").description("echo")
                        .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }
            public String call(String input) {
                toolCalls.incrementAndGet();
                if (fail) throw new IllegalStateException("test failure");
                return "done";
            }
        };
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                modelCalls.incrementAndGet();
                assertFalse(((ToolCallingChatOptions) prompt.getOptions()).getInternalToolExecutionEnabled());
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", requestedTool, "{}")))
                        .build())));
            }
        };
        return new AgentCaller(new Function<String, ChatModel>() {
            @Override
            public ChatModel apply(String ref) {
                assertEquals("test-model", ref);
                return model;
            }
        }, List.of(tool));
    }

    private AgentCaller.Request request(AgentCaller.Mode mode) {
        return new AgentCaller.Request("test-model", List.of(new UserMessage("执行")), List.of("echo"), mode);
    }

    @Test void callOnlyLeavesToolsPending() {
        var result = caller(false, "echo").call(request(AgentCaller.Mode.CALL_ONLY));
        assertEquals(1, modelCalls.get());
        assertEquals(0, toolCalls.get());
        assertFalse(result.executionAttempted());
        assertTrue(result.response().hasToolCalls());
    }

    @Test void executesWithoutSecondModelCall() {
        var result = caller(false, "echo").call(request(AgentCaller.Mode.CALL_AND_EXECUTE));
        assertEquals(1, modelCalls.get());
        assertEquals(1, toolCalls.get());
        var message = (ToolResponseMessage) result.messages().getLast();
        assertEquals("call-1", message.getResponses().getFirst().id());
        assertEquals("done", message.getResponses().getFirst().responseData());
    }

    @Test void failureBecomesFeedbackWithoutRetry() {
        var result = caller(true, "echo").call(request(AgentCaller.Mode.CALL_AND_EXECUTE));
        assertEquals(1, modelCalls.get());
        assertEquals(1, toolCalls.get());
        var message = (ToolResponseMessage) result.messages().getLast();
        assertTrue(message.getResponses().getFirst().responseData().contains("工具执行失败"));
    }

    @Test void rejectsUnadvertisedToolBeforeExecution() {
        var caller = caller(false, "other");
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                caller.call(request(AgentCaller.Mode.CALL_AND_EXECUTE));
            }
        });
        assertEquals(0, toolCalls.get());
    }

    @Test void callOnlyAlsoRejectsUnadvertisedTool() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                caller(false, "other").call(request(AgentCaller.Mode.CALL_ONLY));
            }
        });
        assertEquals(0, toolCalls.get());
    }

    @Test void rejectsDuplicateCallIdsEvenWithoutExecution() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("same", "function", "echo", "{}"),
                        new AssistantMessage.ToolCall("same", "function", "echo", "{}"))).build())));
            }
        };
        ToolCallback echo = new ToolCallback() {
            public ToolDefinition getToolDefinition() { return ToolDefinition.builder().name("echo").description("echo").inputSchema("{\"type\":\"object\"}").build(); }
            public String call(String input) { fail("不应执行"); return null; }
        };
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() throws Throwable {
                new AgentCaller(new Function<String, ChatModel>() {
                    @Override
                    public ChatModel apply(String ref) {
                        return model;
                    }
                }, List.of(echo)).call(request(AgentCaller.Mode.CALL_ONLY));
            }
        });
    }
}
