package com.example.desktopbrain;

import com.example.desktopbrain.service.AudioRecorder;
import com.example.desktopbrain.service.CapabilityService;
import com.example.desktopbrain.service.FriendMatcher;
import com.example.desktopbrain.service.LocalASR;
import com.example.desktopbrain.service.LoggingToolCallback;
import com.example.desktopbrain.service.SkillConfig;
import com.example.desktopbrain.service.ToolPlanner;
import com.example.desktopbrain.service.TtsService;
import com.example.desktopbrain.service.VadService;
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
import java.util.Arrays;
import java.util.Scanner;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

@SpringBootApplication
@ComponentScan(basePackages = {"com.example.tools.windows", "com.example.desktopbrain.service", "com.example.desktopbrain.memory"})
public class DesktopBrainApplication {

    // ========== 配置 ==========
    private static final String WAKE_WORD = "那个谁";
    private static final int SAMPLE_RATE = 16000;
    // 能量检测回退参数（VAD 不可用时启用）
    private static final float ENERGY_THRESHOLD = 0.02f;

    // ========== 状态 ==========
    private enum Mode { IDLE, VOICE_DIALOG }
    private volatile Mode mode = Mode.IDLE;

    // ========== AI 中断控制 ==========
    private final AtomicLong aiTurnId = new AtomicLong(0);
    private volatile long currentAiTurnId = -1;  // 当前正在执行的 AI turn，-1 表示空闲
    private volatile int silenceCount = 0;         // 连续沉默计数（每500ms+1，≥10即5秒→超时）
    private volatile ToolPlanner toolPlanner;       // 工具规划器（AI两阶段选择+记忆）

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
                                 ToolPlanner toolPlanner) {
        return args -> {
            // 合并 MCP 工具 + 本地工具（FriendMatcher, CapabilityService）
            ToolCallback[] allToolCallbacks = Stream.concat(
                    Arrays.stream(mcpTools.getToolCallbacks()),
                    Arrays.stream(MethodToolCallbackProvider.builder()
                            .toolObjects(friendMatcher, capabilityService)
                            .build()
                            .getToolCallbacks())
            ).toArray(ToolCallback[]::new);
            this.toolPlanner = toolPlanner;

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
            System.out.println("📦 加载到的工具数量: " + allToolCallbacks.length);

            // 启动后台录音线程（始终运行）
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

    // ========== 后台录音线程（Silero VAD 神经网络检测，区分人声/噪音） ==========
    private void startBackgroundRecording(LocalASR localASR, VadService vadService, TtsService ttsService) {
        TargetDataLine line = null;
        try {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format, 16000);
            line.start();

            byte[] buffer = new byte[3200];  // 100ms @16kHz
            boolean wasSpeaking = false;

            // Energy fallback state
            ByteArrayOutputStream energyBuf = new ByteArrayOutputStream();
            long silenceStart = 0, speechStart = 0;
            boolean isSpeaking = false;
            final boolean useVad = vadService.isAvailable();

            // 尾音缓冲：保留末尾 200ms 音频，防止 VAD 切尾
            float[] trailBuf = new float[SAMPLE_RATE / 5];  // 3200 samples = 200ms
            int trailPos = 0;

            // TTS 回声消散：TTS 播完后 300ms 内跳过录音，防止喇叭回声被 VAD 当成说话
            long ttsEchoUntil = 0;

            if (!useVad) System.out.println("ℹ️ VAD 未就绪，使用能量检测回退");

            while (!Thread.currentThread().isInterrupted()) {
                int available = line.available();
                if (available <= 0) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                    continue;
                }

                int toRead = Math.min(available, buffer.length);
                int count = line.read(buffer, 0, toRead);
                if (count <= 0) continue;

                // TTS 播放中 或 刚播完 300ms 内 → 跳过，防止回声
                if (ttsService.isPlaying()) {
                    ttsEchoUntil = System.currentTimeMillis() + 300;  // 播完后多等 300ms
                    if (useVad) vadService.clear();
                    else { isSpeaking = false; energyBuf.reset(); silenceStart = 0; }
                    wasSpeaking = false;
                    continue;
                }
                if (System.currentTimeMillis() < ttsEchoUntil) {
                    // 回声消散期：跳过音频，同时清空 trailBuf 防止旧数据污染
                    if (useVad) vadService.clear();
                    trailPos = 0;
                    Arrays.fill(trailBuf, 0);
                    wasSpeaking = false;
                    continue;
                }

                float[] samples = pcmToFloat(buffer, count);

                // 持续写入尾音缓冲区
                for (float s : samples) {
                    trailBuf[trailPos] = s;
                    trailPos = (trailPos + 1) % trailBuf.length;
                }

                if (useVad) {
                    // ===== 神经网络 VAD =====
                    vadService.acceptWaveform(samples);
                    while (vadService.hasSegment()) {
                        SpeechSegment seg = vadService.nextSegment();
                        float[] speech = seg.getSamples();
                        if (speech.length > SAMPLE_RATE * 0.25f) {
                            // 能量校验：过滤纯噪音段
                            if (calcEnergyFallback(speech) < ENERGY_THRESHOLD) continue;
                            // 拼上尾音缓冲（环形顺序：trailPos 是最旧的 → 最新的是 trailPos-1）
                            float[] withTrail = new float[speech.length + trailBuf.length];
                            System.arraycopy(speech, 0, withTrail, 0, speech.length);
                            for (int j = 0; j < trailBuf.length; j++) {
                                withTrail[speech.length + j] = trailBuf[(trailPos + j) % trailBuf.length];
                            }
                            String text;
                            if (mode == Mode.VOICE_DIALOG && localASR.isOfflineAvailable()) {
                                text = localASR.recognizeOffline(withTrail, SAMPLE_RATE);
                            } else {
                                text = localASR.recognizeOnline(withTrail, SAMPLE_RATE);
                            }
                            if (text != null && !text.isEmpty() && !text.startsWith("(")) {
                                System.out.println("\n🎤 听到: " + text);
                                speechQueue.offer(text);
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
                    // ===== 能量检测回退 =====
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
                                    String text;
                                    if (mode == Mode.VOICE_DIALOG && localASR.isOfflineAvailable()) {
                                        text = localASR.recognizeOffline(pcmToFloat(energyBuf.toByteArray()), SAMPLE_RATE);
                                    } else {
                                        text = localASR.recognizeOnline(pcmToFloat(energyBuf.toByteArray()), SAMPLE_RATE);
                                    }
                                    if (text != null && !text.isEmpty() && !text.startsWith("(")) {
                                        System.out.println("\n🎤 听到: " + text);
                                        speechQueue.offer(text);
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
            }
        } catch (Exception e) {
            System.err.println("❌ 录音线程异常: " + e.getMessage());
        } finally {
            if (line != null) {
                try { line.stop(); line.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ========== 语音事件处理线程 ==========
    private void handleSpeechEvents(ChatClient chatClient, ToolCallback[] tools, TtsService ttsService, SkillConfig skillConfig, VadService vadService) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String text = speechQueue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);

                // ===== IDLE 模式：只监听唤醒词 =====
                if (mode == Mode.IDLE) {
                    if (text == null) continue;
                    if (text.contains(WAKE_WORD)) {
                        mode = Mode.VOICE_DIALOG;
                        silenceCount = 0;  // 唤醒后开始计数沉默
                        System.out.println("\n✨ 已唤醒！进入语音对话模式");
                        ttsService.speakAsync("我在");
                    } else {
                        System.out.println("\r💤 听到 '" + text + "'，但未唤醒（需说 '" + WAKE_WORD + "'）");
                    }
                    continue;
                }

                // ===== VOICE_DIALOG 模式 =====

                // 检查超时：连续 5 秒沉默且 AI 未在执行 → 退出（VAD检测到说话中则归零）
                if (text == null) {
                    if (ttsService.isPlaying()) {
                        silenceCount = 0;  // TTS 播报中，不算沉默，等播完再计时
                    } else if (vadService.isAvailable() && vadService.isSpeech()) {
                        silenceCount = 0;  // VAD说用户正在说话，不计数
                    } else {
                        silenceCount++;
                        if (currentAiTurnId == -1 && silenceCount >= 10) {  // 10 × 500ms = 5s
                            mode = Mode.IDLE;
                            silenceCount = 0;
                            System.out.println("\n⏰ 5 秒无语音，回到待命");
                            ttsService.speakAsync("已回到待命模式");
                        }
                    }
                    continue;
                }

                // 有语音输入，归零沉默计数
                silenceCount = 0;

                System.out.println("\n📝 你: " + text);
                playBeep(600, 50);  // 确认音：识别完成
                System.out.print("🎤 正在听... ");

                // 退出词
                if (text.contains("退出") || text.contains("再见") || text.contains("结束对话")) {
                    mode = Mode.IDLE;
                    interruptCurrentAi();
                    ttsService.stop();
                    System.out.println("👋 对话结束，回到待命");
                    ttsService.speakAsync("再见");
                    continue;
                }

                // 中断词：只中断 AI + TTS，不退出语音模式
                if (text.contains("停") || text.contains("打断") || text.contains("取消")) {
                    interruptCurrentAi();
                    ttsService.stop();
                    System.out.println("🛑 已中断，请继续说");
                    silenceCount = 0;
                    continue;
                }

                // AI 执行中来了新语音 → 丢弃（AI 正忙），等 AI 完成后再听
                if (currentAiTurnId != -1) {
                    System.out.println("⏳ AI 执行中，忽略语音输入");
                    continue;
                }

                // 处理 AI 调用（异步，不阻塞监听）
                processAITurnAsync(chatClient, tools, text, ttsService, skillConfig);

            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ========== AI 处理（异步，不阻塞语音监听） ==========
    private void processAITurnAsync(ChatClient chatClient, ToolCallback[] tools, String userInput, TtsService ttsService, SkillConfig skillConfig) {
        Thread worker = new Thread(() -> processAITurn(chatClient, tools, userInput, ttsService, skillConfig), "ai-worker");
        worker.setDaemon(true);
        worker.start();
    }

    /** 缓存方案执行失败时抛出，触发重新规划 */
    private static class CacheFailureException extends RuntimeException {
        CacheFailureException(String msg, Throwable cause) { super(msg, cause); }
    }

    /** 执行 Phase 2：用选中工具调用 AI，缓存命中失败时抛 CacheFailureException */
    private String executeWithTools(ChatClient chatClient, String input,
                                    ToolCallback[] allTools, ToolPlanner.PlanResult plan) {
        ToolCallback[] loggedTools = Arrays.stream(allTools)
                .filter(tc -> plan.selectedToolNames().contains(tc.getToolDefinition().name()))
                .map(LoggingToolCallback::new)
                .toArray(ToolCallback[]::new);
        System.out.println("📦 选用工具: " + loggedTools.length + "/" + allTools.length
                + (plan.fromCache() ? " (缓存命中)" : ""));

        try {
            return chatClient.prompt()
                    .user(input)
                    .toolCallbacks(loggedTools)
                    .call()
                    .content();
        } catch (Exception e) {
            if (plan.fromCache()) {
                // 缓存方案失败 → 抛特殊异常触发重新规划
                throw new CacheFailureException("🔄 缓存方案执行失败，正在重新规划...", e);
            }
            throw e;  // 非缓存方案失败 → 直接抛出
        }
    }

    // ========== AI 处理（支持中断） ==========
    private void processAITurn(ChatClient chatClient, ToolCallback[] tools, String userInput, TtsService ttsService, SkillConfig skillConfig) {
        long myTurnId = aiTurnId.incrementAndGet();
        currentAiTurnId = myTurnId;
        silenceCount = 0;  // 对话开始，停止沉默计数

        System.out.println("🤖 思考中...");

        // 提前声明，让 catch 块也能访问
        String effectiveInput = userInput;
        try {
            // 根据用户输入匹配技能规则，注入到 prompt 中
            String skillInstructions = skillConfig.getInstructions(userInput);
            if (!skillInstructions.isEmpty()) {
                effectiveInput = skillInstructions + "\n用户请求：" + userInput;
                System.out.println("📋 已注入技能: " + skillConfig.getMatchedSkillNames(userInput));
            }

            // ===== Phase 1: AI 规划所需工具（轻量调用，只发分类编号）=====
            ToolPlanner.PlanResult plan = toolPlanner.plan(userInput, tools);

            // 提示缺失工具
            plan.missingDescriptions().forEach(desc ->
                    System.out.println("⚠️ 缺少工具: " + desc + "（可补写本地 @Tool）"));

            // ===== Phase 2: 只发选中工具的完整 schema =====
            String response = executeWithTools(chatClient, effectiveInput, tools, plan);

            if (currentAiTurnId == myTurnId) {
                System.out.println("🤖 " + response);
                // 命中缓存+成功 → 重置失败计数；首次成功 → 写入缓存
                if (plan.fromCache()) {
                    toolPlanner.onCacheHitSuccess(userInput);
                } else {
                    toolPlanner.cachePlan(userInput, plan);
                }
                ttsService.speakAsync(response);
            }
        } catch (CacheFailureException e) {
            // 缓存命中但失败 → 计数+1，重新规划本次执行
            if (currentAiTurnId == myTurnId) {
                boolean shouldReplace = toolPlanner.onCacheHitFailure(userInput);
                String failureReason = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                try {
                    ToolPlanner.PlanResult newPlan = toolPlanner.replan(userInput, tools, failureReason);
                    newPlan.missingDescriptions().forEach(desc ->
                            System.out.println("⚠️ 缺少工具: " + desc + "（可补写本地 @Tool）"));
                    System.out.println("📦 重新选用工具: " + newPlan.selectedToolNames().size() + "/" + tools.length);
                    String response = executeWithTools(chatClient, effectiveInput, tools, newPlan);
                    if (currentAiTurnId == myTurnId) {
                        System.out.println("🤖 " + response);
                        // 只有连续失败达阈值（旧缓存已清）才写入新方案
                        if (shouldReplace) {
                            toolPlanner.cachePlan(userInput, newPlan);
                        }
                        ttsService.speakAsync(response);
                    }
                } catch (Exception retryEx) {
                    if (currentAiTurnId == myTurnId) {
                        System.out.println("❌ 重试失败: " + retryEx.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            if (currentAiTurnId == myTurnId) {
                System.out.println("❌ " + e.getMessage());
            }
        } finally {
            if (currentAiTurnId == myTurnId) {
                currentAiTurnId = -1;
                silenceCount = 0;
            }
        }
    }

    // ========== 中断当前 AI 调用 ==========
    private boolean interruptCurrentAi() {
        if (currentAiTurnId != -1) {
            // 让当前 turn 的结果被丢弃
            currentAiTurnId = -1;
            return true;
        }
        return false;
    }

    // ========== 工具方法 ==========

    /** 播放简短提示音，用于语音反馈 */
    private void playBeep(int freqHz, int durationMs) {
        try {
            float sampleRate = 8000;
            int samples = (int) (sampleRate * durationMs / 1000.0f);
            byte[] buf = new byte[samples];
            for (int i = 0; i < samples; i++) {
                double angle = 2.0 * Math.PI * freqHz * i / sampleRate;
                buf[i] = (byte) (Math.sin(angle) * 80); // 音量适中
            }
            AudioFormat fmt = new AudioFormat(sampleRate, 8, 1, true, false);
            try (Clip clip = AudioSystem.getClip()) {
                clip.open(fmt, buf, 0, buf.length);
                clip.start();
                Thread.sleep(durationMs + 50);
                clip.stop();
            }
        } catch (Exception ignored) {
        }
    }

    /** 能量计算（VAD 回退用） */
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
