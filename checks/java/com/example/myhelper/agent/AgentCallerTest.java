package com.example.myhelper.agent;

import com.example.myhelper.tasklog.TaskLogPort;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
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

    @Test void reportsActualModelBoundaryWithCorrelationAndAvailableUsage() {
        List<TaskLogPort.Event> events = new ArrayList<>();
        var context = new TaskLogPort.Context("task-1", "root-1", "run-1", "node-1", "branch-1", 2);
        ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("private response"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(4, 2, 6)).build());
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                assertEquals(1, events.size());
                assertEquals(TaskLogPort.Kind.MODEL_STARTED, events.getFirst().kind());
                return response;
            }
        };
        var result = observedCaller(model, events::add).call(textRequest(), List.of(),
                AgentCaller.Purpose.RECOVERY_PLANNING, context);

        assertSame(response, result.response());
        assertEquals(2, events.size());
        var finished = events.getLast();
        assertEquals(TaskLogPort.Kind.MODEL_RETURNED, finished.kind());
        assertEquals(context, finished.context());
        assertEquals("node-1", finished.correlationId());
        assertEquals(finished.correlationId(), events.getFirst().correlationId());
        assertTrue(finished.summary().contains("purpose=RECOVERY_PLANNING model=test-model durationMs="));
        assertTrue(finished.summary().contains("promptTokens=4 completionTokens=2 totalTokens=6"));
        assertFalse(events.toString().contains("private"));
    }

    @Test void reportsSafeFailureStageAndRetainsOriginalError() {
        List<TaskLogPort.Event> events = new ArrayList<>();
        var upstream = new IllegalStateException("private upstream error");
        var caller = observedCaller(new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) { throw upstream; }
        }, events::add);
        assertSame(upstream, assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() { caller.call(textRequest()); }
        }));
        assertEquals(2, events.size());
        assertEquals(TaskLogPort.Kind.MODEL_FAILED, events.getLast().kind());
        assertEquals(events.getFirst().correlationId(), events.getLast().correlationId());
        assertTrue(events.getLast().summary().contains("stage=CALL failure=IllegalStateException"));
        assertFalse(events.toString().contains("private"));
    }

    @Test void reportsResponseValidationFailureEvenWhenTheLogConsumerThrows() {
        List<TaskLogPort.Event> events = new ArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) { return new ChatResponse(List.of()); }
        };
        var caller = observedCaller(model, new TaskLogPort() {
            @Override
            public void publish(Event event) {
                events.add(event);
                throw new IllegalStateException("test log consumer failed");
            }
        });

        assertThrows(IllegalStateException.class, new Executable() {
            @Override
            public void execute() { caller.call(textRequest()); }
        });
        assertEquals(2, events.size());
        assertEquals(TaskLogPort.Kind.MODEL_FAILED, events.getLast().kind());
        assertTrue(events.getLast().summary().contains("stage=RESPONSE_VALIDATION"));
        assertFalse(events.getLast().summary().contains("Tokens"));
    }

    private static AgentCaller observedCaller(ChatModel model, TaskLogPort taskLog) {
        return new AgentCaller(new Function<String, ChatModel>() {
            @Override
            public ChatModel apply(String ref) { return model; }
        }, List.of(), taskLog);
    }

    private static AgentCaller.Request textRequest() {
        return new AgentCaller.Request("test-model", List.of(new UserMessage("private prompt")), List.of(),
                AgentCaller.Mode.CALL_ONLY);
    }
}
