package com.example.myhelper.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;

@RestController
public class TestController {

    private final LocalASR localASR;

    public TestController(LocalASR localASR) {
        this.localASR = localASR;
    }

    @GetMapping("/test/asr/file")
    public String testFile() {
        try {
            // 读取模型自带的测试音频（16000 Hz, 16bit, mono）
            AudioInputStream audioStream = AudioSystem.getAudioInputStream(
                    new ClassPathResource("models/asr-bilingual/test_wavs/0.wav").getFile());
            AudioFormat format = audioStream.getFormat();
            if (format.getSampleRate() != 16000 || format.getSampleSizeInBits() != 16 || format.getChannels() != 1) {
                return "测试文件格式不符，需要 16000 Hz 16bit mono";
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = audioStream.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            byte[] raw = out.toByteArray();
            float[] samples = new float[raw.length / 2];
            for (int i = 0, j = 0; i < raw.length; i += 2, j++) {
                short s = (short) ((raw[i + 1] << 8) | (raw[i] & 0xff));
                samples[j] = s / 32768.0f;
            }
            // 调用分块识别（测试音频为 16000 Hz）
            return localASR.recognizeOnline(samples, 16000.0f);
        } catch (Exception e) {
            return "测试失败: " + e.getMessage();
        }
    }
}