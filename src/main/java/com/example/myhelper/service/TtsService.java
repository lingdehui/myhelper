package com.example.myhelper.service;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sound.sampled.*;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 语音合成（TTS）：基于 sherpa-onnx OfflineTts（VITS 模型）
 * 跨平台，纯 Java，不依赖外部进程
 */
@Component
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    private OfflineTts tts;
    private volatile float ttsSampleRate;
    private final LinkedBlockingQueue<GeneratedAudio> playQueue = new LinkedBlockingQueue<>(5);
    private final AtomicBoolean stopFlag = new AtomicBoolean(false);
    private Thread playbackThread;
    private volatile boolean playing = false;

    private final NativeJniGate jniGate;

    public TtsService(NativeJniGate jniGate) {
        this.jniGate = jniGate;
    }

    public boolean isPlaying() { return playing; }

    @PostConstruct
    public void init() {
        try {
            // 使用项目目录下的持久化路径，避免每次解压到 C 盘临时目录
            Path modelDir = Path.of("models", "tts-zh").toAbsolutePath();
            Files.createDirectories(modelDir);

            // 首次启动才解压，后续秒启
            if (!Files.exists(modelDir.resolve("model.onnx"))) {
                log.info("📦 首次启动，解压 TTS 模型到 {}", modelDir);
                String[] files = {"model.onnx", "tokens.txt", "lexicon.txt"};
                for (String f : files) {
                    extractResource("models/tts-zh/" + f, modelDir.resolve(f));
                }
                Path dictDir = modelDir.resolve("dict");
                extractDictDir("models/tts-zh/dict", dictDir);
            }

            Path dictDir = modelDir.resolve("dict");

            OfflineTtsVitsModelConfig vitsConfig = OfflineTtsVitsModelConfig.builder()
                    .setModel(modelDir.resolve("model.onnx").toString())
                    .setTokens(modelDir.resolve("tokens.txt").toString())
                    .setLexicon(modelDir.resolve("lexicon.txt").toString())
                    .setDictDir(dictDir.toString())
                    .build();

            OfflineTtsModelConfig modelConfig = OfflineTtsModelConfig.builder()
                    .setVits(vitsConfig)
                    .setNumThreads(2)
                    .setProvider("cpu")
                    .setDebug(false)
                    .build();

            OfflineTtsConfig config = OfflineTtsConfig.builder()
                    .setModel(modelConfig)
                    .setMaxNumSentences(1)
                    .build();

            tts = new OfflineTts(config);
            ttsSampleRate = tts.getSampleRate();

            // 预热：首次生成有 JNI/模型初始化开销
            GeneratedAudio warmup = tts.generate("测试");
            if (warmup != null && warmup.getSamples().length > 0) {
                log.info("🔊 预热完成 ({} samples)", warmup.getSamples().length);
            }

            playbackThread = new Thread(this::playbackLoop, "tts-playback");
            playbackThread.setDaemon(true);
            playbackThread.start();

            log.info("🔊 TTS 引擎就绪 ({} Hz)", (int) ttsSampleRate);
        } catch (Exception e) {
            log.warn("⚠️ TTS 初始化失败: {}，将跳过语音输出", e.getMessage());
            log.error("TTS 初始化异常", e);
        }
    }

    /**
     * 异步朗读文本（立即返回，后台播放）
     */
    public void speakAsync(String text) {
        if (tts == null) {
            log.error("🔇 TTS 未初始化");
            return;
        }
        if (text == null || text.isBlank()) return;

        String clean = stripMarkdown(text);
        clean = clean.replace("\n", " ").replace("\r", " ").trim();
        if (clean.isEmpty()) return;
        if (clean.length() > 500) clean = clean.substring(0, 500);

        jniGate.lock();
        try {
            GeneratedAudio audio = tts.generate(clean);
            if (audio == null) {
                log.error("🔇 TTS generate 返回 null: {}", clean);
                return;
            }
            if (audio.getSamples().length == 0) {
                log.error("🔇 TTS generate 返回空音频: {}", clean);
                return;
            }
            // 打印前几个采样值确认音频非空
            float[] samples = audio.getSamples();
            float maxVal = 0;
            for (float s : samples) { if (Math.abs(s) > maxVal) maxVal = Math.abs(s); }
            log.info("🔊 TTS: {} ({} samples, max={}, rate={}Hz)", clean, samples.length, String.format("%.3f", maxVal), (int)ttsSampleRate);
            
            // 队列满时淘汰最旧回复，语音交互优先播放最新上下文。
            if (!playQueue.offer(audio)) {
                playQueue.poll();
                if (!playQueue.offer(audio)) log.warn("🔇 TTS 播放队列拥塞，丢弃本次回复");
            }
        } catch (Exception e) {
            log.error("🔇 TTS generate 异常: {}", e.getMessage());
            log.error("TTS generate 异常", e);
        } finally {
            jniGate.unlock();
        }
    }

    /**
     * 停止当前播放
     */
    public void stop() {
        stopFlag.set(true);
        playQueue.clear();
    }

    private void playbackLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                GeneratedAudio audio = playQueue.take();
                stopFlag.set(false);
                playAudio(audio);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void playAudio(GeneratedAudio audio) {
        try {
            float[] samples = audio.getSamples();
            int sampleRate = (int) ttsSampleRate;

            byte[] pcm = new byte[samples.length * 2];
            for (int i = 0; i < samples.length; i++) {
                int s = (int) (samples[i] * 32767);
                if (s > 32767) s = 32767;
                if (s < -32768) s = -32768;
                pcm[i * 2] = (byte) (s & 0xff);
                pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
            }

            AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

            try (SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info)) {
                line.open(format);
                line.start();

                playing = true;
                log.info("🔊 开始播放 ({} bytes, {}Hz)", pcm.length, sampleRate);

                int bufferSize = Math.min(pcm.length, 4096);
                int offset = 0;
                while (offset < pcm.length && !stopFlag.get()) {
                    int bytesToWrite = Math.min(bufferSize, pcm.length - offset);
                    line.write(pcm, offset, bytesToWrite);
                    offset += bytesToWrite;
                }

                if (stopFlag.get()) {
                    line.stop();
                } else {
                    line.drain();
                    line.stop();
                    log.info("🔊 播放完成");
                }
                playing = false;
            }
        } catch (Exception e) {
            playing = false;
            log.error("🔇 TTS 播放失败: {}", e.getMessage());
            log.error("TTS 播放异常", e);
        }
    }

    private void extractResource(String resourcePath, Path target) throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (is != null) {
            Files.createDirectories(target.getParent());
            Files.copy(is, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void extractDictDir(String basePath, Path targetDir) throws Exception {
        String[] files = {
            "jieba.dict.utf8", "hmm_model.utf8", "user.dict.utf8", "idf.utf8", "stop_words.utf8",
            "pos_dict/char_state_tab.utf8", "pos_dict/prob_emit.utf8",
            "pos_dict/prob_start.utf8", "pos_dict/prob_trans.utf8"
        };
        for (String f : files) {
            extractResource(basePath + "/" + f, targetDir.resolve(f));
        }
    }

    @PreDestroy
    public void destroy() {
        stop();
        if (playbackThread != null) playbackThread.interrupt();
        if (tts != null) tts.release();
    }

    /**
     * 剥离 AI 回复中的 markdown 标记，避免 TTS 播报噪音。
     * 处理：标题(#)、粗体(**)、斜体(*)、代码块(```)、行内代码(`)、
     * 表格(|)、链接、水平线(---)、HTML标签等。
     */
    private static String stripMarkdown(String text) {
        if (text == null) return "";
        return text
                // 代码块
                .replaceAll("```[\\s\\S]*?```", " ")
                // 行内代码
                .replaceAll("`([^`]*)`", "$1")
                // 标题
                .replaceAll("(?m)^#{1,6}\\s*", "")
                // 粗体/斜体
                .replaceAll("\\*{1,3}([^*]+)\\*{1,3}", "$1")
                // 删除线
                .replaceAll("~~([^~]+)~~", "$1")
                // 水平线
                .replaceAll("(?m)^[-*_]{3,}\\s*$", " ")
                // 表格分隔行
                .replaceAll("(?m)^\\|[-:| ]+\\|$", " ")
                // 表格管道符
                .replaceAll("\\|", "，")
                // 链接 [text](url)
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                // 图片 ![alt](url)
                .replaceAll("!\\[([^]]*)]\\([^)]*\\)", "$1")
                // HTML 标签
                .replaceAll("<[^>]+>", " ")
                // 引用 >
                .replaceAll("(?m)^>\\s*", "")
                // 列表标记（含 · 和 •）
                .replaceAll("(?m)^[\\s]*[-*+·•]\\s+", "")
                .replaceAll("(?m)^[\\s]*\\d+[.)]\\s+", "")
                // Emoji 数字/符号 (1️⃣ 2️⃣ 等) + 全角冒号/括号/数字等 TTS 不发音的字符
                .replaceAll("[\\p{So}\\p{Sk}]", "")
                // 中文全角括号、斜杠等在 markdown 语境中无意义
                .replaceAll("[\\s]*[/＞＞]+[\\s]*", "、")
                .replaceAll("[（）]", "")
                .replaceAll("[:：#]", "，")
                // 多余空白
                .replaceAll("\\s{2,}", " ")
                .trim();
    }
}
