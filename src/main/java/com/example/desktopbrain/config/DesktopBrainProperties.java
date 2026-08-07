package com.example.desktopbrain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 桌面助手统一配置属性（对应 application.yml 中的 desktopbrain.* 段）。
 * 消除各 Service 中散落的硬编码常量，统一由此注入。
 */
@ConfigurationProperties(prefix = "desktopbrain")
public record DesktopBrainProperties(
        Voice voice,
        Vad vad,
        Dialog dialog,
        Voiceprint voiceprint,
        ToolPlanner toolPlanner,
        Execution execution,
        Semantic semantic,
        DeepSeek deepseek,
        Exploration exploration,
        MemoryMaintenance memoryMaintenance
) {

    /** 语音交互参数 */
    public record Voice(
            String wakeWord,
            int sampleRate,
            double energyThreshold,
            int minSpeechDurationMs,
            int maxSpeechDurationMs,
            int silenceTimeoutMs,
            int ttsEchoDrainMs,
            int eventPollIntervalMs,
            Beep beep
    ) {
        public record Beep(int frequency, int amplitude) {}
    }

    /** VAD 参数 */
    public record Vad(
            float threshold,
            float minSilenceDuration,
            float minSpeechDuration,
            float maxSpeechDuration,
            int windowSize
    ) {}

    /** 对话关键词 */
    public record Dialog(
            List<String> exitKeywords,
            List<String> interruptKeywords,
            List<String> confirmKeywords,
            List<String> rejectKeywords,
            List<String> supplementKeywords,
            int debounceWindowMs,
            int followUpWindowMs,
            int followUpSilenceHintMs
    ) {}

    /** 声纹参数 */
    public record Voiceprint(
            int maxEnrollmentSegments
    ) {}

    /** 工具规划参数 */
    public record ToolPlanner(
            int maxCacheSize,
            int failureThreshold,
            List<String> fallbackTools,
            List<String> alwaysAppendTools
    ) {}

    /** 执行校验参数 */
    public record Execution(
            List<String> failureMarkers,
            List<String> aiFailureKeywords
    ) {}

    /** 语义匹配参数 */
    public record Semantic(
            double relatedRatioThreshold,
            String voiceToneFilterRegex
    ) {}

    /** DeepSeek 远程模型参数（模型 1） */
    public record DeepSeek(
            String baseUrl,
            String apiKey,
            String model
    ) {}

    /** 自主探索引擎参数（模型 2: Ollama） */
    public record Exploration(
            boolean enabled,
            int idleThresholdMinutes,
            int maxDurationMinutes,
            List<Integer> blackoutHours,
            String model,
            List<String> allowedDomains
    ) {}

    /** 记忆维护参数 */
    public record MemoryMaintenance(
            boolean enabled,
            String cron,
            double threshold,
            double target,
            Weights weights,
            RetentionRules retentionRules
    ) {
        public record Weights(double recency, double usage, double userProduced, double dependency) {}
        public record RetentionRules(int userTask, int autonomousExploration, int failurePattern, int defaultDays) {}
    }
}
