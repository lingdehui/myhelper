package com.example.myhelper.service;

import com.example.myhelper.config.MyHelperProperties;
import com.k2fsa.sherpa.onnx.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 语音活动检测（VAD）：使用 Silero VAD v4 神经网络模型
 * 零网络依赖，模型文件需手动放置在 models/vad/silero_vad.onnx
 */
@Component
public class VadService {

    private static final Logger log = LoggerFactory.getLogger(VadService.class);

    private final MyHelperProperties props;
    private Vad vad;
    private volatile boolean enabled = false;

    public VadService(MyHelperProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        try {
            Path modelPath = Path.of("models", "vad", "silero_vad.onnx").toAbsolutePath();
            if (!Files.exists(modelPath)) {
                log.info("ℹ️ VAD 模型未找到 ({})，使用能量检测回退", modelPath);
                log.info("   下载地址: https://github.com/snakers4/silero-vad/raw/refs/tags/v4.0/files/silero_vad.onnx");
                return;
            }
            log.info("🎯 VAD 模型: {}", modelPath);

            SileroVadModelConfig sileroConfig = SileroVadModelConfig.builder()
                    .setModel(modelPath.toString())
                    .setThreshold(props.vad() != null ? props.vad().threshold() : 0.5f)
                    .setMinSilenceDuration(props.vad() != null ? props.vad().minSilenceDuration() : 1.2f)
                    .setMinSpeechDuration(props.vad() != null ? props.vad().minSpeechDuration() : 0.25f)
                    .setMaxSpeechDuration(props.vad() != null ? props.vad().maxSpeechDuration() : 8f)
                    .setWindowSize(props.vad() != null ? props.vad().windowSize() : 512)
                    .build();

            VadModelConfig config = VadModelConfig.builder()
                    .setSileroVadModelConfig(sileroConfig)
                    .setSampleRate(props.voice().sampleRate())
                    .setNumThreads(1)
                    .setProvider("cpu")
                    .build();

            this.vad = new Vad(config);
            this.enabled = true;
            log.info("✅ VAD 引擎就绪 (Silero v4, 阈值={})",
                    props.vad() != null ? props.vad().threshold() : 0.5f);
        } catch (Exception e) {
            log.warn("⚠️ VAD 初始化失败: {}，使用能量检测回退", e.getMessage());
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
