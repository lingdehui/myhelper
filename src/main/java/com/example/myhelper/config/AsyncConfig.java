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
