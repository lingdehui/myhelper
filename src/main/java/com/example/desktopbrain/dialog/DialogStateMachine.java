package com.example.desktopbrain.dialog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 对话状态机 — 管理对话轮次的状态转换
 *
 * 状态:
 *   IDLE         → 待命，只检测唤醒词
 *   LISTENING    → 精确监听，用户正在说话
 *   PROCESSING   → AI 正在处理
 *   SPEAKING     → TTS 正在播放回复
 *   INTERRUPTED  → 被用户打断
 *
 * 转换:
 *   IDLE ──唤醒词──→ LISTENING
 *   LISTENING ──用户说完──→ PROCESSING
 *   PROCESSING ──AI 生成回复──→ SPEAKING
 *   SPEAKING ──TTS 播放完毕──→ LISTENING
 *   SPEAKING ──用户说话──→ INTERRUPTED
 *   INTERRUPTED ──处理打断内容──→ LISTENING
 */
@Component
public class DialogStateMachine {

    public enum State {
        IDLE,
        LISTENING,
        PROCESSING,
        SPEAKING,
        INTERRUPTED
    }

    @Value("${desktopbrain.dialogue.silence-timeout-ms:5000}")
    private int silenceTimeoutMs;

    private volatile State currentState = State.IDLE;
    private volatile long stateEnterTime = 0;
    private volatile long lastInteractionTime = System.currentTimeMillis();

    /**
     * 转换到新状态
     */
    public synchronized void transitionTo(State newState) {
        if (this.currentState != newState) {
            System.out.println("🔄 对话状态: " + currentState + " → " + newState);
            this.currentState = newState;
            this.stateEnterTime = System.currentTimeMillis();
        }
    }

    /**
     * 当前状态
     */
    public State getState() {
        return currentState;
    }

    /**
     * 当前是否可以接受用户输入
     * LISTENING / SPEAKING / INTERRUPTED 状态都可以接收
     */
    public boolean canAcceptInput() {
        State s = currentState;
        return s == State.LISTENING
            || s == State.SPEAKING
            || s == State.INTERRUPTED;
    }

    /** 标记交互时间（每次收到用户输入时调用） */
    public void touch() {
        this.lastInteractionTime = System.currentTimeMillis();
    }

    /** 获取最后一次交互时间 */
    public long getLastInteractionTime() {
        return lastInteractionTime;
    }

    /**
     * 是否处于 AI 处理中
     */
    public boolean isProcessing() {
        return currentState == State.PROCESSING;
    }

    /**
     * 是否处于 AI/TTS 播放中（可以被打断）
     */
    public boolean canInterrupt() {
        State s = currentState;
        return s == State.PROCESSING || s == State.SPEAKING;
    }

    /**
     * 是否在 LISTENING 状态超时（无语音 → 回到 IDLE）
     */
    public boolean isSilenceTimeout() {
        if (currentState != State.LISTENING) return false;
        return System.currentTimeMillis() - stateEnterTime > silenceTimeoutMs;
    }

    /**
     * 重置到 IDLE
     */
    public void reset() {
        transitionTo(State.IDLE);
    }
}
