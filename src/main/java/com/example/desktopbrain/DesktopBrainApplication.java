package com.example.desktopbrain;

import com.example.desktopbrain.dialog.DialogStateMachine;
import com.example.desktopbrain.dialog.SpeechAssembler;
import com.example.desktopbrain.memory.vector.episode.Episode;
import com.example.desktopbrain.memory.vector.episode.PlanExecutor;
import com.example.desktopbrain.memory.vector.episode.PlanMatcher;
import com.example.desktopbrain.memory.vector.episode.ReflectService;
import com.example.desktopbrain.memory.vector.episode.ToolCallLog;
import com.example.desktopbrain.service.AudioRecorder;
import com.example.desktopbrain.service.CapabilityService;
import com.example.desktopbrain.service.FriendMatcher;
import com.example.desktopbrain.service.LocalASR;
import com.example.desktopbrain.service.LoggingToolCallback;
import com.example.desktopbrain.service.SkillConfig;
import com.example.desktopbrain.service.ToolPlanner;
import com.example.desktopbrain.service.TtsService;
import com.example.desktopbrain.service.VadService;
import com.example.desktopbrain.service.VoiceprintService;
import com.example.desktopbrain.integration.HaToolService;
import com.example.desktopbrain.util.NativeLoader;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@SpringBootApplication
@ComponentScan(basePackages = {"com.example.tools.windows", "com.example.desktopbrain", "com.example.desktopbrain.memory", "com.example.desktopbrain.integration"})
public class DesktopBrainApplication {

    // ========== 配置 ==========
    private static final String WAKE_WORD = "那个谁";
    private static final int SAMPLE_RATE = 16000;
    private static final float ENERGY_THRESHOLD = 0.02f;

    // ========== 状态 ==========
    private enum Mode { IDLE, VOICE_DIALOG }
    private volatile Mode mode = Mode.IDLE;

    // ========== 对话系统 ==========
    private volatile DialogStateMachine dialogStateMachine;
    private volatile SpeechAssembler speechAssembler;

    // ========== AI 中断控制 ==========
    private final AtomicLong aiTurnId = new AtomicLong(0);
    private volatile long currentAiTurnId = -1;
    private volatile int silenceCount = 0;
    private volatile ToolPlanner toolPlanner;
    private volatile PlanMatcher planMatcher;
    private volatile ReflectService reflectService;
    private volatile PlanExecutor planExecutor;
    private volatile VoiceprintService voiceprintService;

    // ========== 补充信息 ==========
    private volatile String lastUserInput = "";        // 最近一次用户输入
    private volatile String lastAiResponse = "";        // 最近一次 AI 回复（用于语义关联）
    private volatile String currentSpeaker = null;       // 当前识别到的说话人
    private volatile String pendingSupplement = null;    // 待确认的补充信息
    private volatile boolean awaitingSupplementConfirm = false; // 等待用户确认补充
    private volatile boolean enrollmentHinted = false;  // 是否已提示过注册声纹（只提示一次）

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
                                 ToolPlanner toolPlanner,
                                 PlanMatcher planMatcher,
                                 ReflectService reflectService,
                                 PlanExecutor planExecutor,
                                 HaToolService haToolService,
                                 VoiceprintService voiceprintService,
                                 DialogStateMachine dialogStateMachine,
                                 SpeechAssembler speechAssembler) {
        return args -> {
            // 合并 MCP 工具 + 本地工具
            int mcpCount = mcpTools.getToolCallbacks().length;
            ToolCallback[] localTools = MethodToolCallbackProvider.builder()
                    .toolObjects(friendMatcher, capabilityService, haToolService)
                    .build()
                    .getToolCallbacks();
            int localCount = localTools.length;
            ToolCallback[] allToolCallbacks = Stream.concat(
                    Arrays.stream(mcpTools.getToolCallbacks()),
                    Arrays.stream(localTools)
            ).toArray(ToolCallback[]::new);

            this.toolPlanner = toolPlanner;
            this.planMatcher = planMatcher;
            this.reflectService = reflectService;
            this.planExecutor = planExecutor;
            this.voiceprintService = voiceprintService;
            this.dialogStateMachine = dialogStateMachine;
            this.speechAssembler = speechAssembler;

            ChatClient chatClient = chatClientBuilder
                    .defaultSystem("""
                            你是一个 Windows 桌面助手，能够通过工具控制鼠标、键盘、窗口和屏幕。
                            你需要将用户的自然语言指令转换为具体的工具调用。
                            如果用户的要求不明确，请向用户提问澄清。
                            重要规则：
                            - 每次工具调用尽量一步到位，不要反复确认
                            - 工具调用失败时最多重试1次，不要反复重试
                            - 每步操作后等待界面响应即可，不需要额外验证
                            - 当用户问"你有什么技能"/"你能做什么"/"技能列表"时：
                              1. 调用 listLocalSkills 获取本地技能
                              2. 结合你已有的 MCP 工具（GUI操控、截图、OCR、键盘鼠标等），
                                 汇总列出所有能力，格式为"本地技能：...\nMCP工具：..."
                            """)
                    .build();

            System.out.println("🤖 桌面助手已启动（贾维斯模式）");
            System.out.println("💡 文字输入：直接对话，回复有语音播报");
            System.out.println("💡 语音唤醒：说 '" + WAKE_WORD + "' 进入语音对话");
            System.out.println("💡 语音模式：说 '停' 中断，说 '退出' 结束");
            System.out.println("📊 技能数: " + skillConfig.getSkillNames().size() + " 个");
            System.out.println("🔧 工具数: MCP " + mcpCount + " + 本地 " + localCount + " = " + allToolCallbacks.length + " 个");

            // 启动后台录音线程
            Thread recorderThread = new Thread(() -> startBackgroundRecording(localASR, vadService, ttsService), "bg-recorder");
            recorderThread.setDaemon(true);
            recorderThread.start();

            // 启动语音事件处理线程
            Thread handlerThread = new Thread(() -> handleSpeechEvents(chatClient, allToolCallbacks, ttsService, skillConfig, vadService), "speech-handler");
            handlerThread.setDaemon(true);
            handlerThread.start();

            // 主线程：文字对话
            try (Scanner scanner = new Scanner(System.in)) {
                while (true) {
                    System.out.print("\n> ");
                    String userInput = scanner.nextLine();
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
                                processAITurn(chatClient, allToolCallbacks, text, ttsService, skillConfig);
                            }
                        } catch (Exception e) {
                            System.err.println("❌ 录音或识别失败: " + e.getMessage());
                        }
                        continue;
                    }

                    // 文字对话
                    processAITurn(chatClient, allToolCallbacks, userInput, ttsService, skillConfig);
                }
            }
        };
    }

    // ========== 后台录音线程（Silero VAD） ==========
    private void startBackgroundRecording(LocalASR localASR, VadService vadService, TtsService ttsService) {
        TargetDataLine line = null;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
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
            float[] trailBuf = new float[SAMPLE_RATE / 5];
            int trailPos = 0;
            long ttsEchoUntil = 0;

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
                    ttsEchoUntil = System.currentTimeMillis() + 300;
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

                if (useVad) {
                    vadService.acceptWaveform(samples);
                    while (vadService.hasSegment()) {
                        SpeechSegment seg = vadService.nextSegment();
                        float[] speech = seg.getSamples();
                        if (speech.length > SAMPLE_RATE * 0.25f) {
                            if (calcEnergyFallback(speech) < ENERGY_THRESHOLD) continue;
                            float[] withTrail = new float[speech.length + trailBuf.length];
                            System.arraycopy(speech, 0, withTrail, 0, speech.length);
                            for (int j = 0; j < trailBuf.length; j++) {
                                withTrail[speech.length + j] = trailBuf[(trailPos + j) % trailBuf.length];
                            }

                            // 声纹验证（在 ASR 之前用原始音频）
                            String speaker = null;
                            if (voiceprintService.isAvailable() && voiceprintService.listSpeakers().length > 0) {
                                speaker = voiceprintService.search(withTrail, SAMPLE_RATE);
                                currentSpeaker = speaker;
                            }

                            String text;
                            if (mode == Mode.VOICE_DIALOG && localASR.isOfflineAvailable()) {
                                text = localASR.recognizeOffline(withTrail, SAMPLE_RATE);
                            } else {
                                text = localASR.recognizeOnline(withTrail, SAMPLE_RATE);
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
                        playBeep(800, 80);
                    }
                    wasSpeaking = nowSpeaking;
                } else {
                    float energy = calcEnergyFallback(samples);
                    if (!isSpeaking) {
                        if (energy > ENERGY_THRESHOLD) {
                            isSpeaking = true;
                            speechStart = System.currentTimeMillis();
                            silenceStart = 0;
                            energyBuf.reset();
                            System.out.print("\r🎤 正在听... ");
                            playBeep(800, 80);
                        }
                    } else {
                        energyBuf.write(buffer, 0, count);
                        if (energy < ENERGY_THRESHOLD) {
                            if (silenceStart == 0) silenceStart = System.currentTimeMillis();
                            if (System.currentTimeMillis() - silenceStart > 2000) {
                                if (System.currentTimeMillis() - speechStart > 400) {
                                    float[] energySamples = pcmToFloat(energyBuf.toByteArray());

                                    // 声纹验证
                                    String speaker = null;
                                    if (voiceprintService.isAvailable() && voiceprintService.listSpeakers().length > 0) {
                                        speaker = voiceprintService.search(energySamples, SAMPLE_RATE);
                                        currentSpeaker = speaker;
                                    }

                                    String text;
                                    if (mode == Mode.VOICE_DIALOG && localASR.isOfflineAvailable()) {
                                        text = localASR.recognizeOffline(energySamples, SAMPLE_RATE);
                                    } else {
                                        text = localASR.recognizeOnline(energySamples, SAMPLE_RATE);
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
                        if (System.currentTimeMillis() - speechStart > 10000) {
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
                try { line.stop(); line.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ========== 语音事件处理线程（基于 DialogStateMachine + SpeechAssembler） ==========
    private void handleSpeechEvents(ChatClient chatClient, ToolCallback[] tools, TtsService ttsService, SkillConfig skillConfig, VadService vadService) {
        // 检查声纹状态
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
                String text = speechQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                DialogStateMachine.State state = dialogStateMachine.getState();

                // ========== IDLE 状态：模糊监听，只检测唤醒词 ==========
                if (state == DialogStateMachine.State.IDLE) {
                    if (text == null) {
                        System.out.print("\r💤 模糊监听中...");
                        continue;
                    }
                    if (text.contains(WAKE_WORD)) {
                        dialogStateMachine.transitionTo(DialogStateMachine.State.LISTENING);
                        mode = Mode.VOICE_DIALOG;
                        silenceCount = 0;
                        System.out.println("\n✨ 已唤醒！进入语音对话模式");
                        ttsService.speakAsync("我在");

                        // 未注册时只提示一次
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
                        // TTS 播放中：保持 LISTENING，等待自然打断或播放完毕
                        silenceCount = 0;
                    } else if (vadService.isAvailable() && vadService.isSpeech()) {
                        silenceCount = 0;
                    } else {
                        silenceCount++;
                        // AI 没在跑 + 5 秒无语音 → 回到 IDLE
                        if (currentAiTurnId == -1 && silenceCount >= 10
                                && dialogStateMachine.getState() == DialogStateMachine.State.LISTENING) {
                            dialogStateMachine.transitionTo(DialogStateMachine.State.IDLE);
                            mode = Mode.IDLE;
                            silenceCount = 0;
                            System.out.println("\n⏰ 5 秒无语音，回到待命");
                            ttsService.speakAsync("已回到待命模式");
                        }
                    }
                    continue;
                }

                silenceCount = 0;

                // ========== SPEAKING / PROCESSING 状态：自然打断 ==========
                if (state == DialogStateMachine.State.SPEAKING || state == DialogStateMachine.State.PROCESSING) {
                    // 声纹检查（已注册时只响应本人声音）
                    if (voiceprintAvailable && hasVoiceprints && currentSpeaker == null) {
                        System.out.println("\n🔇 声纹不匹配，忽略打断");
                        continue;
                    }

                    System.out.println("\n⚡ 自然打断！");
                    dialogStateMachine.transitionTo(DialogStateMachine.State.INTERRUPTED);
                    interruptCurrentAi();
                    ttsService.stop();
                    playBeep(400, 80);
                    // 打断后的内容作为新指令处理（继续往下走）
                }

                // ---- 声纹标注 ----
                if (voiceprintAvailable && hasVoiceprints) {
                    System.out.println("\n🎤 听到 [" + (currentSpeaker != null ? currentSpeaker : "未识别") + "]: " + text);
                } else {
                    System.out.println("\n📝 你: " + text);
                }

                playBeep(600, 50);

                // ---- 指令处理 ----
                if (text.contains("退出") || text.contains("再见") || text.contains("结束对话")) {
                    dialogStateMachine.transitionTo(DialogStateMachine.State.IDLE);
                    mode = Mode.IDLE;
                    interruptCurrentAi();
                    ttsService.stop();
                    System.out.println("👋 对话结束，回到待命");
                    ttsService.speakAsync("再见");
                    continue;
                }

                if (text.contains("停") || text.contains("打断") || text.contains("取消")) {
                    interruptCurrentAi();
                    ttsService.stop();
                    System.out.println("🛑 已中断，请继续说");
                    silenceCount = 0;
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

                        long interruptedId = currentAiTurnId;
                        interruptCurrentAi();

                        String combinedInput = lastUserInput + "\n\n--- 补充信息 ---\n" + supplement;
                        System.out.println("🔄 确认补充，中断 AI (turn=" + interruptedId + ")，带补充信息重新执行...");
                        System.out.println("  原请求: " + lastUserInput);
                        System.out.println("  补充: " + supplement);

                        dialogStateMachine.transitionTo(DialogStateMachine.State.PROCESSING);
                        Thread worker = new Thread(() -> processAITurn(chatClient, tools, combinedInput, ttsService, skillConfig), "ai-worker-supplement");
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
                        Thread worker = new Thread(() -> processAITurn(chatClient, tools, text, ttsService, skillConfig), "ai-worker");
                        worker.setDaemon(true);
                        worker.start();
                    }
                    continue;
                }

                // ---- AI 执行中：补充信息判断 ----
                if (currentAiTurnId != -1 && !dialogStateMachine.canInterrupt()) {
                    handleSupplement(chatClient, tools, text, ttsService, skillConfig);
                    continue;
                }

                // ---- 正常处理 ----
                lastUserInput = text;
                dialogStateMachine.transitionTo(DialogStateMachine.State.PROCESSING);
                Thread worker = new Thread(() -> {
                    try {
                        processAITurn(chatClient, tools, text, ttsService, skillConfig);
                        // AI 处理完进入 SPEAKING（TTS），再回到 LISTENING
                        dialogStateMachine.transitionTo(DialogStateMachine.State.SPEAKING);
                        // 等 TTS 播完：isPlaying() 是 volatile 状态，播完必归位；
                        // 用户打断时事件线程会调 ttsService.stop()，playAudio 检测 stopFlag 立即退出
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
    private boolean isConfirmKeyword(String text) {
        String lower = text.toLowerCase();
        return lower.contains("对") || lower.contains("是") || lower.contains("确认")
                || lower.contains("好的") || lower.contains("可以") || lower.contains("加")
                || lower.contains("用") || lower.contains("继续") || lower.contains("加上")
                || lower.contains("整")|| lower.contains("行")|| lower.contains("可以")
                || lower.contains("ok");
    }

    private boolean isRejectKeyword(String text) {
        String lower = text.toLowerCase();
        return lower.contains("不对") || lower.contains("不是") || lower.contains("不要")
                || lower.contains("忽略") || lower.contains("算了") || lower.contains("不用")
                || lower.contains("取消") || lower.contains("不整") || lower.contains("no")
                || lower.contains("不行");
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
        String[] supplementKeywords = {"补充", "对了", "还有", "顺便", "另外", "忘了", "那个",
                "不对", "更正", "改一下", "等一下", "等会", "哦", "嗯"};
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
        return ratio >= 0.3;
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

        boolean success = voiceprintService.finishEnrollment(name, SAMPLE_RATE);
        if (success) {
            System.out.println("✅ 声纹注册成功: " + name);
            ttsService.speakAsync("声纹注册成功");
        } else {
            System.out.println("❌ 声纹注册失败");
            ttsService.speakAsync("声纹注册失败");
        }
    }

    // ========== 核心：AI 处理（完整 Phase 1 逻辑） ==========
    private void processAITurn(ChatClient chatClient, ToolCallback[] tools, String userInput, TtsService ttsService, SkillConfig skillConfig) {
        long myTurnId = aiTurnId.incrementAndGet();
        currentAiTurnId = myTurnId;
        silenceCount = 0;

        System.out.println("🤖 思考中...");

        String effectiveInput = userInput;
        List<ToolCallLog> toolCallLogs = Collections.synchronizedList(new ArrayList<>());

        // 1. 技能匹配
        String skillInstructions = skillConfig.getInstructions(userInput);
        if (!skillInstructions.isEmpty()) {
            effectiveInput = skillInstructions + "\n用户请求：" + userInput;
            System.out.println("📋 已注入技能: " + skillConfig.getMatchedSkillNames(userInput));
        }

        // 2. 工具规划（三层缓存）
        ToolPlanner.PlanResult plan = toolPlanner.plan(userInput, tools);
        plan.missingDescriptions().forEach(desc ->
                System.out.println("⚠️ 缺少工具: " + desc + "（可补写本地 @Tool）"));

        // 3. 命中缓存 → 走缓存逻辑；未命中 → 新规划
        if (plan.fromCache() && plan.episode() != null) {
            handleCacheHit(chatClient, tools, effectiveInput, userInput, plan, toolCallLogs, myTurnId, ttsService);
        } else {
            handleNewPlan(chatClient, tools, effectiveInput, userInput, plan, toolCallLogs, myTurnId, ttsService);
        }

        // 记录最近的 AI 回复（用于补充信息判断）
        lastAiResponse = "处理完成";  // 简化：实际应获取完整 AI 回复

        if (currentAiTurnId == myTurnId) {
            currentAiTurnId = -1;
            silenceCount = 0;
        }
    }

    // ========== 缓存命中处理 ==========
    private void handleCacheHit(ChatClient chatClient, ToolCallback[] tools,
                                 String effectiveInput, String userInput,
                                 ToolPlanner.PlanResult plan, List<ToolCallLog> toolCallLogs,
                                 long myTurnId, TtsService ttsService) {
        Episode episode = plan.episode();

        // Step 1: AI 判断计划可用性 + 提取变量
        PlanMatcher.MatchResult matchResult = planMatcher.match(userInput, episode);
        if (!matchResult.applicable()) {
            System.out.println("❌ 计划不适用（" + matchResult.reason() + "），降级为 AI 新规划");
            handleNewPlan(chatClient, tools, effectiveInput, userInput,
                    ToolPlanner.PlanResult.ofAIPlan(plan.selectedToolNames(), plan.missingDescriptions()),
                    toolCallLogs, myTurnId, ttsService);
            return;
        }

        Map<String, String> variables = matchResult.variables();
        System.out.println("✅ 计划可用（变量: " + variables + "）");

        // Step 2: 附加 lesson 到 prompt
        String augmentedInput = effectiveInput;
        if (episode.successLesson() != null && !episode.successLesson().isEmpty()) {
            augmentedInput += "\n\n--- 历史成功经验 ---\n上次类似任务成功经验: " + episode.successLesson();
        }
        if (episode.failureLesson() != null && !episode.failureLesson().isEmpty()) {
            augmentedInput += "\n\n--- 历史失败教训 ---\n注意避免: " + episode.failureLesson();
        }

        // Step 3: 判断是否可脚本化
        if (episode.isScriptable()) {
            System.out.println("🚀 计划稳定度高（可脚本化），跳过 AI 直接执行脚本");
            PlanExecutor.ExecutionResult execResult = planExecutor.executeScript(episode, variables, tools);

            if (execResult.success()) {
                if (currentAiTurnId == myTurnId) {
                    toolPlanner.onCacheHitSuccess(userInput, plan);
                    String response = "已按脚本完成（" + execResult.executedSteps().size() + " 步）";
                    System.out.println("🤖 " + response);
                    ttsService.speakAsync(response);
                }
            } else {
                // 脚本失败 → AI 归因
                ReflectService.FailureAnalysis analysis = reflectService.reflectFailure(userInput, toolCallLogs, execResult.errorMessage());
                System.out.println("🔍 脚本归因: " + (analysis.isPlanIssue() ? "计划问题" : "环境问题") +
                        (analysis.lesson() != null ? "（" + analysis.lesson() + "）" : ""));

                if (!analysis.isPlanIssue()) {
                    // 环境问题 → 分段继续
                    int fromStep = execResult.failedStepIndex() + 1;
                    if (fromStep < episode.toolCalls().size()) {
                        System.out.println("ℹ️ 脚本环境问题，从第 " + (fromStep + 1) + " 步继续执行");
                        PlanExecutor.ExecutionResult continueResult = planExecutor.executeFromStep(episode, fromStep, variables, tools);
                        if (continueResult.success()) {
                            if (currentAiTurnId == myTurnId) {
                                toolPlanner.onCacheHitSuccess(userInput, plan);
                                int totalSteps = execResult.executedSteps().size() + continueResult.executedSteps().size();
                                String response = "已从失败处继续完成（共 " + totalSteps + " 步）";
                                System.out.println("🤖 " + response);
                                ttsService.speakAsync(response);
                            }
                        } else {
                            // 分段继续也失败 → 重新规划
                            toolPlanner.onCacheHitFailure(userInput, plan, analysis.lesson(), analysis.isPlanIssue());
                            handleCacheFailure(chatClient, tools, effectiveInput, userInput, plan,
                                    toolCallLogs, myTurnId, ttsService, execResult.errorMessage());
                        }
                    } else {
                        toolPlanner.onCacheHitFailure(userInput, plan, analysis.lesson(), analysis.isPlanIssue());
                        handleCacheFailure(chatClient, tools, effectiveInput, userInput, plan,
                                toolCallLogs, myTurnId, ttsService, execResult.errorMessage());
                    }
                } else {
                    // 计划问题 → 重新规划
                    toolPlanner.onCacheHitFailure(userInput, plan, analysis.lesson(), analysis.isPlanIssue());
                    handleCacheFailure(chatClient, tools, effectiveInput, userInput, plan,
                            toolCallLogs, myTurnId, ttsService, execResult.errorMessage());
                }
            }
        } else {
            // 非脚本化 → AI 带参考计划执行
            try {
                String response = executeWithTools(chatClient, augmentedInput, tools, plan, toolCallLogs);
                if (currentAiTurnId == myTurnId) {
                    System.out.println("🤖 " + response);
                    toolPlanner.onCacheHitSuccess(userInput, plan);
                    ttsService.speakAsync(response);
                }
            } catch (Exception e) {
                if (currentAiTurnId == myTurnId) {
                    ReflectService.FailureAnalysis analysis = reflectService.reflectFailure(userInput, toolCallLogs, e.getMessage());
                    System.out.println("🔍 归因: " + (analysis.isPlanIssue() ? "计划问题" : "环境问题") +
                            (analysis.lesson() != null ? "（" + analysis.lesson() + "）" : ""));
                    toolPlanner.onCacheHitFailure(userInput, plan, analysis.lesson(), analysis.isPlanIssue());

                    if (!analysis.isPlanIssue()) {
                        // 环境问题 → 分段继续
                        System.out.println("ℹ️ 环境问题导致失败，分段继续执行");
                        String continuePrompt = buildContinuePrompt(userInput, e.getMessage(), analysis.lesson());
                        toolCallLogs.clear();
                        ToolPlanner.PlanResult fallbackPlan = toolPlanner.plan(userInput, tools);
                        String response = executeWithTools(chatClient, continuePrompt, tools, fallbackPlan, toolCallLogs);
                        if (currentAiTurnId == myTurnId) {
                            System.out.println("🤖 " + response);
                            toolPlanner.onCacheHitSuccess(userInput, plan);
                            ttsService.speakAsync(response);
                        }
                    } else {
                        // 计划问题 → 重新规划
                        handleCacheFailure(chatClient, tools, effectiveInput, userInput, plan,
                                toolCallLogs, myTurnId, ttsService, e.getMessage());
                    }
                }
            }
        }
    }

    // ========== 新规划处理（首次 + 降级） ==========
    private void handleNewPlan(ChatClient chatClient, ToolCallback[] tools,
                                String effectiveInput, String userInput,
                                ToolPlanner.PlanResult plan, List<ToolCallLog> toolCallLogs,
                                long myTurnId, TtsService ttsService) {
        // 决策4：执行前创建 DRAFT
        String draftId = toolPlanner.createDraftEpisode(userInput, plan);

        try {
            String response = executeWithTools(chatClient, effectiveInput, tools, plan, toolCallLogs);
            if (currentAiTurnId == myTurnId) {
                System.out.println("🤖 " + response);

                // Reflect 成功
                String successLesson = reflectService.reflectSuccess(userInput, toolCallLogs, response);
                if (successLesson != null) {
                    System.out.println("📝 成功经验: " + successLesson);
                }

                // DRAFT → ACTIVE
                toolPlanner.activateDraftEpisode(draftId, toolCallLogs, response, successLesson);

                // 写入内存缓存
                toolPlanner.cacheToMemory(userInput, plan, draftId, toolCallLogs, response, successLesson);

                ttsService.speakAsync(response);
            }
        } catch (Exception e) {
            if (currentAiTurnId == myTurnId) {
                // Reflect 失败 + 归因
                ReflectService.FailureAnalysis analysis = reflectService.reflectFailure(userInput, toolCallLogs, e.getMessage());
                System.out.println("🔍 失败归因: " + (analysis.isPlanIssue() ? "计划问题" : "环境问题") +
                        (analysis.lesson() != null ? "（" + analysis.lesson() + "）" : ""));

                // 决策4：失败也保存步骤
                toolPlanner.failDraftEpisode(draftId, toolCallLogs, analysis.lesson(), -1);

                System.out.println("❌ " + e.getMessage());

                // 环境问题 → 分段继续
                if (!analysis.isPlanIssue()) {
                    System.out.println("ℹ️ 环境问题，尝试分段继续");
                    String continuePrompt = buildContinuePrompt(userInput, e.getMessage(), analysis.lesson());
                    toolCallLogs.clear();
                    String response = executeWithTools(chatClient, continuePrompt, tools, plan, toolCallLogs);
                    if (currentAiTurnId == myTurnId) {
                        System.out.println("🤖 " + response);
                        toolPlanner.activateDraftEpisode(draftId, toolCallLogs, response, null);
                        toolPlanner.cacheToMemory(userInput, plan, draftId, toolCallLogs, response, null);
                        ttsService.speakAsync(response);
                    }
                }
                // 计划问题 → 已存为 FAILED，让用户知道
            }
        }
    }

    // ========== 缓存失败处理（重新规划） ==========
    private void handleCacheFailure(ChatClient chatClient, ToolCallback[] tools,
                                     String effectiveInput, String userInput,
                                     ToolPlanner.PlanResult oldPlan, List<ToolCallLog> toolCallLogs,
                                     long myTurnId, TtsService ttsService, String errorReason) {
        try {
            // 用失败原因重新规划
            ToolPlanner.PlanResult newPlan = toolPlanner.replan(userInput, tools, errorReason);
            newPlan.missingDescriptions().forEach(desc ->
                    System.out.println("⚠️ 缺少工具: " + desc + "（可补写本地 @Tool）"));
            System.out.println("📦 重新选用工具: " + newPlan.selectedToolNames().size() + "/" + tools.length);

            // 创建新 draft
            String newDraftId = toolPlanner.createDraftEpisode(userInput, newPlan);

            String response = executeWithTools(chatClient, effectiveInput, tools, newPlan, toolCallLogs);
            if (currentAiTurnId == myTurnId) {
                System.out.println("🤖 " + response);

                // 新计划成功 → Reflect + 存
                String successLesson = reflectService.reflectSuccess(userInput, toolCallLogs, response);
                toolPlanner.activateDraftEpisode(newDraftId, toolCallLogs, response, successLesson);
                toolPlanner.cacheToMemory(userInput, newPlan, newDraftId, toolCallLogs, response, successLesson);

                ttsService.speakAsync(response);
            }
        } catch (Exception retryEx) {
            if (currentAiTurnId == myTurnId) {
                System.out.println("❌ 重试失败: " + retryEx.getMessage());
                ttsService.speakAsync("抱歉，我没做好这个任务");
            }
        }
    }

    // ========== AI 执行（过滤工具 + 收集日志） ==========
    private String executeWithTools(ChatClient chatClient, String input,
                                    ToolCallback[] allTools, ToolPlanner.PlanResult plan,
                                    List<ToolCallLog> toolCallLogs) {
        // 过滤出选中的工具
        ToolCallback[] selectedTools = Arrays.stream(allTools)
                .filter(tc -> plan.selectedToolNames().contains(tc.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);

        // 包装为带日志的工具
        ToolCallback[] loggedTools = Arrays.stream(selectedTools)
                .map(tc -> new LoggingToolCallback(tc, toolCallLogs))
                .toArray(ToolCallback[]::new);

        System.out.println("📦 选用工具: " + selectedTools.length + "/" + allTools.length
                + (plan.fromCache() ? " (缓存命中)" : ""));

        try {
            return chatClient.prompt()
                    .user(input)
                    .toolCallbacks(loggedTools)
                    .call()
                    .content();
        } catch (Exception e) {
            if (plan.fromCache()) {
                throw new RuntimeException("🔄 缓存方案执行失败", e);
            }
            throw e;
        }
    }

    // ========== 构建分段继续的 prompt ==========
    private String buildContinuePrompt(String originalInput, String errorMsg, String failureLesson) {
        StringBuilder sb = new StringBuilder(originalInput);
        sb.append("\n\n--- 分段继续执行 ---\n");
        sb.append("上次执行失败: ").append(errorMsg).append("\n");
        if (failureLesson != null && !failureLesson.isEmpty()) {
            sb.append("注意: ").append(failureLesson).append("\n");
        }
        sb.append("请继续完成上述任务，忽略已完成的步骤。");
        return sb.toString();
    }

    // ========== 中断 ==========
    private boolean interruptCurrentAi() {
        if (currentAiTurnId != -1) {
            currentAiTurnId = -1;
            return true;
        }
        return false;
    }

    // ========== 工具方法 ==========
    private void playBeep(int freqHz, int durationMs) {
        try {
            float sampleRate = 8000;
            int samples = (int) (sampleRate * durationMs / 1000.0f);
            byte[] buf = new byte[samples];
            for (int i = 0; i < samples; i++) {
                double angle = 2.0 * Math.PI * freqHz * i / sampleRate;
                buf[i] = (byte) (Math.sin(angle) * 80);
            }
            AudioFormat fmt = new AudioFormat(sampleRate, 8, 1, true, false);
            try (Clip clip = AudioSystem.getClip()) {
                clip.open(fmt, buf, 0, buf.length);
                clip.start();
                Thread.sleep(durationMs + 50);
                clip.stop();
            }
        } catch (Exception ignored) {}
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
