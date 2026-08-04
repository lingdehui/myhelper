package com.example.desktopbrain.service;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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

    private OfflineTts tts;
    private volatile float ttsSampleRate;
    private final LinkedBlockingQueue<GeneratedAudio> playQueue = new LinkedBlockingQueue<>(5);
    private final AtomicBoolean stopFlag = new AtomicBoolean(false);
    private Thread playbackThread;
    private volatile boolean playing = false;

    public boolean isPlaying() { return playing; }

    @PostConstruct
    public void init() {
        try {
            // 使用项目目录下的持久化路径，避免每次解压到 C 盘临时目录
            Path modelDir = Path.of("models", "tts-zh").toAbsolutePath();
            Files.createDirectories(modelDir);

            // 首次启动才解压，后续秒启
            if (!Files.exists(modelDir.resolve("model.onnx"))) {
                System.out.println("📦 首次启动，解压 TTS 模型到 " + modelDir);
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
                System.out.println("🔊 预热完成 (" + warmup.getSamples().length + " samples)");
            }

            playbackThread = new Thread(this::playbackLoop, "tts-playback");
            playbackThread.setDaemon(true);
            playbackThread.start();

            System.out.println("🔊 TTS 引擎就绪 (" + (int) ttsSampleRate + " Hz)");
        } catch (Exception e) {
            System.out.println("⚠️ TTS 初始化失败: " + e.getMessage() + "，将跳过语音输出");
            e.printStackTrace();
        }
    }

    /**
     * 异步朗读文本（立即返回，后台播放）
     */
    public void speakAsync(String text) {
        if (tts == null) {
            System.err.println("🔇 TTS 未初始化");
            return;
        }
        if (text == null || text.isBlank()) return;

        String clean = text.replace("\n", " ").replace("\r", " ").trim();
        if (clean.isEmpty()) return;
        if (clean.length() > 500) clean = clean.substring(0, 500);

        try {
            GeneratedAudio audio = tts.generate(clean);
            if (audio == null) {
                System.err.println("🔇 TTS generate 返回 null: " + clean);
                return;
            }
            if (audio.getSamples().length == 0) {
                System.err.println("🔇 TTS generate 返回空音频: " + clean);
                return;
            }
            // 打印前几个采样值确认音频非空
            float[] samples = audio.getSamples();
            float maxVal = 0;
            for (float s : samples) { if (Math.abs(s) > maxVal) maxVal = Math.abs(s); }
            System.out.println("🔊 TTS: " + clean + " (" + samples.length + " samples, max=" + String.format("%.3f", maxVal) + ", rate=" + (int)ttsSampleRate + "Hz)");
            
            playQueue.poll();
            playQueue.offer(audio);
        } catch (Exception e) {
            System.err.println("🔇 TTS generate 异常: " + e.getMessage());
            e.printStackTrace();
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
                System.out.println("🔊 开始播放 (" + pcm.length + " bytes, " + sampleRate + "Hz)");

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
                    System.out.println("🔊 播放完成");
                }
                playing = false;
            }
        } catch (Exception e) {
            playing = false;
            System.err.println("🔇 TTS 播放失败: " + e.getMessage());
            e.printStackTrace();
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
}
