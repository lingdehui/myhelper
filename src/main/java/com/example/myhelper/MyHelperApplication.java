package com.example.myhelper;

import com.example.myhelper.config.MyHelperProperties;
import com.example.myhelper.config.FallbackModelTool;
import com.example.myhelper.config.ModelRouter;
import com.example.myhelper.dialog.DialogStateMachine;
import com.example.myhelper.dialog.SpeechAssembler;
import com.example.myhelper.exploration.AutonomousExplorationService;
import com.example.myhelper.exploration.ExplorationTool;
import com.example.myhelper.registry.ToolSyncService;
import com.example.myhelper.service.AudioRecorder;
import com.example.myhelper.service.BackgroundAudioService;
import com.example.myhelper.service.LocalASR;
import com.example.myhelper.service.SkillConfig;
import com.example.myhelper.service.SpeechEventLoop;
import com.example.myhelper.service.ToolSearchService;
import com.example.myhelper.service.TtsService;
import com.example.myhelper.service.TurnProcessor;
import com.example.myhelper.service.VadService;
import com.example.myhelper.service.VoiceprintService;
import com.example.myhelper.memory.unit.UniversalUnitExecutor;
import com.example.myhelper.schedule.ScheduledTaskService;
import com.example.myhelper.util.NativeLoader;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.stream.Stream;

/**
 * 桌面助手的启动编排入口。
 *
 * <p>本类只负责把独立的 Spring 组件装配成可运行的应用：收集工具、把同一份工具目录交给
 * 规划/探索/定时执行链路、启动语音子系统，以及维护控制台输入循环。具体业务逻辑应留在各自的服务中，
 * 不应继续堆积到这里。</p>
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.example.tools.windows", "com.example.myhelper", "com.example.myhelper.memory", "com.example.myhelper.integration", "com.example.myhelper.exploration", "dev.harrjdk.robotmcp.tools.software"})
public class MyHelperApplication {

    private static final Logger log = LoggerFactory.getLogger(MyHelperApplication.class);

    /** 启动阶段收集的 MCP 与本地工具；控制台对话以此作为基础能力目录。 */
    private volatile ToolCallback[] allTools;

    /** 包含 Unit 动态工具的派生目录；首次构建后缓存，避免重复查询图谱。 */
    private volatile ToolCallback[] cachedMergedTools;

    private volatile TurnProcessor turnProcessor;

    /** 语音事件循环与录音线程共享的模式状态。 */
    private volatile SpeechEventLoop.Mode mode = SpeechEventLoop.Mode.IDLE;

    public static void main(String[] args) {
        // 桌面应用依赖 java.awt.Robot（ScreenButtonService 屏幕控制），必须在 AWT 初始化前强制非 headless
        System.setProperty("java.awt.headless", "false");
        NativeLoader.extractToCurrentDir();
        SpringApplication.run(MyHelperApplication.class, args);
    }

    /**
     * 启动顺序必须保持稳定：先建立完整工具目录，再启动任何可能调用工具的后台组件。
     * 这样探索、定时任务和语音对话看到的是同一份能力视图。
     */
    @Bean
    public CommandLineRunner run(ModelRouter modelRouter,
                                 ToolCallbackProvider mcpTools,
                                 LocalASR localASR,
                                 TtsService ttsService,
                                 VadService vadService,
                                 SkillConfig skillConfig,
                                 TurnProcessor turnProcessor,
                                 VoiceprintService voiceprintService,
                                 DialogStateMachine dialogStateMachine,
                                 MyHelperProperties props,
                                 BackgroundAudioService backgroundAudio,
                                 SpeechEventLoop speechEventLoop,
                                 ApplicationContext appCtx,
                                 AutonomousExplorationService explorationService,
                                 ToolSyncService toolSyncService,
                                 UniversalUnitExecutor universalUnitExecutor,
                                 ScheduledTaskService scheduledTaskService,
                                 @Qualifier("aiExecutor") ExecutorService aiExecutor) {
        return args -> {
            this.turnProcessor = turnProcessor;
            ToolCatalog toolCatalog = buildToolCatalog(mcpTools, appCtx);
            this.allTools = toolCatalog.callbacks();
            turnProcessor.initToolSearch(allTools);
            turnProcessor.initDynamicClassLoader();

            wireToolConsumers(explorationService, universalUnitExecutor, scheduledTaskService);
            toolSyncService.syncOnStartup(allTools, toolCatalog.mcpCount());
            logStartupSummary(props, skillConfig, toolCatalog);
            registerNativeShutdownHook(localASR, ttsService, vadService, voiceprintService);
            synchronizeToolCategories(aiExecutor, props);
            startVoiceComponents(speechEventLoop, backgroundAudio);
            runConsoleLoop(modelRouter, localASR, ttsService, dialogStateMachine);
        };
    }

    /**
     * 收集可调用工具。每个 Bean 单独创建 provider，避免 Spring AI 将不同 Bean 中的同名方法混为一谈；
     * 再以工具名去重，以保证目录与实际执行顺序一致。
     */
    private ToolCatalog buildToolCatalog(ToolCallbackProvider mcpTools, ApplicationContext appCtx) {
        ToolCallback[] mcpCallbacks = mcpTools.getToolCallbacks();
        Map<String, ToolCallback> localToolsByName = new LinkedHashMap<>();
        Set<String> processedClassNames = new HashSet<>();

        for (String beanName : appCtx.getBeanDefinitionNames()) {
            registerBeanTools(appCtx, beanName, processedClassNames, localToolsByName);
        }

        ToolCallback[] localCallbacks = localToolsByName.values().toArray(ToolCallback[]::new);
        ToolCallback[] callbacks = Stream.concat(Arrays.stream(mcpCallbacks), Arrays.stream(localCallbacks))
                .toArray(ToolCallback[]::new);
        return new ToolCatalog(callbacks, mcpCallbacks.length, localCallbacks.length);
    }

    /** 忽略无法提前实例化的 Bean；它们不是启动阶段工具目录的必要条件。 */
    private void registerBeanTools(ApplicationContext appCtx, String beanName, Set<String> processedClassNames,
                                   Map<String, ToolCallback> localToolsByName) {
        try {
            Object bean = appCtx.getBean(beanName);
            if (!processedClassNames.add(bean.getClass().getName()) || !hasToolMethod(bean)) {
                return;
            }
            for (ToolCallback callback : MethodToolCallbackProvider.builder().toolObjects(bean).build().getToolCallbacks()) {
                localToolsByName.putIfAbsent(callback.getToolDefinition().name(), callback);
            }
        } catch (Exception e) {
            log.warn("跳过无法注册的工具 Bean: {}", beanName, e);
        }
    }

    private boolean hasToolMethod(Object bean) {
        return Arrays.stream(bean.getClass().getDeclaredMethods())
                .anyMatch(method -> method.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class));
    }

    /** 将统一工具目录注入所有按名称执行工具的后台链路。 */
    private void wireToolConsumers(AutonomousExplorationService explorationService,
                                   UniversalUnitExecutor universalUnitExecutor,
                                   ScheduledTaskService scheduledTaskService) {
        explorationService.setAllTools(allTools);
        universalUnitExecutor.setAllTools(allTools);
        scheduledTaskService.setAllTools(allTools);
    }

    private void logStartupSummary(MyHelperProperties props, SkillConfig skillConfig, ToolCatalog toolCatalog) {
        log.info("🤖 桌面助手已启动（贾维斯模式）");
        log.info("💡 文字输入：直接对话，回复有语音播报");
        log.info("💡 语音唤醒：说 '{}' 进入语音对话", props.voice().wakeWord());
        log.info("💡 语音模式：说 '停' 中断，说 '退出' 结束");
        log.info("📊 技能数: {} 个", skillConfig.getSkillNames().size());
        log.info("🔧 工具数: MCP {} + 本地 {} = {} 个",
                toolCatalog.mcpCount(), toolCatalog.localCount(), getMergedTools().length);
    }

    /** JVM 退出钩子只做资源释放，释放失败不阻碍后续资源回收。 */
    private void registerNativeShutdownHook(LocalASR localASR, TtsService ttsService,
                                            VadService vadService, VoiceprintService voiceprintService) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("🧹 释放 sherpa-onnx 资源...");
            closeNativeResources(localASR, ttsService, vadService, voiceprintService);
            log.info("✅ sherpa-onnx 资源已释放");
        }, "sherpa-shutdown"));
    }

    private void closeNativeResources(LocalASR localASR, TtsService ttsService,
                                      VadService vadService, VoiceprintService voiceprintService) {
        try { localASR.shutdown(); } catch (Exception e) { log.warn("释放 ASR 资源失败", e); }
        try { ttsService.destroy(); } catch (Exception e) { log.warn("释放 TTS 资源失败", e); }
        try { vadService.destroy(); } catch (Exception e) { log.warn("释放 VAD 资源失败", e); }
        try { voiceprintService.release(); } catch (Exception e) { log.warn("释放声纹资源失败", e); }
    }

    /** 分类同步可能调用模型，因此放入 AI 执行器而不阻塞语音与控制台的启动。 */
    private void synchronizeToolCategories(ExecutorService aiExecutor, MyHelperProperties props) {
        aiExecutor.submit(() -> {
            boolean forceCategory = props.toolPlanner().forceCategorySync();
            int categoryCount = forceCategory
                    ? turnProcessor.syncCategories(getMergedTools(), true)
                    : turnProcessor.syncCategoriesIncremental(getMergedTools());
            if (categoryCount > 0) {
                log.info("📁 工具分类已同步: {} 类 (force={})", categoryCount, forceCategory);
            }
        });
    }

    /** 语音循环先获取模式和工具目录，再由录音线程开始投递语音片段。 */
    private void startVoiceComponents(SpeechEventLoop speechEventLoop, BackgroundAudioService backgroundAudio) {
        speechEventLoop.setMode(mode);
        speechEventLoop.setTools(getMergedTools());
        speechEventLoop.start();
        backgroundAudio.start();
    }

    /** 控制台支持单行命令、一次性录音和空行结束的多行文本。 */
    private void runConsoleLoop(ModelRouter modelRouter, LocalASR localASR, TtsService ttsService,
                                DialogStateMachine dialogStateMachine) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            int roundNumber = 0;
            while (true) {
                log.info("> （多行输入，空行回车发送）");
                String firstLine = reader.readLine();
                if (firstLine == null || "exit".equalsIgnoreCase(firstLine.trim())) {
                    return;
                }
                dialogStateMachine.touch();

                if ("voice".equalsIgnoreCase(firstLine.trim())) {
                    roundNumber = processRecordedVoiceInput(modelRouter, localASR, ttsService, roundNumber);
                    continue;
                }

                String userInput = readMultilineInput(reader, firstLine);
                if (!userInput.isEmpty()) {
                    processTurn(modelRouter, ttsService, ++roundNumber, userInput);
                }
            }
        }
    }

    private int processRecordedVoiceInput(ModelRouter modelRouter, LocalASR localASR,
                                          TtsService ttsService, int roundNumber) {
        log.info("🎤 开始录音（5秒），请说话...");
        try {
            AudioRecorder.AudioData audio = AudioRecorder.record(5);
            String recognizedText = localASR.recognizeOnline(audio.samples(), audio.sampleRate());
            log.info("📝 识别结果: {}", recognizedText);
            if (isUsableRecognition(recognizedText)) {
                int nextRound = roundNumber + 1;
                processTurn(modelRouter, ttsService, nextRound, recognizedText);
                return nextRound;
            }
        } catch (Exception e) {
            log.error("❌ 录音或识别失败", e);
        }
        return roundNumber;
    }

    private boolean isUsableRecognition(String text) {
        return text != null && !text.isBlank() && !text.startsWith("(");
    }

    private String readMultilineInput(BufferedReader reader, String firstLine) throws Exception {
        StringBuilder input = new StringBuilder(firstLine);
        for (String line; (line = reader.readLine()) != null && !line.trim().isEmpty(); ) {
            input.append('\n').append(line);
        }
        return input.toString().trim();
    }

    private void processTurn(ModelRouter modelRouter, TtsService ttsService, int roundNumber, String userInput) {
        log.info("=== 第{}轮 === userInput='{}'", roundNumber, userInput);
        long startedAt = System.currentTimeMillis();
        turnProcessor.process(modelRouter, allTools, userInput, ttsService);
        log.info("=== 第{}轮完成 === 耗时={}s", roundNumber, (System.currentTimeMillis() - startedAt) / 1000.0);
    }

    /** 启动期使用的工具目录与计数，避免平行变量脱节。 */
    private record ToolCatalog(ToolCallback[] callbacks, int mcpCount, int localCount) {
    }

    // ========== 动态工具管理 ==========

    private synchronized ToolCallback[] getMergedTools() {
        // 启动阶段会被多处调用（工具数日志/分类同步/语音注入），缓存避免重复 mergeDynamicTools（重复查 Neo4j 构建 Unit 工具）
        if (cachedMergedTools == null) {
            cachedMergedTools = turnProcessor.mergeTools(allTools);
        }
        return cachedMergedTools;
    }
}
