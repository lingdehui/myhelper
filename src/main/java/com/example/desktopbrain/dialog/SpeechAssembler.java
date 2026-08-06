package com.example.desktopbrain.dialog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 语音拼接器 — 解决"一句话被切成多段"的问题
 *
 * 原理：VAD 检测到语音段时不立即处理，而是在 debounce 窗口内拼接多段语音。
 * 只有超过窗口时间无新语音时，才确认为完整句子并发送给 AI。
 *
 * 用法：
 *   assembler.addSegment("打开微信")    → true（已拼接，继续等待）
 *   assembler.addSegment("给张三发消息") → true（已拼接）
 *   // 500ms 无新语音
 *   assembler.hasPending()             → true（可以获取完整句子了）
 *   assembler.getFullSentence()        → "打开微信给张三发消息"
 */
@Component
public class SpeechAssembler {

    @Value("${desktopbrain.dialogue.debounce-window-ms:500}")
    private int debounceWindowMs;

    private final List<String> segments = Collections.synchronizedList(new ArrayList<>());
    private volatile long lastSegmentTime = 0;
    private volatile boolean hasPending = false;

    /**
     * 添加一个语音片段
     *
     * @param text ASR 识别出的文本
     * @return true=已拼接进当前句子，继续等待；false=当前片段属于新句子，先处理之前的
     */
    public synchronized boolean addSegment(String text) {
        long now = System.currentTimeMillis();
        if (now - lastSegmentTime < debounceWindowMs) {
            // 在窗口内，拼接
            segments.add(text);
            lastSegmentTime = now;
            hasPending = true;
            return true;
        }

        // 超过窗口：之前的句子需要先处理
        if (!segments.isEmpty()) {
            hasPending = true;
            return false;
        }

        // 之前没有待处理的，直接加入
        segments.add(text);
        lastSegmentTime = now;
        hasPending = false;
        return true;
    }

    /**
     * 获取完整句子（拼接所有待处理片段），并清空
     *
     * @return 拼接后的完整文本；如果没有待处理片段返回 null
     */
    public synchronized String getFullSentence() {
        if (segments.isEmpty()) return null;
        String result = String.join("", segments);
        segments.clear();
        hasPending = false;
        return result;
    }

    /**
     * 是否有待处理的片段（且已超过 debounce 窗口）
     */
    public synchronized boolean hasPending() {
        return hasPending && (System.currentTimeMillis() - lastSegmentTime >= debounceWindowMs);
    }

    /**
     * 获取当前已拼接但还未确认的片段数（调试用）
     */
    public synchronized int getPendingSegmentCount() {
        return segments.size();
    }

    /**
     * 清空所有片段
     */
    public synchronized void clear() {
        segments.clear();
        hasPending = false;
    }
}
