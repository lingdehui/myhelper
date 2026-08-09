package com.example.desktopbrain.service;

import com.k2fsa.sherpa.onnx.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class LocalASR {

    private static final Logger log = LoggerFactory.getLogger(LocalASR.class);

    private static final int SAMPLE_RATE = 16000;

    // 流式识别（唤醒词用，低延迟，zipformer 模型）
    private OnlineRecognizer onlineRecognizer;

    // 离线识别（对话用，高精度，Paraformer 模型）
    private OfflineRecognizer offlineRecognizer;

    @PostConstruct
    public void init() throws Exception {
        initOnline();
        initOffline();
    }

    // ========== OnlineRecognizer（唤醒词检测）==========
    private void initOnline() throws Exception {
        Path modelDir = Files.createTempDirectory("sherpa-model");
        modelDir.toFile().deleteOnExit();

        String[] files = {"encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.int8.onnx",
                "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt"};
        for (String f : files) {
            ClassPathResource res = new ClassPathResource("models/asr-bilingual/" + f);
            try (InputStream in = res.getInputStream()) {
                Files.copy(in, modelDir.resolve(f));
            }
        }

        OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(modelDir.resolve("encoder-epoch-99-avg-1.int8.onnx").toString())
                .setDecoder(modelDir.resolve("decoder-epoch-99-avg-1.int8.onnx").toString())
                .setJoiner(modelDir.resolve("joiner-epoch-99-avg-1.int8.onnx").toString())
                .build();

        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(modelDir.resolve("tokens.txt").toString())
                .setNumThreads(2)
                .setProvider("cpu")
                .setDebug(false)
                .build();

        OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(modelConfig)
                .setDecodingMethod("modified_beam_search")
                .build();
        this.onlineRecognizer = new OnlineRecognizer(config);

        // 预热
        recognizeOnline(new float[480], SAMPLE_RATE);
        log.info("✅ 流式 ASR（唤醒词检测）就绪");
    }

    // ========== OfflineRecognizer（对话识别，Paraformer）==========
    private void initOffline() throws Exception {
        Path modelDir = Path.of("models", "asr-offline", "sherpa-onnx-paraformer-zh-small-2024-03-09")
                .toAbsolutePath();
        if (!Files.exists(modelDir)) {
            log.warn("⚠️ Paraformer 模型未找到: {}，离线 ASR 不可用", modelDir);
            return;
        }

        OfflineParaformerModelConfig paraformer = OfflineParaformerModelConfig.builder()
                .setModel(modelDir.resolve("model.int8.onnx").toString())
                .build();

        OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                .setParaformer(paraformer)
                .setTokens(modelDir.resolve("tokens.txt").toString())
                .setNumThreads(2)
                .setProvider("cpu")
                .setDebug(false)
                .build();

        OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(modelConfig)
                .build();
        this.offlineRecognizer = new OfflineRecognizer(config);

        // 预热：喂 1 秒静音，避免 30ms 短音频触发 Paraformer Conv 层 Invalid input shape: {0}
        try {
            OfflineStream stream = offlineRecognizer.createStream();
            stream.acceptWaveform(new float[SAMPLE_RATE], SAMPLE_RATE);
            offlineRecognizer.decode(stream);
            stream.release();
        } catch (Exception e) {
            log.warn("⚠️ Paraformer 预热异常（实际语音可能正常）: {}", e.getMessage());
        }
        log.info("✅ 离线 ASR（Paraformer 对话识别）就绪");
    }

    // ========== 在线识别（唤醒词检测）==========
    public String recognizeOnline(float[] samples, float sampleRate) {
        if (onlineRecognizer == null) return "模型未加载";
        if (samples == null || samples.length == 0) return "音频数据为空";

        float maxVal = 0;
        for (float s : samples) {
            float abs = Math.abs(s);
            if (abs > maxVal) maxVal = abs;
        }
        if (maxVal < 0.001f) {
            return "(未检测到有效语音，请大声说话)";
        }

        return doRecognizeOnline(samples, (int) sampleRate);
    }

    private String doRecognizeOnline(float[] samples, int sampleRate) {
        OnlineStream stream = onlineRecognizer.createStream();
        try {
            int chunkSize = sampleRate * 30 / 1000;
            int paddedLen = ((samples.length + chunkSize - 1) / chunkSize) * chunkSize;
            float[] padded = new float[paddedLen];
            System.arraycopy(samples, 0, padded, 0, samples.length);

            for (int start = 0; start < padded.length; start += chunkSize) {
                float[] chunk = new float[chunkSize];
                System.arraycopy(padded, start, chunk, 0, chunkSize);
                stream.acceptWaveform(chunk, sampleRate);

                while (onlineRecognizer.isReady(stream)) {
                    onlineRecognizer.decode(stream);
                }
            }
            stream.inputFinished();

            while (onlineRecognizer.isReady(stream)) {
                onlineRecognizer.decode(stream);
            }

            String text = onlineRecognizer.getResult(stream).getText();
            return text.isEmpty() ? "(未识别到语音)" : text;
        } finally {
            stream.release();
        }
    }

    // ========== 离线识别（对话，高精度）==========
    public String recognizeOffline(float[] samples, float sampleRate) {
        if (offlineRecognizer == null) return "离线 ASR 未就绪";
        if (samples == null || samples.length == 0) return "音频数据为空";

        float maxVal = 0;
        for (float s : samples) {
            float abs = Math.abs(s);
            if (abs > maxVal) maxVal = abs;
        }
        if (maxVal < 0.001f) {
            return "(未检测到有效语音，请大声说话)";
        }

        return doRecognizeOffline(samples, (int) sampleRate);
    }

    private String doRecognizeOffline(float[] samples, int sampleRate) {
        OfflineStream stream = offlineRecognizer.createStream();
        try {
            stream.acceptWaveform(samples, sampleRate);
            offlineRecognizer.decode(stream);
            String text = offlineRecognizer.getResult(stream).getText();
            return text.isEmpty() ? "(未识别到语音)" : text;
        } finally {
            stream.release();
        }
    }

    /** 判断离线 ASR 是否可用 */
    public boolean isOfflineAvailable() {
        return offlineRecognizer != null;
    }

    @PreDestroy
    public void shutdown() {
        if (onlineRecognizer != null) onlineRecognizer.release();
        if (offlineRecognizer != null) offlineRecognizer.release();
    }

    /** 创建流式识别 Stream（持续使用，用完 release() 释放） */
    public OnlineStream createStream() {
        return onlineRecognizer.createStream();
    }

    /**
     * 流式喂入音频数据，返回当前部分识别结果。
     * @param stream     已创建的流
     * @param samples    音频样本
     * @param sampleRate 采样率
     * @param isEnd      是否已结束（true 时会调用 inputFinished）
     * @return 当前部分识别文本
     */
    public String feedStream(OnlineStream stream, float[] samples, int sampleRate, boolean isEnd) {
        int chunkSize = sampleRate * 30 / 1000; // 30ms chunks
        int paddedLen = ((samples.length + chunkSize - 1) / chunkSize) * chunkSize;
        float[] padded = new float[paddedLen];
        System.arraycopy(samples, 0, padded, 0, samples.length);

        for (int start = 0; start < padded.length; start += chunkSize) {
            float[] chunk = new float[chunkSize];
            System.arraycopy(padded, start, chunk, 0, chunkSize);
            stream.acceptWaveform(chunk, sampleRate);
            while (onlineRecognizer.isReady(stream)) {
                onlineRecognizer.decode(stream);
            }
        }

        if (isEnd) {
            stream.inputFinished();
            while (onlineRecognizer.isReady(stream)) {
                onlineRecognizer.decode(stream);
            }
        }

        return onlineRecognizer.getResult(stream).getText();
    }
}
