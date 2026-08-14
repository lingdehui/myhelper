package com.example.myhelper.service;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 声纹识别服务 - 基于 sherpa-onnx SpeakerEmbeddingExtractor
 * 
 * 支持:
 * 1. 注册声纹（单/多段）
 * 2. 声纹比对（验证是否是注册用户）
 * 3. 多声纹管理（添加/删除/列出）
 * 4. 启动时自动加载已注册声纹
 */
@Service
public class VoiceprintService {

    private static final Logger log = LoggerFactory.getLogger(VoiceprintService.class);

    @Value("${sherpa.speaker.model:models/speaker-embedding/model.onnx}")
    private String speakerModelPath;

    @Value("${sherpa.speaker.threshold:0.6}")
    private float similarityThreshold;

    private SpeakerEmbeddingExtractor extractor;
    private SpeakerEmbeddingManager manager;
    private boolean initialized = false;
    private final Map<String, List<float[]>> pendingEnrollments = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (!new File(speakerModelPath).exists()) {
            log.info("⚠️ 声纹模型不存在，声纹识别功能禁用: {}", speakerModelPath);
            log.info("   下载: ollama pull 或从 sherpa-onnx 模型库获取 3D-Speaker 模型");
            return;
        }

        try {
            SpeakerEmbeddingExtractorConfig config = SpeakerEmbeddingExtractorConfig.builder()
                    .setModel(speakerModelPath)
                    .setNumThreads(2)
                    .setDebug(false)
                    .build();

            extractor = new SpeakerEmbeddingExtractor(config);
            manager = new SpeakerEmbeddingManager(extractor.getDim());

            initialized = true;
            log.info("🎙️ 声纹识别已就绪（维度={}，阈值={}）", extractor.getDim(), similarityThreshold);
        } catch (Exception e) {
            log.error("⚠️ 声纹识别初始化失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void release() {
        if (extractor != null) extractor.release();
        if (manager != null) manager.release();
    }

    public boolean isAvailable() {
        return initialized;
    }

    /**
     * 从音频样本计算声纹向量
     */
    public float[] extractEmbedding(float[] audioSamples, int sampleRate) {
        if (!initialized) return null;

        OnlineStream stream = extractor.createStream();
        try {
            int chunkSize = 3200;
            for (int i = 0; i < audioSamples.length; i += chunkSize) {
                int end = Math.min(i + chunkSize, audioSamples.length);
                float[] chunk = Arrays.copyOfRange(audioSamples, i, end);
                stream.acceptWaveform(chunk, sampleRate);
            }

            if (!extractor.isReady(stream)) {
                Thread.sleep(200);
            }

            return extractor.compute(stream);
        } catch (Exception e) {
            log.error("声纹提取失败: {}", e.getMessage());
            return null;
        } finally {
            stream.release();
        }
    }

    /**
     * 注册声纹（单段音频）
     */
    public boolean registerSpeaker(String name, float[] audioSamples, int sampleRate) {
        if (!initialized) return false;

        float[] embedding = extractEmbedding(audioSamples, sampleRate);
        if (embedding == null) return false;

        return manager.add(name, embedding);
    }

    /**
     * 注册声纹（多段音频，更稳定）
     */
    public boolean registerSpeakerMultiSegment(String name, List<float[]> audioSegments, int sampleRate) {
        if (!initialized || audioSegments.isEmpty()) return false;

        float[][] embeddings = audioSegments.stream()
                .map(s -> extractEmbedding(s, sampleRate))
                .filter(Objects::nonNull)
                .toArray(float[][]::new);

        if (embeddings.length == 0) return false;
        return manager.add(name, embeddings);
    }

    /**
     * 验证是否是指定用户
     */
    public boolean verify(String name, float[] audioSamples, int sampleRate) {
        if (!initialized) return false;

        float[] embedding = extractEmbedding(audioSamples, sampleRate);
        if (embedding == null) return false;

        return manager.verify(name, embedding, similarityThreshold);
    }

    /**
     * 搜索最匹配的说话人
     */
    public String search(float[] audioSamples, int sampleRate) {
        if (!initialized) return null;

        float[] embedding = extractEmbedding(audioSamples, sampleRate);
        if (embedding == null) return null;

        return manager.search(embedding, similarityThreshold);
    }

    /**
     * 列出所有已注册说话人
     */
    public String[] listSpeakers() {
        if (!initialized) return new String[0];
        return manager.getAllSpeakerNames();
    }

    /**
     * 删除说话人
     */
    public boolean removeSpeaker(String name) {
        if (!initialized) return false;
        return manager.remove(name);
    }

    /**
     * 开始注册（收集多段音频）
     */
    public void startEnrollment(String name) {
        pendingEnrollments.put(name, new ArrayList<>());
    }

    /**
     * 添加一段注册音频
     */
    public void addEnrollmentSegment(String name, float[] audioSamples) {
        List<float[]> segments = pendingEnrollments.get(name);
        if (segments != null && segments.size() < 3) {
            segments.add(audioSamples);
        }
    }

    /**
     * 完成注册
     */
    public boolean finishEnrollment(String name, int sampleRate) {
        List<float[]> segments = pendingEnrollments.remove(name);
        if (segments == null || segments.isEmpty()) return false;
        return registerSpeakerMultiSegment(name, segments, sampleRate);
    }

    /**
     * 检查是否已注册
     */
    public boolean hasSpeaker(String name) {
        if (!initialized) return false;
        return manager.contains(name);
    }

    /**
     * 获取相似度阈值
     */
    public float getThreshold() {
        return similarityThreshold;
    }
}
