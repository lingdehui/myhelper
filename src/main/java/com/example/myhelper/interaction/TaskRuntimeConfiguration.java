package com.example.myhelper.interaction;

import com.example.myhelper.agent.AgentCaller;
import com.example.myhelper.application.TaskActivity;
import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.CapabilitySearchPort;
import com.example.myhelper.capability.CapabilityUsePort;
import com.example.myhelper.capability.CheckedCapabilityUse;
import com.example.myhelper.gate.RuntimeGate;
import com.example.myhelper.interaction.desktop.DesktopConfiguration;
import com.example.myhelper.llm.ConfiguredModelAdapter;
import com.example.myhelper.llm.ModelClientConfiguration;
import com.example.myhelper.orchestration.AgentCallerPlanner;
import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.orchestration.ExecutionTraceStore;
import com.example.myhelper.orchestration.SpringToolExecutor;
import com.example.myhelper.orchestration.ToolReliabilityGate;
import com.example.myhelper.records.TaskRecordPort;
import com.example.myhelper.tasklog.TaskLogConfiguration;
import com.example.myhelper.tasklog.TaskLogPort;
import com.example.myhelper.tools.ToolDirectory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 应用入口共享的编排运行时，默认仅启用模型规划和答复。 */
@Configuration(proxyBeanMethods = false)
@Import({TaskLogConfiguration.class, DesktopConfiguration.class})
public class TaskRuntimeConfiguration {
    /** 编排预算和运行容量共用此配置前缀。 */
    private static final String PREFIX = "myhelper.orchestration.";

    @Bean
    public TaskActivity taskActivity() {
        return new TaskActivity();
    }

    @Bean
    public ExecutionTraceStore taskExecutionTraces(Environment environment) {
        return new ExecutionTraceStore(
                number(environment, "trace-max-runs", 64),
                number(environment, "trace-max-nodes", 4096),
                number(environment, "trace-max-events", 16384));
    }

    @Bean
    public ThreadPoolTaskExecutor taskWorkers(Environment environment) {
        int threads = number(environment, "worker-threads", 4);
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(number(environment, "worker-queue-capacity", 128));
        executor.setThreadNamePrefix("myhelper-task-");
        // 入口先有界等待收尾。远端调用卡住时，工作线程不能无限阻止进程退出。
        executor.setDaemon(true);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledThreadPoolExecutor taskTimer() {
        var timer = new ScheduledThreadPoolExecutor(1, new CustomizableThreadFactory("myhelper-deadline-"));
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }

    @Bean
    RuntimeTools taskRuntimeTools(
            AgentCaller caller,
            ConfiguredModelAdapter models,
            ObjectProvider<ToolDirectory.Binding> bindings,
            ObjectProvider<ToolDirectory> directories,
            ObjectProvider<RuntimeGate> gates,
            ObjectProvider<SpringToolExecutor.ResourceResolver> resourceResolvers,
            ObjectProvider<TaskLogPort> taskLogs) {
        List<ToolDirectory.Binding> active = bindings.orderedStream().toList();
        RuntimeGate gate = gates.getIfAvailable();
        if (active.isEmpty()) {
            if (gate == null) {
                gate = new TextResultGate();
            }
            return new RuntimeTools(caller, SpringToolExecutor.withBindings(active, new NoResources(), 1),
                    gate, List.of());
        }

        ToolDirectory directory = directories.getIfAvailable();
        SpringToolExecutor.ResourceResolver resources = resourceResolvers.getIfAvailable();
        if (gate == null || directory == null || resources == null) {
            throw new IllegalStateException("启用任务工具需要提供工具目录、运行门禁和资源解析器");
        }
        List<String> names = new ArrayList<>();
        List<ToolCallback> callbacks = new ArrayList<>();
        for (ToolDirectory.Binding binding : active) {
            String name = binding.ref().toolName();
            if (name.equals(AgentCallerPlanner.SUBMIT_PLAN_TOOL) || name.equals(AgentCallerPlanner.FINISH_TASK_TOOL)) {
                throw new IllegalArgumentException("任务工具与编排协议重名：" + name);
            }
            ToolDirectory.Registration registration = directory.find(name).orElse(null);
            if (registration == null || !registration.ref().equals(binding.ref())) {
                throw new IllegalArgumentException("任务工具与目录登记不一致：" + name);
            }
            names.add(name);
            callbacks.add(binding.callback());
        }
        return new RuntimeTools(new AgentCaller(models, callbacks, taskLogs.getIfAvailable(TaskLogPort::disabled)),
                SpringToolExecutor.withBindings(active, resources, 64), gate, List.copyOf(names));
    }

    @Bean
    AgentOrchestrator agentOrchestrator(
            RuntimeTools tools,
            ConfiguredModelAdapter models,
            ExecutionTraceStore taskExecutionTraces,
            @Qualifier("taskWorkers") ThreadPoolTaskExecutor workers,
            @Qualifier("taskTimer") ScheduledThreadPoolExecutor timer,
            TaskActivity activity,
            Environment environment,
            ObjectProvider<CapabilitySearchPort> searches,
            ObjectProvider<CapabilityUsePort> capabilityUses,
            ObjectProvider<TaskRecordPort> records,
            ObjectProvider<ToolReliabilityGate> reliability,
            ObjectProvider<TaskLogPort> taskLogs) {
        CapabilitySearchPort search = searches.getIfAvailable();
        if (search == null) {
            search = CapabilitySearchPort.disabled();
        }
        CapabilityUsePort capabilityUse = capabilityUses.getIfAvailable();
        if (capabilityUse == null) {
            capabilityUse = new CheckedCapabilityUse(search, tools.gate());
        }
        var limits = new AgentOrchestrator.Limits(
                number(environment, "max-rounds", 8),
                number(environment, "max-depth", 6),
                number(environment, "max-tasks", 32),
                number(environment, "max-steps", 32),
                number(environment, "max-model-calls", 48),
                number(environment, "max-tool-calls", 64),
                environment.getProperty(PREFIX + "timeout", Duration.class, Duration.ofMinutes(2)));
        return new AgentOrchestrator(new AgentCallerPlanner(tools.caller()), search, tools.actions(), tools.gate(),
                taskExecutionTraces, workers, timer, limits, models, records.getIfAvailable(TaskRecordPort::disabled),
                capabilityUse, reliability.getIfAvailable(ToolReliabilityGate::unavailable),
                taskLogs.getIfAvailable(TaskLogPort::disabled), activity);
    }

    @Bean
    TaskEntry taskEntry(AgentOrchestrator orchestrator, RuntimeTools tools,
                        @Qualifier("openAiChatModel") OpenAiChatModel client,
                        Environment environment, ExecutionTraceStore taskExecutionTraces,
                        ObjectProvider<TaskEntry.TraceListener> traceListeners) {
        return new TaskEntry(orchestrator, ModelClientConfiguration.configuredModelRef(environment, client),
                tools.names(), taskExecutionTraces, traceListeners.getIfAvailable());
    }

    private static int number(Environment environment, String property, int defaultValue) {
        return environment.getProperty(PREFIX + property, Integer.class, defaultValue);
    }

    record RuntimeTools(
            /** 规划使用的实际工具合同。 */
            AgentCaller caller,
            /** 通过同一批绑定装配的动作执行器。 */
            SpringToolExecutor actions,
            /** 运行许可与结果检查。 */
            RuntimeGate gate,
            /** 可向模型公开的工具范围。 */
            List<String> names) {
    }

    /** 首版只接收模型答复。宿主如需业务验收须提供自己的门禁。 */
    private static final class TextResultGate implements RuntimeGate {
        @Override
        public boolean allow(String goal, Map<String, Object> arguments, CapabilityLookup.Ref capability) {
            return false;
        }

        @Override
        public boolean verify(String goal, Map<String, Object> arguments, Object output) {
            return output != null && (!(output instanceof String text) || !text.isBlank());
        }
    }

    /** 默认无工具时，所有调用都会在参数分派前被拒绝。 */
    private static final class NoResources implements SpringToolExecutor.ResourceResolver {
        @Override
        public List<String> keys(String toolName, Map<String, Object> arguments) {
            return List.of();
        }
    }
}
