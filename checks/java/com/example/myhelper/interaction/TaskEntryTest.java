package com.example.myhelper.interaction;

import com.example.myhelper.agent.AgentCaller;
import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.gate.RuntimeGate;
import com.example.myhelper.llm.ConfiguredModelAdapter;
import com.example.myhelper.orchestration.AgentCallerPlanner;
import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.orchestration.ExecutionTraceStore;
import com.example.myhelper.orchestration.SpringToolExecutor;
import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.orchestration.ToolReliabilityGate;
import com.example.myhelper.orchestration.WorldContextPort;
import com.example.myhelper.capability.CapabilityUsePort;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.tasklog.TaskLogPort;
import com.example.myhelper.tools.DeviceStateTool;
import com.example.myhelper.tools.HomeAssistantStateReader;
import com.example.myhelper.tools.ToolDirectory;
import com.example.myhelper.world.WorldDeviceStatus;
import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.UnitType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskEntryTest {
    @ParameterizedTest
    @ValueSource(strings = {"", " /exit  \n这行不应提交\n"})
    void consoleDrainsIndependentRequestsAtEofOrExit(String ending) throws Exception {
        try (Fixture fixture = new Fixture()) {
            StringWriter output = new StringWriter();
            String input = " \t\n  第一条任务  \n第二条任务\n" + ending;
            new ConsoleInput(fixture.entry, new BufferedReader(new StringReader(input)),
                    new PrintWriter(output)).run();

            String reports = output.toString();
            Matcher receipts = Pattern.compile("(?m)^\\[([^]]+)] 已提交$").matcher(reports);
            assertTrue(receipts.find(), reports);
            String firstId = receipts.group(1);
            assertTrue(receipts.find(), reports);
            String secondId = receipts.group(1);
            assertNotEquals(firstId, secondId);
            assertTrue(reports.contains("[" + firstId + "] SUCCESS" + System.lineSeparator() + "结果：  第一条任务  "));
            assertTrue(reports.contains("[" + secondId + "] SUCCESS" + System.lineSeparator() + "结果：第二条任务"));
            assertEquals(2, fixture.model.goals.size());
        }
    }

    @Test
    void runtimeInputAdapterUsesTheSameTextAndResultContract() throws Exception {
        try (Fixture fixture = new Fixture()) {
            assertThrows(IllegalArgumentException.class, new SubmitText(fixture.entry, " \t"));
            // 应用启动后才获得入口的适配器可以提交最终转写，读取同一种任务结果。
            var submitted = fixture.entry.submit("语音最终转写");
            var outcome = submitted.settled().get(5, TimeUnit.SECONDS);
            assertEquals(AgentOrchestrator.Status.SUCCESS, outcome.status());
            assertEquals("结果：语音最终转写", outcome.value());
            assertEquals(List.of("语音最终转写"), fixture.model.goals);

            fixture.entry.close();
            assertThrows(IllegalStateException.class, new SubmitText(fixture.entry, "关闭后的转写"));
        }
    }

    @Test
    void registeredToolExecutesThroughApplicationRuntime() throws Exception {
        EchoTool echo = new EchoTool();
        var ref = new ToolDirectory.ToolRef("echo", "local-check", "1");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("binding", new ToolDirectory.Binding(ref, echo));
        beans.registerSingleton("directory", new ToolDirectory(List.of(
                new ToolDirectory.Registration(ref, "local-check", false, false, true))));
        beans.registerSingleton("gate", new EchoGate());
        beans.registerSingleton("resources", new EchoResources());
        CapturedTaskLog taskLog = new CapturedTaskLog();
        beans.registerSingleton("taskLog", taskLog);

        ReplyModel model = new ReplyModel(true);
        var models = new ConfiguredModelAdapter(List.of(
                new ConfiguredModelAdapter.ModelConfig("local-check", model, true, true)));
        var config = new TaskRuntimeConfiguration();
        var environment = new StandardEnvironment();
        var traces = config.taskExecutionTraces(environment);
        var tools = config.taskRuntimeTools(new AgentCaller(models, List.of()), models,
                beans.getBeanProvider(ToolDirectory.Binding.class), beans.getBeanProvider(ToolDirectory.class),
                beans.getBeanProvider(RuntimeGate.class), beans.getBeanProvider(SpringToolExecutor.ResourceResolver.class),
                beans.getBeanProvider(TaskLogPort.class));
        var workers = config.taskWorkers(environment);
        workers.initialize();
        var timer = config.taskTimer();
        try {
            var orchestrator = config.agentOrchestrator(tools, models, traces, workers, timer,
                    config.taskActivity(), environment,
                    beans.getBeanProvider(CapabilitySearchPort.class), beans.getBeanProvider(CapabilityUsePort.class),
                    beans.getBeanProvider(TaskRecordPort.class), beans.getBeanProvider(ToolReliabilityGate.class),
                    beans.getBeanProvider(TaskLogPort.class), beans.getBeanProvider(WorldContextPort.class));
            try (var entry = new TaskEntry(orchestrator, "local-check", tools.names(), traces)) {
                var handle = entry.submit("执行回声工具");
                var outcome = handle.settled().get(5, TimeUnit.SECONDS);
                assertEquals(AgentOrchestrator.Status.SUCCESS, outcome.status());
                assertEquals("回声完成", outcome.value());
                assertEquals("{\"value\":\"你好\"}", echo.arguments.get());
                assertEquals(List.of("执行回声工具", "执行回声工具"), model.goals);
                assertEquals(TaskLogPort.Kind.RUN_STARTED, taskLog.events.getFirst().kind());
                assertEquals(TaskLogPort.Kind.RUN_ENDED, taskLog.events.getLast().kind());
                assertEquals(taskLog.first(TaskLogPort.Kind.PLAN_CALL_PENDING).context(),
                        taskLog.first(TaskLogPort.Kind.MODEL_STARTED).context());
                assertEquals(handle.rootRunId(), taskLog.first(TaskLogPort.Kind.TOOL_RETURNED).context().rootRunId());
                assertEquals(taskLog.first(TaskLogPort.Kind.TOOL_INTENT).context(),
                        taskLog.first(TaskLogPort.Kind.TOOL_RETURNED).context());
                assertFalse(taskLog.events.toString().contains("你好"));
                assertFalse(taskLog.events.toString().contains("执行回声工具"));
            }
        } finally {
            timer.shutdownNow();
            workers.destroy();
        }
    }

    @Test
    void deviceToolRequiresTheDeviceGateAtApplicationAssembly() {
        var ref = new ToolDirectory.ToolRef("device-power", "local-check", "1");
        var sensor = new WorldDeviceStatus.Sensor(new EntityRef("sensor:power", 1L, null, null, UnitType.TOOL, null),
                "local-check");
        var tool = new DeviceStateTool(ref, sensor, List.of(new DeviceStateTool.Mapping("ac", "climate.ac")),
                new NoDeviceReads());
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("binding", tool.binding());
        beans.registerSingleton("directory", new ToolDirectory(List.of(
                new ToolDirectory.Registration(ref, "local-check", false, false, true))));
        beans.registerSingleton("gate", new EchoGate());
        beans.registerSingleton("resources", new EchoResources());
        var models = new ConfiguredModelAdapter(List.of(
                new ConfiguredModelAdapter.ModelConfig("local-check", new ReplyModel(false), true, true)));
        var failure = assertThrows(IllegalStateException.class, new AssembleRuntime(beans, models));
        assertEquals("启用设备状态工具需要为其配置设备读取门禁", failure.getMessage());
    }

    private record AssembleRuntime(DefaultListableBeanFactory beans, ConfiguredModelAdapter models) implements Executable {
        @Override
        public void execute() {
            new TaskRuntimeConfiguration().taskRuntimeTools(new AgentCaller(models, List.of()), models,
                    beans.getBeanProvider(ToolDirectory.Binding.class), beans.getBeanProvider(ToolDirectory.class),
                    beans.getBeanProvider(RuntimeGate.class), beans.getBeanProvider(SpringToolExecutor.ResourceResolver.class),
                    beans.getBeanProvider(TaskLogPort.class));
        }
    }

    private static final class NoDeviceReads implements DeviceStateTool.Reader {
        @Override
        public HomeAssistantStateReader.Reading read(String entityId, Instant deadline) {
            throw new AssertionError("装配不得读取设备");
        }
    }

    private static final class CapturedTaskLog implements TaskLogPort {
        /** 收集并发工作线程实际发给已装配端口的摘要。 */
        private final List<Event> events = new CopyOnWriteArrayList<>();

        @Override
        public void publish(Event event) {
            events.add(event);
        }

        private Event first(Kind kind) {
            for (Event event : events) {
                if (event.kind() == kind) {
                    return event;
                }
            }
            throw new AssertionError("缺少事件 " + kind);
        }
    }

    private static final class Fixture implements AutoCloseable {
        /** 真实编排器管理的工作线程和超时线程。 */
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        /** 只处理任务截止时间的线程。 */
        private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        /** 模型边界使用替身，其余路径使用正式组件。 */
        private final ReplyModel model = new ReplyModel(false);
        /** 模拟应用在进程启动时提供给后接入适配器的共用入口。 */
        private final TaskEntry entry;

        private Fixture() {
            var adapter = new ConfiguredModelAdapter(List.of(
                    new ConfiguredModelAdapter.ModelConfig("local-check", model, true, true)));
            var caller = new AgentCaller(adapter, List.of());
            var limits = new AgentOrchestrator.Limits(2, 2, 4, 4, 8, 8, Duration.ofSeconds(5));
            var traces = new ExecutionTraceStore(8, 32, 64);
            var orchestrator = new AgentOrchestrator(new AgentCallerPlanner(caller), CapabilitySearchPort.disabled(),
                    new SpringToolExecutor(List.of(), new EmptyToolResources(), 1), new TextResultGate(), traces, workers, timer,
                    limits, adapter);
            entry = new TaskEntry(orchestrator, "local-check", List.of(), traces);
        }

        @Override
        public void close() {
            entry.close();
            workers.shutdownNow();
            timer.shutdownNow();
        }
    }

    private static final class ReplyModel implements ChatModel {
        /** 记录发给模型的真实目标，包括用户原文两侧的空格。 */
        private final List<String> goals = new CopyOnWriteArrayList<>();
        /** 解析真实规划请求并构造本地协议响应。 */
        private final ObjectMapper json = new ObjectMapper();
        /** 工具检查需要先提交一步计划，再从模型侧读取工具回执。 */
        private final boolean planTool;

        private ReplyModel(boolean planTool) {
            this.planTool = planTool;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            try {
                String input = prompt.getInstructions().getLast().getText();
                String goal = json.readTree(input).path("task").path("goal").asText();
                goals.add(goal);
                if (planTool && goals.size() == 1) {
                    assertTrue(input.contains("\"toolName\":\"echo\"") || input.contains("\"name\":\"echo\""), input);
                    var step = new TaskPlan.Step("echo-step", "回声", TaskPlan.Kind.TOOL, "echo",
                            Map.of("value", "你好"), List.of(), Map.of());
                    return reply("submit_plan", new TaskPlan(goal, List.of(step)));
                }
                if (planTool) {
                    assertTrue(input.contains("回声工具返回值"), input);
                }
                return reply("finish_task", Map.of("kind", "COMPLETE", "output", planTool ? "回声完成" : "结果：" + goal));
            } catch (JsonProcessingException failure) {
                throw new IllegalStateException(failure);
            }
        }

        private ChatResponse reply(String name, Object arguments) throws JsonProcessingException {
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + goals.size(), "function", name,
                            json.writeValueAsString(arguments))))
                    .build())));
        }
    }

    private static final class TextResultGate implements RuntimeGate {
        @Override
        public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) {
            return false;
        }

        @Override
        public boolean verify(String goal, Map<String, Object> args, Object output) {
            return ("结果：" + goal).equals(output);
        }
    }

    private static final class EmptyToolResources implements SpringToolExecutor.ResourceResolver {
        @Override
        public List<String> keys(String tool, Map<String, Object> arguments) {
            throw new AssertionError("文本任务不占用工具资源");
        }
    }

    private static final class EchoTool implements ToolCallback {
        /** 收到的真实调用参数。 */
        private final AtomicReference<String> arguments = new AtomicReference<>();

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name("echo").description("检查用回声").inputSchema("{\"type\":\"object\"}").build();
        }

        @Override
        public String call(String input) {
            assertTrue(arguments.compareAndSet(null, input));
            return "{\"text\":\"回声工具返回值\"}";
        }
    }

    private static final class EchoGate implements RuntimeGate {
        @Override
        public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) {
            return goal.equals("回声") && args.equals(Map.of("value", "你好")) && ref == null;
        }

        @Override
        public boolean verify(String goal, Map<String, Object> args, Object output) {
            return (goal.equals("回声") && Map.of("text", "回声工具返回值").equals(output))
                    || (goal.equals("执行回声工具") && "回声完成".equals(output));
        }
    }

    private static final class EchoResources implements SpringToolExecutor.ResourceResolver {
        @Override
        public List<String> keys(String tool, Map<String, Object> arguments) {
            assertEquals("echo", tool);
            assertEquals(Map.of("value", "你好"), arguments);
            return List.of("echo-check");
        }
    }

    private record SubmitText(TaskEntry entry, String text) implements Executable {
        @Override
        public void execute() {
            entry.submit(text);
        }
    }
}
