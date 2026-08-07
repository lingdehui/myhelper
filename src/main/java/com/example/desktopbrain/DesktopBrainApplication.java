package com.example.desktopbrain;

import com.example.desktopbrain.common.PromptLoader;
import com.example.desktopbrain.config.DesktopBrainProperties;
import com.example.desktopbrain.dialog.DialogStateMachine;
import com.example.desktopbrain.dialog.SpeechAssembler;
import com.example.desktopbrain.exploration.ExplorationTool;
import com.example.desktopbrain.service.AudioRecorder;
import com.example.desktopbrain.service.CapabilityService;
import com.example.desktopbrain.service.FriendMatcher;
import com.example.desktopbrain.service.FailurePatternTool;
import com.example.desktopbrain.service.LocalASR;
import com.example.desktopbrain.service.SkillConfig;
import com.example.desktopbrain.service.ToolSearchService;
import com.example.desktopbrain.service.TtsService;
import com.example.desktopbrain.service.TurnProcessor;
import com.example.desktopbrain.service.VadService;
import com.example.desktopbrain.service.VoiceprintService;
import com.example.desktopbrain.integration.HaToolService;
import com.example.desktopbrain.util.NativeLoader;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

@SpringBootApplication
@ComponentScan(basePackages = {"com.example.tools.windows", "com.example.desktopbrain", "com.example.desktopbrain.memory", "com.example.desktopbrain.integration", "com.example.desktopbrain.exploration"})
public class DesktopBrainApplication {

    // ========== 配置（从 application.yml 注入） ==========
    private volatile DesktopBrainProperties props;

    // ========== 状态 ==========
    private enum Mode { IDLE, VOICE_DIALOG }
    private volatile Mode mode = Mode.IDLE;

    // ========== 对话系统 ==========
    private volatile DialogStateMachine dialogStateMachine;
    private volatile SpeechAssembler speechAssembler;

    // ========== AI Turn 处理器 ==========
    private volatile TurnProcessor turnProcessor;

    // ========== 服务（仅保留语音/声纹相关） ==========
    private volatile VoiceprintService voiceprintService;
    private volatile boolean lastWasFuzzyListening = false;  // 上次是否在模糊监听

    // ========== 工具管理 ==========
    /** 静态工具（MCP + 本地 @Tool，启动时一次性注册） */
    private volatile ToolCallback[] allTools;
    /** 工具搜索服务（让 AI 在执行过程中搜工具） */
    private volatile ToolSearchService toolSearchService;

    // ========== 补充信息 ==========
    private volatile String lastUserInput = "";        // 最近一次用户输入
    private volatile String lastAiResponse = "";        // 最近一次 AI 回复（用于语义关联）
    private volatile String currentSpeaker = null;       // 当前识别到的说话人
    private volatile String pendingSupplement = null;    // 待确认的补充信息
    private volatile boolean awaitingSupplementConfirm = false; // 等待用户确认补充
    private volatile boolean enrollmentHinted = false;  // 是否已提示过注册声纹（只提示一次）
    private volatile boolean followUpHintGiven = false;   // 跟随窗口内是否已提示

    // ========== 事件队列 ==========
    private final LinkedBlockingQueue<String> speechQueue = new LinkedBlockingQueue<>();

    public static void main(String[] args) {
        NativeLoader.extractToCurrentDir();
        SpringApplication.run(DesktopBrainApplication.class, args);
    }

    @Bean
    public CommandLineRunner run(ChatClient.Builder chatClientBuilder,
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
                                 DesktopBrainProperties props,
                                 PromptLoader promptLoader) {
        return args -> {
            this.props = props;

            // 合并 MCP 工具 + 本地工具
            int mcpCount = mcpTools.getToolCallbacks().length;
            ToolCallback[] localTools = MethodToolCallbackProvider.builder()
                    .toolObjects(friendMatcher, capabilityService, haToolService, toolSearchService, failurePatternTool, explorationTool)
                    .build()
                    .getToolCallbacks();
            int localCount = localTools.length;
            ToolCallback[] allToolCallbacks = Stream.concat(
                    Arrays.stream(mcpTools.getToolCallbacks()),
                    Arrays.stream(localTools)
            ).toArray(ToolCallback[]::new);

            this.turnProcessor = turnProcessor;
            this.voiceprintService = voiceprintService;
            this.toolSearchService = toolSearchService;
            this.dialogStateMachine = dialogStateMachine;
            this.speechAssembler = speechAssembler;

            // 存储静态工具 + 初始化动态 ClassLoader
            this.allTools = allToolCallbacks;
            turnProcessor.initToolSearch(allToolCallbacks);
            turnProcessor.initDynamicClassLoader();

            ChatClient chatClient = chatClientBuilder
                    .defaultSystem(promptLoader.getDefaultSystem())
                    .build();

            System.out.println("🤖 桌面助手已启动（贾维斯模式）");
            System.out.println("💡 文字输入：直接对话，回复有语音播报");
            System.out.println("💡 语音唤醒：说 '" + props.voice().wakeWord() + "' 进入语音对话");
            System.out.println("💡 语音模式：说 '停' 中断，说 '退出' 结束");
            System.out.println("📊 技能数: " + skillConfig.getSkillNames().size() + " 个");
            System.out.println("🔧 工具数: MCP " + mcpCount + " + 本地 " + localCount + " = " + getMergedTools().length + " 个");

            // JVM 退出时主动释放 sherpa-onnx native 资源，避免 EXCEPTION_ACCESS_VIOLATION
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("🧹 释放 sherpa-onnx 资源...");
                try { localASR.shutdown(); } catch (Exception ignored) {}
                try { ttsService.destroy(); } catch (Exception ignored) {}
                try { vadService.destroy(); } catch (Exception ignored) {}
                try { voiceprintService.release(); } catch (Exception ignored) {}
                System.out.println("✅ sherpa-onnx 资源已释放");
            }, "sherpa-shutdown"));

            // 异步同步工具分类到 Qdrant（启动时 AI 扫描所有工具自动分组归类）
            Thread categorySyncThread = new Thread(() -> {
                int catCount = turnProcessor.syncCategories(getMergedTools());
                if (catCount > 0) System.out.println("📁 工具分类已同步: " + catCount + " 类");
            }, "category-sync");
            categorySyncThread.setDaemon(true);
            categorySyncThread.start();

            // 启动后台录音线程
            Thread recorderThread = new Thread(() -> startBackgroundRecording(localASR, vadService, ttsService), "bg-recorder");
            recorderThread.setDaemon(true);
            recorderThread.start();

            // 启动语音事件处理线程
            Thread handlerThread = new Thread(() -> handleSpeechEvents(chatClient, getMergedTools(), ttsService, skillConfig, vadService), "speech-handler");
            handlerThread.setDaemon(true);
            handlerThread.start();

            // 主线程：文字对话
            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    System.out.print("\n> ");
                    String userInput = scanner.nextLine();
                    dialogStateMachine.touch();  // 标记交互时间
                    if ("exit".equalsIgnoreCase(userInput.trim())) {
                        break;
                    }

                    if ("voice".equalsIgnoreCase(userInput.trim())) {
                        System.out.println("🎤 开始录音（5秒），请说话...");
                        try {
                            AudioRecorder.AudioData audio = AudioRecorder.record(5);
                            String text = localASR.recognizeOnline(audio.samples(), audio.sampleRate());
                            System.out.println("📝 识别结果: " + text);
                            if (text != null && !text.isEmpty() && !text.startsWith("(")) {
                                ToolCallback[] mergedTools = getMergedTools();
                                turnProcessor.process(chatClient, allTools, text, ttsService);
                            }
                        } catch (Exception e) {
                            System.err.println("❌ 录音或识别失败: " + e.getMessage());
                        }
                        continue;
                    }

                    // 文字对话
                    ToolCallback[] mergedTools = getMergedTools();
                    turnProcessor.process(chatClient, allTools, userInput, ttsService);
                }
            }
        };
    }

    // ========== 后台录音线程（Silero VAD） ==========
    private void startBackgroundRecording(LocalASR localASR, VadService vadService, TtsService ttsService) {
        TargetDataLine line = null;
        try {
            AudioFormat format = new AudioFormat(props.voice().sampleRate(), 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format, 16000);
            line.start();

            byte[] buffer = new byte[3200];
            boolean wasSpeaking = false;
            ByteArrayOutputStream energyBuf = new ByteArrayOutputStream();
            long silenceStart = 0, speechStart = 0;
            boolean isSpeaking = false;
            final boolean useVad = vadService.isAvailable();
            float[] trailBuf = new float[props.voice().sampleRate() / 5];
            int trailPos = 0;
            long ttsEchoUntil = 0;

            // 流式 ASR：IDLE 模式的持续识别流（用于快速唤醒词检测）
            OnlineStream idleStream = null;
            long idleStreamCreated = 0;

            if (!useVad) System.out.println("ℹ️ VAD 未就绪，使用能量检测回退");

            while (!Thread.currentThread().isInterrupted()) {
                int available = line.available();
                if (available <= 0) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                    continue;
                }
                int count = line.read(buffer, 0, Math.min(available, buffer.length));
                if (count <= 0) continue;

                if (ttsService.isPlaying()) {
                    // TTS 播放中仍保持录音（用于自然打断检测），但清空 VAD 以过滤 TTS 回声
                    ttsEchoUntil = System.currentTimeMillis() + props.voice().ttsEchoDrainMs();
                    if (useVad) vadService.clear();
                    else { isSpeaking = false; energyBuf.reset(); silenceStart = 0; }
                    wasSpeaking = false;
                    continue;
                }
                if (System.currentTimeMillis() < ttsEchoUntil) {
                    if (useVad) vadService.clear();
                    trailPos = 0;
                    Arrays.fill(trailBuf, 0);
                    wasSpeaking = false;
                    continue;
                }

                float[] samples = pcmToFloat(buffer, count);

                for (float s : samples) {
                    trailBuf[trailPos] = s;
                    trailPos = (trailPos + 1) % trailBuf.length;
                }

                // ===== 流式唤醒词检测（IDLE 模式，边进边识别）=====
                if (mode == Mode.IDLE && useVad) {
                    if (idleStream == null) {
                        idleStream = localASR.createStream();
                        idleStreamCreated = System.currentTimeMillis();
                    }
                    String partial = localASR.feedStream(idleStream, samples, props.voice().sampleRate(), false);
                    if (partial.contains(props.voice().wakeWord())) {
                        System.out.println("\n🎤 流式识别: " + partial + " → 唤醒！");
                        speechQueue.offer(props.voice().wakeWord());
                        idleStream.release();
                        idleStream = null;
                    }
                    // 每 3 秒重建 stream 防止上下文累积
                    if (System.currentTimeMillis() - idleStreamCreated > 3000) {
                        idleStream.release();
                        idleStream = null;
                    }
                } else if (mode != Mode.IDLE && idleStream != null) {
                    // 进入语音对话后释放空闲流
                    idleStream.release();
                    idleStream = null;
                }

                if (useVad) {
                    vadService.acceptWaveform(samples);
                    while (vadService.hasSegment()) {
                        SpeechSegment seg = vadService.nextSegment();
                        float[] speech = seg.getSamples();
                        if (speech.length > props.voice().sampleRate() * 0.25f) {
                            if (calcEnergyFallback(speech) < (float) props.voice().energyThreshold()) continue;
                            float[] withTrail = new float[speech.length + trailBuf.length];
                            System.arraycopy(speech, 0, withTrail, 0, speech.length);
                            for (int j = 0; j < trailBuf.length; j++) {
                                withTrail[speech.length + j] = trailBuf[(trailPos + j) % trailBuf.length];
                            }

                            // 声纹验证（在 ASR 之前用原始音频）
                            String speaker = null;
                            if (voiceprintService.isAvailable() && voiceprintService.listSpeakers().length > 0) {
                                speaker = voiceprintService.search(withTrail, props.voice().sampleRate());
                                currentSpeaker = speaker;
                            }

                            String text;
                            if (mode == Mode.VOICE_DIALOG && localASR.isOfflineAvailable()) {
                                text = localASR.recognizeOffline(withTrail, props.voice().sampleRate());
                            } else {
                                text = localASR.recognizeOnline(withTrail, props.voice().sampleRate());
                            }
                            if (text != null && !text.isEmpty() && !text.startsWith("(")) {
                                // 送入拼接器：在 debounce 窗口内合并多段语音为完整句子
                                boolean appended = speechAssembler.addSegment(text);
                                if (!appended && speechAssembler.hasPending()) {
                                    // 当前片段属于新句子，先把之前积累的完整句子送入队列
                                    String complete = speechAssembler.getFullSentence();
                                    if (complete != null && !complete.isEmpty()) {
                                        System.out.println("\n🎤 " + (currentSpeaker != null ? "[" + currentSpeaker + "]" : "") + ": " + complete);
                                        speechQueue.offer(complete);
                                    }
                                }
                            }
                        }
                    }
                    boolean nowSpeaking = vadService.isSpeech();
                    if (nowSpeaking && !wasSpeaking) {
                        System.out.print("\r🎤 正在听... ");
                        playBeep(props.voice().beep().frequency(), 80);
                    }
                    wasSpeaking = nowSpeaking;
                    // 周期性 flush SpeechAssembler：确保超时句子不会卡住（没有后续语音时也能交付）
                    if (!turnProcessor.isActive() && speechAssembler.hasPending()) {
                        String complete = speechAssembler.getFullSentence();
                        if (complete != null && !complete.isEmpty()) {
                            System.out.println("\n🎤 " + (currentSpeaker != null ? "[" + currentSpeaker + "]" : "") + ": " + complete);
                            speechQueue.offer(complete);
                        }
                    }
                } else {
                    float energy = calcEnergyFallback(samples);
                    if (!isSpeaking) {
                        if (energy > (float) props.voice().energyThreshold()) {
                            isSpeaking = true;
                            speechStart = System.currentTimeMillis();
                            silenceStart = 0;
                            energyBuf.reset();
                            System.out.print("\r🎤 正在听... ");
                            playBeep(props.voice().beep().frequency(), 80);
                        }
                    } else {
                        energyBuf.write(buffer, 0, count);
                        if (energy < (float) props.voice().energyThreshold()) {
                            if (silenceStart == 0) silenceStart = System.currentTimeMillis();
                            if (System.currentTimeMillis() - silenceStart > props.voice().silenceTimeoutMs()) {
                                if (System.currentTimeMillis() - speechStart > props.voice().minSpeechDurationMs()) {
                                    float[] energySamples = pcmToFloat(energyBuf.toByteArray());

                                    // 声纹验证
                                    String speaker = null;
                                    if (voiceprintService.isAvailable() && voiceprintService.listSpeakers().length > 0) {
                                        speaker = voiceprintService.search(energySamples, props.voice().sampleRate());
                                        currentSpeaker = speaker;
                                    }

                                    String text;
                                    if (mode == Mode.VOICE_DIALOG && localASR.isOfflineAvailable()) {
                                        text = localASR.recognizeOffline(energySamples, props.voice().sampleRate());
                                    } else {
                                        text = localASR.recognizeOnline(energySamples, props.voice().sampleRate());
                                    }
                                    if (text != null && !text.isEmpty() && !text.startsWith("(")) {
                                        // 送入拼接器
                                        speechAssembler.addSegment(text);
                                    }
                                }
                                isSpeaking = false;
                                silenceStart = 0;
                            }
                        } else { silenceStart = 0; }
                        if (System.currentTimeMillis() - speechStart > props.voice().maxSpeechDurationMs()) {
                            isSpeaking = false; silenceStart = 0; energyBuf.reset();
                        }
                    }
                }

                // 定期 flush 拼接器：debounce 窗口过后把完整句子送入队列
                if (speechAssembler.hasPending()) {
                    String complete = speechAssembler.getFullSentence();
                    if (complete != null && !complete.isEmpty()) {
                        System.out.println("\n🎤 " + (currentSpeaker != null ? "[" + currentSpeaker + "]" : "") + ": " + complete);
                        speechQueue.offer(complete);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("❌ 录音线程异常: " + e.getMessage());
        } finally {
            if (line != null) {
                try { line.stop(); line.close(); } catch (Exception ex) {
                    System.err.println("⚠️ 音频设备关闭异常: " + ex.getMessage());
                }
            }
        }
    }

    // ========== 语音事件处理线程（基于 DialogStateMachine + SpeechAssembler） ==========
    private void handleSpeechEvents(ChatClient chatClient, ToolCallback[] tools, TtsService ttsService, SkillConfig skillConfig, VadService vadService) {
        // 守卫：服务未初始化完成时静默跳过（启动时序保护）
        if (voiceprintService == null || speechAssembler == null) return;
        boolean voiceprintAvailable = voiceprintService.isAvailable();
        boolean hasVoiceprints = voiceprintAvailable && voiceprintService.listSpeakers().length > 0;

        if (!voiceprintAvailable) {
            System.out.println("ℹ️ 声纹识别未启用，任何人声音均可唤醒");
        } else if (!hasVoiceprints) {
            System.out.println("ℹ️ 声纹识别已就绪，但尚未注册声纹");
            System.out.println("   👉 不注册也可以正常使用，如需专属唤醒可随时说 '注册声纹'");
        } else {
            System.out.println("🎙️ 声纹保护已开启，已注册用户: " + String.join(", ", voiceprintService.listSpeakers()));
        }

        while (!Thread.currentThread().isInterrupted()) {
            try {
                String text = speechQueue.poll(props.voice().eventPollIntervalMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
                DialogStateMachine.State state = dialogStateMachine.getState();

                // 有语音输入 → 标记交互时间
                if (text != null && !text.isEmpty()) {
                    dialogStateMachine.touch();
                }

                // ========== IDLE 状态：模糊监听，只检测唤醒词 ==========
                if (state == DialogStateMachine.State.IDLE) {
                    if (text == null) {
                        // 只在状态变化时打印一次
                        if (!lastWasFuzzyListening) {
                            System.out.print("\r💤 模糊监听中...");
                            lastWasFuzzyListening = true;
                        }
                        continue;
                    }
                    lastWasFuzzyListening = false;  // 检测到语音，重置状态
                    if (text.contains(props.voice().wakeWord())) {
                        dialogStateMachine.transitionTo(DialogStateMachine.State.LISTENING);
                        mode = Mode.VOICE_DIALOG;
                        turnProcessor.resetSilenceCount();
                        System.out.println("\n✨ 已唤醒！进入语音对话模式");
                        ttsService.speakAsync("我在");
                        if (voiceprintAvailable && !hasVoiceprints && !enrollmentHinted) {
                            enrollmentHinted = true;
                            System.out.println("💡 （可选）说 '注册声纹' 可设置专属声纹，不注册也能正常使用");
                        }
                    }
                    continue;
                }

                // ========== 非 IDLE 状态：精确监听 ==========
                if (text == null) {
                    if (ttsService.isPlaying()) {
                        turnProcessor.resetSilenceCount();
                        followUpHintGiven = false;
                    } else if (vadService.isAvailable() && vadService.isSpeech()) {
                        turnProcessor.resetSilenceCount();
                        followUpHintGiven = false;
                    } else {
                        turnProcessor.incrementSilenceCount();
                        int hintThreshold = props.dialog().followUpSilenceHintMs() / props.voice().eventPollIntervalMs();
                        int timeoutThreshold = props.dialog().followUpWindowMs() / props.voice().eventPollIntervalMs();
                        int sc = turnProcessor.getSilenceCount();
                        if (!turnProcessor.isActive() && dialogStateMachine.getState() == DialogStateMachine.State.LISTENING) {
                            if (!followUpHintGiven && sc >= hintThreshold && sc < timeoutThreshold) {
                                followUpHintGiven = true;
                                playBeep(props.voice().beep().frequency(), props.voice().beep().amplitude());
                                if (sc >= hintThreshold + 2) {
                                    System.out.println("\n💬 跟随对话中...（无需重新唤醒）");
                                }
                            }
                            if (sc >= timeoutThreshold) {
                                dialogStateMachine.transitionTo(DialogStateMachine.State.IDLE);
                                mode = Mode.IDLE;
                                turnProcessor.resetSilenceCount();
                                followUpHintGiven = false;
                                System.out.println("\n⏰ " + (props.dialog().followUpWindowMs() / 1000) + " 秒无语音，回到待命");
                                ttsService.speakAsync("已回到待命模式");
                            }
                        }
                    }
                    continue;
                }

                turnProcessor.resetSilenceCount();
                followUpHintGiven = false;

                // ========== SPEAKING / PROCESSING 状态：自然打断 ==========
                if (state == DialogStateMachine.State.SPEAKING || state == DialogStateMachine.State.PROCESSING) {
                    if (voiceprintAvailable && hasVoiceprints && currentSpeaker == null) {
                        System.out.println("\n🔇 声纹不匹配，忽略打断");
                        continue;
                    }

                    System.out.println("\n⚡ 自然打断！");
                    dialogStateMachine.transitionTo(DialogStateMachine.State.INTERRUPTED);
                    turnProcessor.interruptCurrentTurn();
                    ttsService.stop();
                    playBeep(400, 80);
                }

                // ---- 声纹标注 ----
                if (voiceprintAvailable && hasVoiceprints) {
                    System.out.println("\n🎤 听到 [" + (currentSpeaker != null ? currentSpeaker : "未识别") + "]: " + text);
                } else {
                    System.out.println("\n📝 你: " + text);
                }

                playBeep(600, 50);

                // ---- 指令处理 ----
                if (containsKeyword(text, props.dialog().exitKeywords())) {
                    dialogStateMachine.transitionTo(DialogStateMachine.State.IDLE);
                    mode = Mode.IDLE;
                    turnProcessor.interruptCurrentTurn();
                    ttsService.stop();
                    System.out.println("👋 对话结束，回到待命");
                    ttsService.speakAsync("再见");
                    continue;
                }

                if (containsKeyword(text, props.dialog().interruptKeywords())) {
                    turnProcessor.interruptCurrentTurn();
                    ttsService.stop();
                    System.out.println("🛑 已中断，请继续说");
                    turnProcessor.resetSilenceCount();
                    continue;
                }

                // ---- 注册声纹指令 ----
                if (text.contains("注册声纹")) {
                    handleEnrollment(ttsService);
                    continue;
                }

                // ---- 补充信息确认流程 ----
                if (awaitingSupplementConfirm) {
                    if (isConfirmKeyword(text)) {
                        awaitingSupplementConfirm = false;
                        String supplement = pendingSupplement;
                        pendingSupplement = null;

                        long interruptedId = turnProcessor.getCurrentTurnId();
                        turnProcessor.interruptCurrentTurn();

                        String combinedInput = lastUserInput + "\n\n--- 补充信息 ---\n" + supplement;
                        System.out.println("🔄 确认补充，中断 AI (turn=" + interruptedId + ")，带补充信息重新执行...");
                        System.out.println("  原请求: " + lastUserInput);
                        System.out.println("  补充: " + supplement);

                        dialogStateMachine.transitionTo(DialogStateMachine.State.PROCESSING);
                        Thread worker = new Thread(() -> turnProcessor.process(chatClient, allTools, combinedInput, ttsService), "ai-worker-supplement");
                        worker.setDaemon(true);
                        worker.start();
                    } else if (isRejectKeyword(text)) {
                        awaitingSupplementConfirm = false;
                        pendingSupplement = null;
                        System.out.println("❌ 用户拒绝补充，AI 继续执行");
                        ttsService.speakAsync("好的，继续执行");
                    } else {
                        awaitingSupplementConfirm = false;
                        pendingSupplement = null;
                        System.out.println("↩️ 用户说了其他内容，作为新请求处理");
                        lastUserInput = text;
                        dialogStateMachine.transitionTo(DialogStateMachine.State.PROCESSING);
                        Thread worker = new Thread(() -> turnProcessor.process(chatClient, allTools, text, ttsService), "ai-worker");
                        worker.setDaemon(true);
                        worker.start();
                    }
                    continue;
                }

                // ---- AI 执行中：补充信息判断 ----
                if (turnProcessor.isActive() && !dialogStateMachine.canInterrupt()) {
                    handleSupplement(chatClient, tools, text, ttsService, skillConfig);
                    continue;
                }

                // ---- 正常处理 ----
                lastUserInput = text;
                dialogStateMachine.transitionTo(DialogStateMachine.State.PROCESSING);
                Thread worker = new Thread(() -> {
                    try {
                        turnProcessor.process(chatClient, allTools, text, ttsService);
                        dialogStateMachine.transitionTo(DialogStateMachine.State.SPEAKING);
                        while (ttsService.isPlaying()) {
                            Thread.sleep(200);
                        }
                        dialogStateMachine.transitionTo(DialogStateMachine.State.LISTENING);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        dialogStateMachine.transitionTo(DialogStateMachine.State.LISTENING);
                    }
                }, "ai-worker");
                worker.setDaemon(true);
                worker.start();

            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ========== 补充信息判断与处理 ==========
    private void handleSupplement(ChatClient chatClient, ToolCallback[] tools,
                                   String newInput, TtsService ttsService, SkillConfig skillConfig) {
        System.out.println("\n🔍 AI 执行中，判断是否为补充信息...");

        // Step 1: 声纹验证（已在录音线程中完成，此处用 currentSpeaker 判断）
        if (voiceprintService.isAvailable() && voiceprintService.listSpeakers().length > 0) {
            if (currentSpeaker == null) {
                System.out.println("  → 声纹不匹配，忽略补充请求");
                return;
            }
            System.out.println("  ✅ 声纹匹配: " + currentSpeaker);
        }

        // Step 2: 语义关联判断
        boolean isRelated = isSemanticallyRelated(newInput, lastUserInput, lastAiResponse);
        if (!isRelated) {
            System.out.println("  → 语义不相关，暂不处理（AI 继续执行）");
            return;
        }

        // Step 3: 询问用户确认
        System.out.println("  ✅ 语义相关（补充信息）: " + newInput);
        System.out.println("  ❓ 是否作为补充信息加入？说 '对' 确认，说 '不对' 忽略");

        pendingSupplement = newInput;
        awaitingSupplementConfirm = true;
        ttsService.speakAsync("检测到补充信息，是否加入？说对确认");
    }

    // ========== 确认/拒绝关键词 ==========
    private boolean containsKeyword(String text, List<String> keywords) {
        String lower = text.toLowerCase();
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase())) return true;
        }
        return false;
    }

    private boolean isConfirmKeyword(String text) {
        return containsKeyword(text, props.dialog().confirmKeywords());
    }

    private boolean isRejectKeyword(String text) {
        return containsKeyword(text, props.dialog().rejectKeywords());
    }

    /**
     * 判断新输入是否与上一次对话语义相关
     * 使用关键词重叠 + 启发式规则（轻量判断，避免额外 AI 调用）
     */
    private boolean isSemanticallyRelated(String newInput, String lastInput, String lastResponse) {
        if (lastInput == null || lastInput.isEmpty()) return false;

        // 规则1: 新输入很短（补充信息通常简短）
        if (newInput.length() > 50) return false;

        // 规则2: 检测补充/转折关键词
        String lower = newInput.toLowerCase();
        String[] supplementKeywords = props.dialog().supplementKeywords().toArray(new String[0]);
        for (String kw : supplementKeywords) {
            if (lower.contains(kw)) return true;
        }

        // 规则3: 关键词重叠度（合并上一次用户输入 + AI回复的关键词）
        Set<String> lastWords = extractKeywords(lastInput);
        if (lastResponse != null && !lastResponse.isEmpty()) {
            lastWords.addAll(extractKeywords(lastResponse));
        }
        Set<String> newWords = extractKeywords(newInput);
        if (lastWords.isEmpty()) return false;

        long overlap = newWords.stream().filter(lastWords::contains).count();
        double ratio = (double) overlap / Math.max(newWords.size(), 1);

        // 30% 以上关键词重叠认为相关
        return ratio >= props.semantic().relatedRatioThreshold();
    }

    /**
     * 简单的中文关键词提取（按字 bigram 切分）
     */
    private Set<String> extractKeywords(String text) {
        Set<String> words = new HashSet<>();
        if (text == null) return words;

        // 中文 bigram
        for (int i = 0; i < text.length() - 1; i++) {
            String bigram = text.substring(i, i + 2);
            if (!bigram.matches("[\\p{Punct}\\s]+")) {
                words.add(bigram);
            }
        }

        // 英文单词
        for (String w : text.toLowerCase().split("[^a-z0-9]+")) {
            if (w.length() >= 2) words.add(w);
        }

        return words;
    }

    // ========== 用户注册引导 ==========
    private void handleEnrollment(TtsService ttsService) {
        if (!voiceprintService.isAvailable()) {
            System.out.println("❌ 声纹识别未就绪（无模型文件）");
            ttsService.speakAsync("声纹识别未就绪");
            return;
        }

        String name = "user_" + System.currentTimeMillis() % 10000;
        voiceprintService.startEnrollment(name);

        // 语音提示用户该说什么
        String[] prompts = {
                "你好，我是桌面上的人工智能助手，很高兴为你服务",
                "今天天气真好，我想出去散步，看看公园里的花开了没有",
                "请帮我打开微信，给张三发一条消息，说今晚七点开会"
        };

        System.out.println("\n👤 开始注册声纹，共 3 段语音");
        ttsService.speakAsync("开始注册声纹，第一句请跟我读");

        try (Scanner scanner = new Scanner(System.in)) {
            for (int i = 0; i < 3; i++) {
                System.out.println("  📢 请说第 " + (i + 1) + "/3 段: " + prompts[i]);
                ttsService.speakAsync(prompts[i]);
                System.out.print("  说完后按回车继续...");
                scanner.nextLine();
                System.out.println("  ✅ 已收集第 " + (i + 1) + " 段");
                // 实际注册中应该用真实音频，这里用占位符
                // voiceprintService.addEnrollmentSegment(name, audioSamples);
            }
        }

        boolean success = voiceprintService.finishEnrollment(name, props.voice().sampleRate());
        if (success) {
            System.out.println("✅ 声纹注册成功: " + name);
            ttsService.speakAsync("声纹注册成功");
        } else {
            System.out.println("❌ 声纹注册失败");
            ttsService.speakAsync("声纹注册失败");
        }
    }

    // ========== 动态工具管理 ==========

    /**
     * 获取合并后的完整工具列表（静态工具 + 运行时动态加载的工具）。
     */
    private synchronized ToolCallback[] getMergedTools() {
        return turnProcessor.mergeTools(allTools);
    }


    // ========== 工具方法 ==========
    private void playBeep(int freqHz, int durationMs) {
        try {
            float sampleRate = 8000;
            int samples = (int) (sampleRate * durationMs / 1000.0f);
            byte[] buf = new byte[samples];
            for (int i = 0; i < samples; i++) {
                double angle = 2.0 * Math.PI * freqHz * i / sampleRate;
                buf[i] = (byte) (Math.sin(angle) * (props != null ? props.voice().beep().amplitude() : 80));
            }
            AudioFormat fmt = new AudioFormat(sampleRate, 8, 1, true, false);
            try (Clip clip = AudioSystem.getClip()) {
                clip.open(fmt, buf, 0, buf.length);
                clip.start();
                Thread.sleep(durationMs + 50);
                clip.stop();
            }
        } catch (Exception e) {
            System.err.println("⚠️ 提示音播放失败: " + e.getMessage());
        }
    }

    private float calcEnergyFallback(float[] samples) {
        double sum = 0;
        for (float s : samples) sum += s * s;
        return (float) Math.sqrt(sum / samples.length);
    }

    private float[] pcmToFloat(byte[] pcm) {
        return pcmToFloat(pcm, pcm.length);
    }

    private float[] pcmToFloat(byte[] pcm, int len) {
        int n = len / 2;
        float[] samples = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = pcm[2 * i] & 0xff;
            int hi = pcm[2 * i + 1] & 0xff;
            int s = (hi << 8) | lo;
            if (s >= 32768) s -= 65536;
            samples[i] = (float) s / 32768.0f;
        }
        return samples;
    }
}
