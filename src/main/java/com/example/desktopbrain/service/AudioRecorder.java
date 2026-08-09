package com.example.desktopbrain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;

public class AudioRecorder {

    private static final Logger log = LoggerFactory.getLogger(AudioRecorder.class);

    /**
     * 录音结果：包含采样数据和实际使用的采样率
     */
    public record AudioData(float[] samples, float sampleRate) {}

    /**
     * 支持的采样率列表，按优先级从高到低排列（16kHz 是 ASR 模型的最佳输入）
     */
    private static final int[] SAMPLE_RATES = {16000, 44100, 48000, 22050, 11025, 8000};

    /**
     * 自动检测麦克风支持的采样率，返回最佳匹配
     */
    private static int detectSampleRate() throws Exception {
        for (int rate : SAMPLE_RATES) {
            AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            if (AudioSystem.isLineSupported(info)) {
                // System.out.println("🔍 检测到麦克风支持采样率: " + rate + " Hz");
                return rate;
            }
        }
        throw new Exception("麦克风不支持任何常用采样率 (16/44.1/48/22.05/11.025/8 kHz)");
    }

    public static AudioData record(int seconds) throws Exception {
        int sampleRate = detectSampleRate();
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();

        // System.out.println("🎤 开始录音...(" + sampleRate + " Hz)");
        ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        long endTime = System.currentTimeMillis() + seconds * 1000;
        while (System.currentTimeMillis() < endTime) {
            int count = line.read(buffer, 0, buffer.length);
            if (count > 0) pcmOut.write(buffer, 0, count);
        }
        line.stop();
        line.close();

        byte[] pcm = pcmOut.toByteArray();
        int n = pcm.length / 2;
        float[] samples = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = pcm[2 * i] & 0xff;
            int hi = pcm[2 * i + 1] & 0xff;
            int s = (hi << 8) | lo;
            if (s >= 32768) s -= 65536;
            samples[i] = (float) s / 32768.0f;
        }

        // 调试：保存为 WAV 用于验证音频数据
        // boolean ok = WaveWriter.write("mic_dbg.wav", samples, sampleRate);
        // if (ok) System.out.println("💾 调试音频已保存: mic_dbg.wav");

        return new AudioData(samples, sampleRate);
    }
}