package com.example.desktopbrain.service;

import com.example.desktopbrain.config.DesktopBrainProperties;
import com.example.desktopbrain.config.ModelRouter;
import com.example.desktopbrain.dialog.DialogStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import javax.sound.sampled.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

@Service
public class SpeechEventLoop {

    private static final Logger log = LoggerFactory.getLogger(SpeechEventLoop.class);

    public enum Mode { IDLE, VOICE_DIALOG }

    // ========== 注入的配置 / 共享状态（无 this. 前缀） ==========
    private final DesktopBrainProperties props;
    private final LinkedBlockingQueue<String> speechQueue;
    private volatile ToolCallback[] allTools;
    private final ExecutorService aiExecutor;

    // ========== 注入的服务（带 this. 前缀） ==========
    private final ModelRouter modelRouter;
    private final TurnProcessor turnProcessor;
    private final DialogStateMachine dialogStateMachine;
    private final TtsService ttsService;
    private final VoiceprintService voiceprintService;
    private final VadService vadService;
    private final SkillConfig skillConfig;

    // ========== 实例状态 ==========
    private volatile Mode mode = Mode.IDLE;
    private volatile String currentSpeaker = null;
    private volatile String lastUserInput = "";
    private volatile String lastAiResponse = "";
    private volatile String pendingSupplement = null;
    private volatile boolean awaitingSupplementConfirm = false;
    private volatile boolean enrollmentHinted = false;
    private volatile boolean followUpHintGiven = false;
    private volatile boolean lastWasFuzzyListening = false;

    public SpeechEventLoop(DesktopBrainProperties props,
                           LinkedBlockingQueue<String> speechQueue,
                           @Qualifier("aiExecutor") ExecutorService aiExecutor,
                           ModelRouter modelRouter,
                           TurnProcessor turnProcessor,
                           DialogStateMachine dialogStateMachine,
                           TtsService ttsService,
                           VoiceprintService voiceprintService,
                           VadService vadService,
                           SkillConfig skillConfig) {
        this.props = props;
        this.speechQueue = speechQueue;
        this.aiExecutor = aiExecutor;
        this.modelRouter = modelRouter;
        this.turnProcessor = turnProcessor;
        this.dialogStateMachine = dialogStateMachine;
        this.ttsService = ttsService;
        this.voiceprintService = voiceprintService;
        this.vadService = vadService;
        this.skillConfig = skillConfig;
    }

    // ========== 公开 API ==========

    /** 启动事件循环（daemon 线程） */
    public void start() {
        Thread handlerThread = new Thread(this::runEventLoop, "speech-handler");
        handlerThread.setDaemon(true);
        handlerThread.start();
    }

    /** 获取当前识别到的说话人 */
    public String getCurrentSpeaker() {
        return currentSpeaker;
    }

    /** 设置当前说话人（由录音线程调用） */
    public void setCurrentSpeaker(String speaker) {
        this.currentSpeaker = speaker;
    }

    /** 设置最近一次 AI 回复（语义匹配用） */
    public void setLastAiResponse(String response) {
        this.lastAiResponse = response;
    }

    /** 获取最近一次用户输入（语义匹配用） */
    public String getLastUserInput() {
        return lastUserInput;
    }

    /** 获取当前模式 */
    public Mode getMode() {
        return mode;
    }

    /** 设置当前模式 */
    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** 设置工具列表（由 Application 启动后注入） */
    public void setTools(ToolCallback[] tools) {
        this.allTools = tools;
    }

    // ========== 事件循环 ==========

    private void runEventLoop() {
        boolean voiceprintAvailable = voiceprintService.isAvailable();
        boolean hasVoiceprints = voiceprintAvailable && voiceprintService.listSpeakers().length > 0;

        if (!voiceprintAvailable) {
            log.info("ℹ️ 声纹识别未启用，任何人声音均可唤醒");
        } else if (!hasVoiceprints) {
            log.info("ℹ️ 声纹识别已就绪，但尚未注册声纹");
            log.info("   👉 不注册也可以正常使用，如需专属唤醒可随时说 '注册声纹'");
        } else {
            log.info("🎙️ 声纹保护已开启，已注册用户: {}", String.join(", ", voiceprintService.listSpeakers()));
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
                            log.info("💤 模糊监听中...");
                            lastWasFuzzyListening = true;
                        }
                        continue;
                    }
                    lastWasFuzzyListening = false;  // 检测到语音，重置状态
                    if (fuzzyMatchWakeWord(text, props.voice().wakeWord())) {
                        dialogStateMachine.transitionTo(DialogStateMachine.State.LISTENING);
                        mode = Mode.VOICE_DIALOG;
                        turnProcessor.resetSilenceCount();
                        log.info("✨ 已唤醒！进入语音对话模式");
                        ttsService.speakAsync("我在");
                        if (voiceprintAvailable && !hasVoiceprints && !enrollmentHinted) {
                            enrollmentHinted = true;
                            log.info("💡 （可选）说 '注册声纹' 可设置专属声纹，不注册也能正常使用");
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
                                    log.info("\n💬 跟随对话中...（无需重新唤醒）");
                                }
                            }
                            if (sc >= timeoutThreshold) {
                                dialogStateMachine.transitionTo(DialogStateMachine.State.IDLE);
                                mode = Mode.IDLE;
                                turnProcessor.resetSilenceCount();
                                followUpHintGiven = false;
                                log.info("\n⏰ {} 秒无语音，回到待命", props.dialog().followUpWindowMs() / 1000);
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
                        log.info("\n🔇 声纹不匹配，忽略打断");
                        continue;
                    }

                    log.info("\n⚡ 自然打断！");
                    dialogStateMachine.transitionTo(DialogStateMachine.State.INTERRUPTED);
                    turnProcessor.interruptCurrentTurn();
                    ttsService.stop();
                    playBeep(400, 80);
                }

                // ---- 声纹标注 ----
                if (voiceprintAvailable && hasVoiceprints) {
                    log.info("\n🎤 听到 [{}]: {}", (currentSpeaker != null ? currentSpeaker : "未识别"), text);
                } else {
                    log.info("\n📝 你: {}", text);
                }

                playBeep(600, 50);

                // ---- 指令处理 ----
                if (containsKeyword(text, props.dialog().exitKeywords())) {
                    dialogStateMachine.transitionTo(DialogStateMachine.State.IDLE);
                    mode = Mode.IDLE;
                    turnProcessor.interruptCurrentTurn();
                    ttsService.stop();
                    log.info("👋 对话结束，回到待命");
                    ttsService.speakAsync("再见");
                    continue;
                }

                if (containsKeyword(text, props.dialog().interruptKeywords())) {
                    turnProcessor.interruptCurrentTurn();
                    ttsService.stop();
                    log.info("🛑 已中断，请继续说");
                    turnProcessor.resetSilenceCount();
                    continue;
                }

                // ---- 注册声纹指令 ----
                if (text.contains("注册声纹")) {
                    handleEnrollment();
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
                        log.info("🔄 确认补充，中断 AI (turn={})，带补充信息重新执行...", interruptedId);
                        log.info("  原请求: {}", lastUserInput);
                        log.info("  补充: {}", supplement);

                        dialogStateMachine.transitionTo(DialogStateMachine.State.PROCESSING);
                        aiExecutor.submit(() -> turnProcessor.process(modelRouter, allTools, combinedInput, ttsService));
                    } else if (isRejectKeyword(text)) {
                        awaitingSupplementConfirm = false;
                        pendingSupplement = null;
                        log.info("❌ 用户拒绝补充，AI 继续执行");
                        ttsService.speakAsync("好的，继续执行");
                    } else {
                        awaitingSupplementConfirm = false;
                        pendingSupplement = null;
                        log.info("↩️ 用户说了其他内容，作为新请求处理");
                        lastUserInput = text;
                        dialogStateMachine.transitionTo(DialogStateMachine.State.PROCESSING);
                        aiExecutor.submit(() -> turnProcessor.process(modelRouter, allTools, text, ttsService));
                    }
                    continue;
                }

                // ---- AI 执行中：补充信息判断 ----
                if (turnProcessor.isActive() && !dialogStateMachine.canInterrupt()) {
                    handleSupplement(text);
                    continue;
                }

                // ---- 正常处理 ----
                lastUserInput = text;
                dialogStateMachine.transitionTo(DialogStateMachine.State.PROCESSING);
                aiExecutor.submit(() -> {
                    try {
                        turnProcessor.process(modelRouter, allTools, text, ttsService);
                        dialogStateMachine.transitionTo(DialogStateMachine.State.SPEAKING);
                        while (ttsService.isPlaying()) {
                            Thread.sleep(200);
                        }
                        dialogStateMachine.transitionTo(DialogStateMachine.State.LISTENING);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        dialogStateMachine.transitionTo(DialogStateMachine.State.LISTENING);
                    }
                });

            } catch (InterruptedException e) {
                break;
            }
        }
    }

    // ========== 补充信息判断与处理 ==========
    private void handleSupplement(String newInput) {
        log.info("\n🔍 AI 执行中，判断是否为补充信息...");

        // Step 1: 声纹验证（已在录音线程中完成，此处用 currentSpeaker 判断）
        if (voiceprintService.isAvailable() && voiceprintService.listSpeakers().length > 0) {
            if (currentSpeaker == null) {
                log.info("  → 声纹不匹配，忽略补充请求");
                return;
            }
            log.info("  ✅ 声纹匹配: {}", currentSpeaker);
        }

        // Step 2: 语义关联判断
        boolean isRelated = isSemanticallyRelated(newInput, lastUserInput, lastAiResponse);
        if (!isRelated) {
            log.info("  → 语义不相关，暂不处理（AI 继续执行）");
            return;
        }

        // Step 3: 询问用户确认
        log.info("  ✅ 语义相关（补充信息）: {}", newInput);
        log.info("  ❓ 是否作为补充信息加入？说 '对' 确认，说 '不对' 忽略");

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

    /**
     * 唤醒词模糊匹配：在 ASR 识别结果中滑动窗口，用编辑距离判断是否命中唤醒词。
     * 解决 ASR 不准（字符重复、相似音误识别）导致的唤醒失败。
     *
     * @param text      ASR 识别文本
     * @param wakeWord  配置的唤醒词
     * @return 是否模糊匹配成功
     */
    private boolean fuzzyMatchWakeWord(String text, String wakeWord) {
        if (text == null || wakeWord == null) return false;
        // 先做精确匹配，性能优化
        if (text.contains(wakeWord)) return true;

        int wLen = wakeWord.length();
        int tLen = text.length();
        if (tLen < wLen) return false;

        // 去除连续重复字，处理 ASR 吐出"大大大大"这类噪音
        StringBuilder dedup = new StringBuilder();
        char last = 0;
        for (int i = 0; i < tLen; i++) {
            char c = text.charAt(i);
            if (c != last) { dedup.append(c); last = c; }
        }
        String dedupText = dedup.toString();
        int dLen = dedupText.length();

        // 最多允许 2 个编辑距离
        int maxDist = Math.max(2, wLen / 2);

        // 在去重后的文本上滑动窗口
        for (int start = 0; start <= dLen - wLen + 1; start++) {
            String window = dedupText.substring(start, Math.min(start + wLen + 1, dLen));
            if (editDistance(window, wakeWord) <= maxDist) return true;
        }

        // 也尝试+1长度的窗口（ASR 可能多识别一个字）
        for (int start = 0; start <= dLen - wLen; start++) {
            if (start + wLen + 1 > dLen) break;
            String window = dedupText.substring(start, start + wLen + 1);
            if (editDistance(window, wakeWord) <= maxDist) return true;
        }

        return false;
    }

    /** 计算两个字符串的 Levenshtein 编辑距离 */
    private int editDistance(String a, String b) {
        int n = a.length(), m = b.length();
        int[] prev = new int[m + 1];
        int[] curr = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;

        for (int i = 1; i <= n; i++) {
            curr[0] = i;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(prev[j] + 1, curr[j - 1] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[m];
    }

    // ========== 用户注册引导 ==========
    private void handleEnrollment() {
        if (!voiceprintService.isAvailable()) {
            log.info("❌ 声纹识别未就绪（无模型文件）");
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

        log.info("\n👤 开始注册声纹，共 3 段语音");
        ttsService.speakAsync("开始注册声纹，第一句请跟我读");

        try (Scanner scanner = new Scanner(System.in)) {
            for (int i = 0; i < 3; i++) {
                log.info("  📢 请说第 {}/3 段: {}", i + 1, prompts[i]);
                ttsService.speakAsync(prompts[i]);
                log.info("  说完后按回车继续...");
                scanner.nextLine();
                log.info("  ✅ 已收集第 {}/3 段", i + 1);
                // 实际注册中应该用真实音频，这里用占位符
                // voiceprintService.addEnrollmentSegment(name, audioSamples);
            }
        }

        boolean success = voiceprintService.finishEnrollment(name, props.voice().sampleRate());
        if (success) {
            log.info("✅ 声纹注册成功: {}", name);
            ttsService.speakAsync("声纹注册成功");
        } else {
            log.info("❌ 声纹注册失败");
            ttsService.speakAsync("声纹注册失败");
        }
    }

    // ========== 提示音 ==========
    private void playBeep(int freqHz, int durationMs) {
        try {
            float sampleRate = 8000;
            int samples = (int) (sampleRate * durationMs / 1000.0f);
            byte[] buf = new byte[samples];
            for (int i = 0; i < samples; i++) {
                double angle = 2.0 * Math.PI * freqHz * i / sampleRate;
                buf[i] = (byte) (Math.sin(angle) * props.voice().beep().amplitude());
            }
            AudioFormat fmt = new AudioFormat(sampleRate, 8, 1, true, false);
            try (Clip clip = AudioSystem.getClip()) {
                clip.open(fmt, buf, 0, buf.length);
                clip.start();
                Thread.sleep(durationMs + 50);
                clip.stop();
            }
        } catch (Exception e) {
            log.error("⚠️ 提示音播放失败", e);
        }
    }
}
