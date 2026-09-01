package com.example.myhelper.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 异步线程池配置。
 * <p>统一管理项目中的后台线程，替代裸 {@code new Thread(...).start()}。</p>
 */
@Configuration
public class AsyncConfig {

    /** AI 对话工作线程池（处理用户请求，不阻塞主线程） */
    @Bean("aiExecutor")
    public ExecutorService aiExecutor() {
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ai-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** 后台语音处理线程池（录音、ASR、VAD） */
    @Bean("audioExecutor")
    public ExecutorService audioExecutor() {
        return Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "audio-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** 异步写操作线程池（Qdrant 持久化、工具生成等） */
    @Bean("asyncExecutor")
    public ExecutorService asyncExecutor() {
        return Executors.newFixedThreadPool(3, r -> {
            Thread t = new Thread(r, "async-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 前台准备线程池。
     *
     * <p>一轮对话中，记忆召回和工具规划互不依赖。将规划放到独立线程池后，
     * 主处理线程可以同时完成记忆和技能准备，缩短用户等待时间。该线程池不能与
     * {@code aiExecutor} 共用：后者会承载完整 Turn，若 Turn 自己等待同池任务，
     * 高并发时可能造成线程相互等待。</p>
     *
     * <p>队列饱和时由提交线程执行，保证不会因性能优化而丢失规划能力。</p>
     */
    @Bean("turnPreparationExecutor")
    public ExecutorService turnPreparationExecutor() {
        return new ThreadPoolExecutor(
                2, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                r -> {
                    Thread t = new Thread(r, "turn-preparation");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /** 计划生成线程池（文档 15 v1.7 §7.3：核心2 最大4 队列100 CallerRunsPolicy） */
    @Bean("planGenerationPool")
    public ExecutorService planGenerationPool() {
        return new ThreadPoolExecutor(
                2, 4,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                r -> {
                    Thread t = new Thread(r, "plan-generation");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
