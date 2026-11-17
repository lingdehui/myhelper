package com.example.myhelper.interaction.desktop.behavior;

import com.example.myhelper.agent.AgentCaller;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Action;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Color;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Context;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Outcome;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Pattern;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Pose;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Prop;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Slot;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Style;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Type;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Workspace;
import com.example.myhelper.tasklog.TaskLogPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ModelBehaviorPlannerTest {
    /** 模型调用和许可都在同一检查线程中执行。 */
    private static final BooleanSupplier ALLOW = new BooleanSupplier() {
        @Override
        public boolean getAsBoolean() {
            return true;
        }
    };
    /** 空闲工作区的无道具快照。 */
    private static final Context EMPTY = new Context(new Workspace(1600, 900, 1250, 645), Map.of(), List.of(), Outcome.NONE);
    /** 设计稿中的创建后看向动作。 */
    private static final String CREATE_AND_LOOK = """
            {"actions":[
              {"type":"CREATE_PROP","style":"CUP","color":"LAVENDER","slot":"RIGHT","durationMs":1200},
              {"type":"LOOK_AT_SLOT","slot":"RIGHT","durationMs":900}
            ]}
            """;

    @Test
    void acceptsOrderedPropCreationAndSendsOnlyBehaviorContext() throws Exception {
        var response = new ModelResponse(CREATE_AND_LOOK);
        var log = new ArrayList<TaskLogPort.Event>();
        var recent = new Action(Type.POSE, Pose.ALERT, null, null, 900);
        var context = new Context(EMPTY.workspace(), Map.of(Slot.LEFT, new Prop(Style.BOOK, Color.BLUE, Pattern.DOTS)),
                List.of(recent), Outcome.INTERRUPTED);
        BehaviorPlan plan = planner(response, log::add).plan(context, ALLOW);

        assertEquals(List.of(
                new Action(Type.CREATE_PROP, null, Slot.RIGHT, new Prop(Style.CUP, Color.LAVENDER, Pattern.PLAIN), 1200),
                new Action(Type.LOOK_AT_SLOT, null, Slot.RIGHT, null, 900)), plan.actions());
        assertEquals(1, response.calls);
        assertEquals(2, response.prompt.getInstructions().size());
        assertInstanceOf(SystemMessage.class, response.prompt.getInstructions().getFirst());
        assertInstanceOf(UserMessage.class, response.prompt.getInstructions().getLast());
        JsonNode input = new ObjectMapper().readTree(response.prompt.getInstructions().getLast().getText());
        assertEquals(4, input.size());
        assertEquals(1250, input.path("workspace").path("x").intValue());
        assertEquals("DOTS", input.path("props").path("LEFT").path("pattern").textValue());
        assertEquals("ALERT", input.path("recentActions").get(0).path("pose").textValue());
        assertEquals("INTERRUPTED", input.path("lastOutcome").textValue());
        var options = (ToolCallingChatOptions) response.prompt.getOptions();
        assertTrue(options.getToolCallbacks().isEmpty());
        assertFalse(options.getInternalToolExecutionEnabled());
        assertEquals(List.of(TaskLogPort.Kind.MODEL_STARTED, TaskLogPort.Kind.MODEL_RETURNED),
                List.of(log.getFirst().kind(), log.getLast().kind()));
        for (TaskLogPort.Event event : log) {
            assertNull(event.context());
            assertTrue(event.summary().contains("purpose=DESKTOP_BEHAVIOR"));
        }
    }

    @Test
    void checksDispatchAfterModelResolutionAndDoesNotLogDeniedCall() {
        var response = new ModelResponse(CREATE_AND_LOOK);
        var log = new ArrayList<TaskLogPort.Event>();
        var gate = new DispatchGate(response);
        var planner = new ModelBehaviorPlanner(new AgentCaller(gate, List.of(), log::add), "desktop-model");
        assertNull(planner.plan(EMPTY, gate));
        assertEquals(1, gate.checks);
        assertEquals(0, response.calls);
        assertTrue(log.isEmpty());
    }

    @Test
    void validatesEntirePlanInOrderAgainstCurrentSnapshot() {
        var occupied = new Context(EMPTY.workspace(),
                Map.of(Slot.RIGHT, new Prop(Style.BOOK, Color.BLUE, Pattern.DOTS)), List.of(), Outcome.NONE);
        assertThrows(IllegalArgumentException.class, new InvalidPlan(CREATE_AND_LOOK, occupied));
        assertThrows(IllegalArgumentException.class, new InvalidPlan("""
                {"actions":[
                  {"type":"CREATE_PROP","style":"CUP","color":"LAVENDER","slot":"RIGHT","durationMs":1200},
                  {"type":"HIDE_PROP","slot":"RIGHT","durationMs":900},
                  {"type":"LOOK_AT_SLOT","slot":"RIGHT","durationMs":900}
                ]}
                """, EMPTY));

        String replacement = """
                {"actions":[
                  {"type":"HIDE_PROP","slot":"RIGHT","durationMs":1000},
                  {"type":"CREATE_PROP","style":"STAR","color":"PINK","pattern":"STRIPES","slot":"RIGHT","durationMs":1000},
                  {"type":"SHOW_PROP","slot":"RIGHT","durationMs":1000}
                ]}
                """;
        var valid = planner(new ModelResponse(replacement), TaskLogPort.disabled()).plan(occupied, ALLOW);
        assertEquals(Pattern.STRIPES, valid.actions().get(1).prop().pattern());
        assertEquals(Style.BOOK, occupied.props().get(Slot.RIGHT).style());
    }

    @Test
    void rejectsExtraFieldsDuplicateKeysMalformedValuesAndUnboundedPlans() {
        String pose = """
                {"type":"POSE","pose":"REST","durationMs":6000}
                """;
        List<String> rejected = List.of(
                "",
                " ".repeat(4096) + "{}",
                CREATE_AND_LOOK + "{}",
                """
                {"actions":[],"actions":[{"type":"POSE","pose":"REST","durationMs":1}]}
                """,
                """
                {"actions":[{"type":"MOVE_TO_ANCHOR","slot":"LEFT","durationMs":1,"x":999}]}
                """,
                """
                {"actions":[{"type":"CREATE_PROP","style":"STAR","color":"PINK","pattern":null,"slot":"RIGHT","durationMs":1}]}
                """,
                """
                {"actions":[{"type":"POSE","pose":1,"durationMs":10}]}
                """,
                """
                {"actions":[{"type":"POSE","pose":"REST","durationMs":1.0}]}
                """,
                """
                {"actions":[{"type":"POSE","pose":"REST","durationMs":0}]}
                """,
                """
                {"actions":[{"type":"SHELL","durationMs":100}]}
                """,
                """
                {"actions":[{"type":"MOVE_TO_ANCHOR","slot":"TOP","durationMs":1}]}
                """,
                "{\"actions\":[" + String.join(",", List.of(pose, pose, pose)) + "]}",
                "{\"actions\":[" + String.join(",", List.of(pose, pose, pose, pose)) + "]}");
        for (String output : rejected) {
            assertThrows(IllegalArgumentException.class, new InvalidPlan(output, EMPTY), output);
        }
    }

    @Test
    void refusesUnexpectedNativeToolCall() {
        var event = AssistantMessage.builder().content(CREATE_AND_LOOK)
                .toolCalls(List.of(new AssistantMessage.ToolCall("1", "function", "unknown", "{}"))).build();
        assertThrows(IllegalArgumentException.class, new InvalidPlan(event, EMPTY));
    }

    private static ModelBehaviorPlanner planner(ModelResponse model, TaskLogPort log) {
        return new ModelBehaviorPlanner(new AgentCaller(new DispatchGate(model), List.of(), log), "desktop-model");
    }

    private static final class DispatchGate implements Function<String, ChatModel>, BooleanSupplier {
        /** 替身模型。 */
        private final ModelResponse model;
        /** 解析后才可做最后一次许可判断。 */
        private boolean resolved;
        /** 本轮许可判断次数。 */
        private int checks;

        private DispatchGate(ModelResponse model) {
            this.model = model;
        }

        @Override
        public ChatModel apply(String modelRef) {
            assertEquals("desktop-model", modelRef);
            resolved = true;
            return model;
        }

        @Override
        public boolean getAsBoolean() {
            assertTrue(resolved);
            checks++;
            return false;
        }
    }

    private static final class ModelResponse implements ChatModel {
        /** 预置响应，包含文本或故意越界的工具调用。 */
        private final AssistantMessage output;
        /** 请求次数。 */
        private int calls;
        /** 收到的请求快照。 */
        private Prompt prompt;

        private ModelResponse(String output) {
            this(new AssistantMessage(output));
        }

        private ModelResponse(AssistantMessage output) {
            this.output = output;
        }

        @Override
        public ChatResponse call(Prompt received) {
            calls++;
            prompt = received;
            return new ChatResponse(List.of(new Generation(output)));
        }
    }

    private record InvalidPlan(
            /** 预计被拒绝的模型内容。 */
            AssistantMessage output,
            /** 用于检查占用槽位的快照。 */
            Context context) implements Executable {
        private InvalidPlan(String output, Context context) {
            this(new AssistantMessage(output), context);
        }

        @Override
        public void execute() {
            planner(new ModelResponse(output), TaskLogPort.disabled()).plan(context, ALLOW);
        }
    }
}
