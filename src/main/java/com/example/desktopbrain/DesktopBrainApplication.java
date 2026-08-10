package com.example.desktopbrain;

import com.example.desktopbrain.config.DesktopBrainProperties;
import com.example.desktopbrain.config.FallbackModelTool;
import com.example.desktopbrain.config.ModelRouter;
import com.example.desktopbrain.dialog.DialogStateMachine;
import com.example.desktopbrain.dialog.SpeechAssembler;
import com.example.desktopbrain.exploration.AutonomousExplorationService;
import com.example.desktopbrain.exploration.ExplorationTool;
import com.example.desktopbrain.service.AudioRecorder;
import com.example.desktopbrain.service.BackgroundAudioService;
import com.example.desktopbrain.service.CapabilityService;
import com.example.desktopbrain.service.FriendMatcher;
import com.example.desktopbrain.service.FailurePatternTool;
import com.example.desktopbrain.service.LocalASR;
import com.example.desktopbrain.service.SkillConfig;
import com.example.desktopbrain.service.SpeechEventLoop;
import com.example.desktopbrain.service.ToolSearchService;
import com.example.desktopbrain.service.TtsService;
import com.example.desktopbrain.service.TurnProcessor;
import com.example.desktopbrain.service.VadService;
import com.example.desktopbrain.service.VoiceprintService;
import com.example.desktopbrain.integration.HaToolService;
import com.example.desktopbrain.util.NativeLoader;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
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

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

@SpringBootApplication
@ComponentScan(basePackages = {"com.example.tools.windows", "com.example.desktopbrain", "com.example.desktopbrain.memory", "com.example.desktopbrain.integration", "com.example.desktopbrain.exploration"})
public class DesktopBrainApplication {

    private static final Logger log = LoggerFactory.getLogger(DesktopBrainApplication.class);

    // ========== 配置 ==========
    private volatile DesktopBrainProperties props;

    // ========== 线程池 ==========
    private volatile ExecutorService aiExecutor;

    // ========== 工具管理 ==========
    private volatile ToolCallback[] allTools;
    private volatile TurnProcessor turnProcessor;
    private volatile AutonomousExplorationService explorationService;

    // ========== 模式（用于录音线程判断 VAD 策略） ==========
    private volatile SpeechEventLoop.Mode mode = SpeechEventLoop.Mode.IDLE;

    public static void main(String[] args) {
        NativeLoader.extractToCurrentDir();
        SpringApplication.run(DesktopBrainApplication.class, args);
    }

    @Bean
    public CommandLineRunner run(ModelRouter modelRouter,
                                 ToolCallbackProvider mcpTools,
                                 FriendMatcher friendMatcher,
                                 CapabilityService capabilityService,
                                 LocalASR localASR,
                                 TtsService ttsService,
                                 VadService vadService,
                                 SkillConfig skillConfig,
                                 TurnProcessor turnProcessor,
                                 HaToolService haToolService,
                                 VoiceprintService voiceprintService,
                                 DialogStateMachine dialogStateMachine,
                                 SpeechAssembler speechAssembler,
                                 ToolSearchService toolSearchService,
                                 FailurePatternTool failurePatternTool,
                                 ExplorationTool explorationTool,
                                 FallbackModelTool fallbackModelTool,
                                 DesktopBrainProperties props,
                                 BackgroundAudioService backgroundAudio,
                                 SpeechEventLoop speechEventLoop,
                                 ApplicationContext appCtx,
                                 AutonomousExplorationService explorationService,
                                 @Qualifier("aiExecutor") ExecutorService aiExecutor) {
        return args -> {
            this.props = props;
            this.aiExecutor = aiExecutor;
            this.turnProcessor = turnProcessor;
            this.explorationService = explorationService;

            // 合并 MCP 工具 + 所有本地 @Tool bean（含生成的）
            int mcpCount = mcpTools.getToolCallbacks().length;
            java.util.Set<String> seenBeans = new java.util.HashSet<>();
            List<Object> allToolBeans = new ArrayList<>();
            // 扫描所有带 @Tool 方法的 bean（自动发现，无需手动添加，避免重复）
            for (String beanName : appCtx.getBeanDefinitionNames()) {
                try {
                    Object bean = appCtx.getBean(beanName);
                    if (!seenBeans.add(bean.getClass().getName())) continue;
                    for (java.lang.reflect.Method m : bean.getClass().getDeclaredMethods()) {
                        if (m.isAnnotationPresent(org.springframework.ai.tool.annotation.Tool.class)) {
                            allToolBeans.add(bean);
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    // 跳过无法获取的 bean
                }
            }
            // 每个 bean 独立创建 provider（避免不同 bean 间同名 @Tool 方法冲突），然后名称去重
            java.util.LinkedHashMap<String, ToolCallback> localToolMap = new java.util.LinkedHashMap<>();
            for (Object bean : allToolBeans) {
                try {
                    ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                            .toolObjects(bean)
                            .build()
                            .getToolCallbacks();
                    for (ToolCallback tc : callbacks) {
                        localToolMap.putIfAbsent(tc.getToolDefinition().name(), tc);
                    }
                } catch (Exception e) {
                    log.warn("⚠️ 无法注册工具 bean: {}", bean.getClass().getSimpleName(), e);
                }
            }
            ToolCallback[] localTools = localToolMap.values().toArray(ToolCallback[]::new);
            int localCount = localTools.length;
            ToolCallback[] allToolCallbacks = Stream.concat(
                    Arrays.stream(mcpTools.getToolCallbacks()),
                    Arrays.stream(localTools)
            ).toArray(ToolCallback[]::new);
            this.allTools = allToolCallbacks;
            turnProcessor.initToolSearch(allToolCallbacks);
            turnProcessor.initDynamicClassLoader();

            // 探索服务接收完整工具列表（含生成工具），避免"缺失浏览器工具"误判
            explorationService.setAllTools(allToolCallbacks);

            log.info("🤖 桌面助手已启动（贾维斯模式）");
            log.info("💡 文字输入：直接对话，回复有语音播报");
            log.info("💡 语音唤醒：说 '{}' 进入语音对话", props.voice().wakeWord());
            log.info("💡 语音模式：说 '停' 中断，说 '退出' 结束");
            log.info("📊 技能数: {} 个", skillConfig.getSkillNames().size());
            log.info("🔧 工具数: MCP {} + 本地 {} = {} 个", mcpCount, localCount, getMergedTools().length);

            // JVM 退出时释放 native 资源
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("🧹 释放 sherpa-onnx 资源...");
                try { localASR.shutdown(); } catch (Exception ignored) {}
                try { ttsService.destroy(); } catch (Exception ignored) {}
                try { vadService.destroy(); } catch (Exception ignored) {}
                try { voiceprintService.release(); } catch (Exception ignored) {}
                log.info("✅ sherpa-onnx 资源已释放");
            }, "sherpa-shutdown"));

            // 异步同步工具分类（启动时非强制，已有缓存则跳过）
            aiExecutor.submit(() -> {
                int catCount = turnProcessor.syncCategories(getMergedTools(), false);
                if (catCount > 0) log.info("📁 工具分类已同步: {} 类", catCount);
            });

            // 启动语音子组件
            speechEventLoop.setMode(mode); // 共享 mode 对象
            speechEventLoop.setTools(getMergedTools()); // 注入工具列表
            speechEventLoop.start();
            backgroundAudio.start();

            // 主线程：文字对话
            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    log.info("> ");
                    String userInput = scanner.nextLine();
                    dialogStateMachine.touch();
                    if ("exit".equalsIgnoreCase(userInput.trim())) {
                        break;
                    }

                    if ("voice".equalsIgnoreCase(userInput.trim())) {
                        log.info("🎤 开始录音（5秒），请说话...");
                        try {
                            AudioRecorder.AudioData audio = AudioRecorder.record(5);
                            String text = localASR.recognizeOnline(audio.samples(), audio.sampleRate());
                            log.info("📝 识别结果: {}", text);
                            if (text != null && !text.isEmpty() && !text.startsWith("(")) {
                                turnProcessor.process(modelRouter, allTools, text, ttsService);
                            }
                        } catch (Exception e) {
                            log.error("❌ 录音或识别失败", e);
                        }
                        continue;
                    }

                    turnProcessor.process(modelRouter, allTools, userInput, ttsService);
                }
            }
        };
    }

    // ========== 动态工具管理 ==========

    private synchronized ToolCallback[] getMergedTools() {
        return turnProcessor.mergeTools(allTools);
    }
}
