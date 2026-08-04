package com.example.desktopbrain.service;

import com.k2fsa.sherpa.onnx.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 语音活动检测（VAD）：使用 Silero VAD v4 神经网络模型
 * 零网络依赖，模型文件需手动放置在 models/vad/silero_vad.onnx
 */
@Component
public class VadService {

    private Vad vad;
    private volatile boolean enabled = false;
    private static final int SAMPLE_RATE = 16000;

    // VAD 参数 — 偏敏感，靠后续能量校验过滤噪音
    private static final float THRESHOLD = 0.5f;
    private static final float MIN_SILENCE_DURATION = 1.2f;
    private static final float MIN_SPEECH_DURATION = 0.25f;
    private static final float MAX_SPEECH_DURATION = 8f;
    private static final int WINDOW_SIZE = 512;

    @PostConstruct
    public void init() {
        try {
            Path modelPath = Path.of("models", "vad", "silero_vad.onnx").toAbsolutePath();
            if (!Files.exists(modelPath)) {
                System.out.println("ℹ️ VAD 模型未找到 (" + modelPath + ")，使用能量检测回退");
                System.out.println("   下载地址: https://github.com/snakers4/silero-vad/raw/refs/tags/v4.0/files/silero_vad.onnx");
                return;
            }
            System.out.println("🎯 VAD 模型: " + modelPath);

            SileroVadModelConfig sileroConfig = SileroVadModelConfig.builder()
                    .setModel(modelPath.toString())
                    .setThreshold(THRESHOLD)
                    .setMinSilenceDuration(MIN_SILENCE_DURATION)
                    .setMinSpeechDuration(MIN_SPEECH_DURATION)
                    .setMaxSpeechDuration(MAX_SPEECH_DURATION)
                    .setWindowSize(WINDOW_SIZE)
                    .build();

            VadModelConfig config = VadModelConfig.builder()
                    .setSileroVadModelConfig(sileroConfig)
                    .setSampleRate(SAMPLE_RATE)
                    .setNumThreads(1)
                    .setProvider("cpu")
                    .build();

            this.vad = new Vad(config);
            this.enabled = true;
            System.out.println("✅ VAD 引擎就绪 (Silero v4, 阈值=" + THRESHOLD + ")");
        } catch (Exception e) {
            System.out.println("⚠️ VAD 初始化失败: " + e.getMessage() + "，使用能量检测回退");
            this.enabled = false;
        }
    }

    public boolean isAvailable() {
        return enabled && vad != null;
    }

    public void acceptWaveform(float[] samples) {
        if (enabled && vad != null) {
            vad.acceptWaveform(samples);
        }
    }

    public boolean hasSegment() {
        return enabled && vad != null && !vad.empty();
    }

    public SpeechSegment nextSegment() {
        SpeechSegment seg = vad.front();
        vad.pop();
        return seg;
    }

    public boolean isSpeech() {
        return enabled && vad != null && vad.isSpeechDetected();
    }

    public void clear() {
        if (enabled && vad != null) vad.clear();
    }

    @PreDestroy
    public void destroy() {
        if (vad != null) vad.release();
    }
}
