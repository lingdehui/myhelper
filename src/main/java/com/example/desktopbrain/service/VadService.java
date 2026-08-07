package com.example.desktopbrain.service;

import com.example.desktopbrain.config.DesktopBrainProperties;
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

    private final DesktopBrainProperties props;
    private Vad vad;
    private volatile boolean enabled = false;

    public VadService(DesktopBrainProperties props) {
        this.props = props;
    }

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
                    .setThreshold(props.vad().threshold())
                    .setMinSilenceDuration(props.vad().minSilenceDuration())
                    .setMinSpeechDuration(props.vad().minSpeechDuration())
                    .setMaxSpeechDuration(props.vad().maxSpeechDuration())
                    .setWindowSize(props.vad().windowSize())
                    .build();

            VadModelConfig config = VadModelConfig.builder()
                    .setSileroVadModelConfig(sileroConfig)
                    .setSampleRate(props.voice().sampleRate())
                    .setNumThreads(1)
                    .setProvider("cpu")
                    .build();

            this.vad = new Vad(config);
            this.enabled = true;
            System.out.println("✅ VAD 引擎就绪 (Silero v4, 阈值=" + props.vad().threshold() + ")");
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
