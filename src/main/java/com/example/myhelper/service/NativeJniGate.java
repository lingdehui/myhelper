package com.example.myhelper.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * sherpa-onnx JNI 门控 —— 防止多线程并发访问原生库导致死锁。
 *
 * 规则：
 * 1. TTS（generate）调用者：使用 {@link #lock()}/{@link #unlock()} 阻塞等待
 * 2. 录音线程（VAD/ASR）：使用 {@link #tryLock()} 非阻塞尝试，失败则跳过本次循环
 */
@Component
public class NativeJniGate {

    private final ReentrantLock lock = new ReentrantLock();

    /** 阻塞获取锁（用于 TTS generate，等待录音线程释放） */
    public void lock() { lock.lock(); }

    /** 释放锁 */
    public void unlock() { lock.unlock(); }

    /** 非阻塞尝试获取锁（用于录音线程，失败则跳过本次循环） */
    public boolean tryLock() { return lock.tryLock(); }
}
