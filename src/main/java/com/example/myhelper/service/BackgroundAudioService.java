package com.example.myhelper.service;

import com.example.myhelper.config.MyHelperProperties;
import com.example.myhelper.dialog.SpeechAssembler;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

@Service
public class BackgroundAudioService {

    private static final Logger log = LoggerFactory.getLogger(BackgroundAudioService.class);

    private final MyHelperProperties props;
    private final LocalASR localASR;
    private final VadService vadService;
    private final TtsService ttsService;
    private final VoiceprintService voiceprintService;
    private final SpeechAssembler speechAssembler;
    private final TurnProcessor turnProcessor;
    private final LinkedBlockingQueue<String> speechQueue;
    private final SpeechEventLoop speechEventLoop;
    private final NativeJniGate jniGate;

    private volatile String currentSpeaker = null;

    public BackgroundAudioService(MyHelperProperties props,
                                  LocalASR localASR,
                                  VadService vadService,
                                  TtsService ttsService,
                                  VoiceprintService voiceprintService,
                                  SpeechAssembler speechAssembler,
                                  TurnProcessor turnProcessor,
                                  LinkedBlockingQueue<String> speechQueue,
                                  SpeechEventLoop speechEventLoop,
                                  NativeJniGate jniGate) {
        this.props = props;
        this.localASR = localASR;
        this.vadService = vadService;
        this.ttsService = ttsService;
        this.voiceprintService = voiceprintService;
        this.speechAssembler = speechAssembler;
        this.turnProcessor = turnProcessor;
        this.speechQueue = speechQueue;
        this.speechEventLoop = speechEventLoop;
        this.jniGate = jniGate;
    }

    public String getCurrentSpeaker() {
        return currentSpeaker;
    }

    public void start() {
        Thread recorderThread = new Thread(this::runRecordingLoop, "bg-recorder");
        recorderThread.setDaemon(true);
        recorderThread.start();
    }

    private void runRecordingLoop() {
        TargetDataLine line = null;
        try {
            AudioFormat format = new AudioFormat(props.voice().sampleRate(), 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
            line = (TargetDataLine) AudioSystem.getLine(info);
            line.open(format, 16000);
            line.start();

            byte[] buffer = new byte[3200];
            boolean wasSpeaking = false;
            ByteArrayOutputStream energyBuf = new ByteArrayOutputStream();
            long silenceStart = 0, speechStart = 0;
            boolean isSpeaking = false;
            final boolean useVad = vadService.isAvailable();
            float[] trailBuf = new float[props.voice().sampleRate() / 5];
            int trailPos = 0;
            long ttsEchoUntil = 0;

            OnlineStream idleStream = null;
            long idleStreamCreated = 0;

            if (!useVad) log.info("ℹ️ VAD 未就绪，使用能量检测回退");

            while (!Thread.currentThread().isInterrupted()) {
                int available = line.available();
                if (available <= 0) {
                    try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                    continue;
                }
                int count = line.read(buffer, 0, Math.min(available, buffer.length));
                if (count <= 0) continue;

                // 获取 JNI 门锁：TTS 正在调用 generate() 时跳过，防止原生死锁
                boolean gotLock = jniGate.tryLock();
                if (!gotLock) {
                    try { Thread.sleep(5); } catch (InterruptedException e) { break; }
                    continue;
                }
                try {
                    if (ttsService.isPlaying()) {
                        ttsEchoUntil = System.currentTimeMillis() + props.voice().ttsEchoDrainMs();
                        if (useVad) vadService.clear();
                        else { isSpeaking = false; energyBuf.reset(); silenceStart = 0; }
                        wasSpeaking = false;
                        continue;
                    }
                    if (System.currentTimeMillis() < ttsEchoUntil) {
                        if (useVad) vadService.clear();
                        trailPos = 0;
                        Arrays.fill(trailBuf, 0);
                        wasSpeaking = false;
                        continue;
                    }

                    float[] samples = pcmToFloat(buffer, count);

                for (float s : samples) {
                    trailBuf[trailPos] = s;
                    trailPos = (trailPos + 1) % trailBuf.length;
                }

                // Wake word detection (IDLE mode)
                Object currentMode = speechEventLoop.getMode().name();
                if ("IDLE".equals(String.valueOf(currentMode)) && useVad) {
                    if (idleStream == null) {
                        idleStream = localASR.createStream();
                        idleStreamCreated = System.currentTimeMillis();
                    }
                    String partial = localASR.feedStream(idleStream, samples, props.voice().sampleRate(), false);
                    if (partial.contains(props.voice().wakeWord())) {
                        log.info("🎤 流式识别: {} → 唤醒！", partial);
                        speechQueue.offer(props.voice().wakeWord());
                        idleStream.release();
                        idleStream = null;
                    }
                    if (System.currentTimeMillis() - idleStreamCreated > 3000) {
                        idleStream.release();
                        idleStream = null;
                    }
                } else if (!"IDLE".equals(String.valueOf(currentMode)) && idleStream != null) {
                    idleStream.release();
                    idleStream = null;
                }

                if (useVad) {
                    vadService.acceptWaveform(samples);
                    while (vadService.hasSegment()) {
                        SpeechSegment seg = vadService.nextSegment();
                        float[] speech = seg.getSamples();
                        if (speech.length > props.voice().sampleRate() * 0.25f) {
                            if (calcEnergyFallback(speech) < (float) props.voice().energyThreshold()) continue;
                            float[] withTrail = new float[speech.length + trailBuf.length];
                            System.arraycopy(speech, 0, withTrail, 0, speech.length);
                            for (int j = 0; j < trailBuf.length; j++) {
                                withTrail[speech.length + j] = trailBuf[(trailPos + j) % trailBuf.length];
                            }

                            String speaker = null;
                            if (voiceprintService.isAvailable() && voiceprintService.listSpeakers().length > 0) {
                                speaker = voiceprintService.search(withTrail, props.voice().sampleRate());
                                currentSpeaker = speaker;
                            }

                            String text;
                            if ("VOICE_DIALOG".equals(String.valueOf(currentMode)) && localASR.isOfflineAvailable()) {
                                text = localASR.recognizeOffline(withTrail, props.voice().sampleRate());
                            } else {
                                text = localASR.recognizeOnline(withTrail, props.voice().sampleRate());
                            }
                            if (text != null && !text.isEmpty() && !text.startsWith("(")) {
                                boolean appended = speechAssembler.addSegment(text);
                                if (!appended && speechAssembler.hasPending()) {
                                    String complete = speechAssembler.getFullSentence();
                                    if (complete != null && !complete.isEmpty()) {
                                        log.info("🎤 {}: {}", (currentSpeaker != null ? "[" + currentSpeaker + "]" : ""), complete);
                                        speechQueue.offer(complete);
                                    }
                                }
                            }
                        }
                    }
                    boolean nowSpeaking = vadService.isSpeech();
                    if (nowSpeaking && !wasSpeaking) {
                        log.info("🎤 正在听... ");
                        playBeep(props.voice().beep().frequency(), 80);
                    }
                    wasSpeaking = nowSpeaking;
                    if (!turnProcessor.isActive() && speechAssembler.hasPending()) {
                        String complete = speechAssembler.getFullSentence();
                        if (complete != null && !complete.isEmpty()) {
                            log.info("🎤 {}: {}", (currentSpeaker != null ? "[" + currentSpeaker + "]" : ""), complete);
                            speechQueue.offer(complete);
                        }
                    }
                } else {
                    float energy = calcEnergyFallback(samples);
                    if (!isSpeaking) {
                        if (energy > (float) props.voice().energyThreshold()) {
                            isSpeaking = true;
                            speechStart = System.currentTimeMillis();
                            silenceStart = 0;
                            energyBuf.reset();
                            log.info("🎤 正在听... ");
                            playBeep(props.voice().beep().frequency(), 80);
                        }
                    } else {
                        energyBuf.write(buffer, 0, count);
                        if (energy < (float) props.voice().energyThreshold()) {
                            if (silenceStart == 0) silenceStart = System.currentTimeMillis();
                            if (System.currentTimeMillis() - silenceStart > props.voice().silenceTimeoutMs()) {
                                if (System.currentTimeMillis() - speechStart > props.voice().minSpeechDurationMs()) {
                                    float[] energySamples = pcmToFloat(energyBuf.toByteArray());

                                    String speaker = null;
                                    if (voiceprintService.isAvailable() && voiceprintService.listSpeakers().length > 0) {
                                        speaker = voiceprintService.search(energySamples, props.voice().sampleRate());
                                        currentSpeaker = speaker;
                                    }

                                    String text;
                                    if ("VOICE_DIALOG".equals(String.valueOf(currentMode)) && localASR.isOfflineAvailable()) {
                                        text = localASR.recognizeOffline(energySamples, props.voice().sampleRate());
                                    } else {
                                        text = localASR.recognizeOnline(energySamples, props.voice().sampleRate());
                                    }
                                    if (text != null && !text.isEmpty() && !text.startsWith("(")) {
                                        speechAssembler.addSegment(text);
                                    }
                                }
                                isSpeaking = false;
                                silenceStart = 0;
                            }
                        } else { silenceStart = 0; }
                        if (System.currentTimeMillis() - speechStart > props.voice().maxSpeechDurationMs()) {
                            isSpeaking = false; silenceStart = 0; energyBuf.reset();
                        }
                    }
                }

                if (speechAssembler.hasPending()) {
                    String complete = speechAssembler.getFullSentence();
                    if (complete != null && !complete.isEmpty()) {
                        log.info("🎤 {}: {}", (currentSpeaker != null ? "[" + currentSpeaker + "]" : ""), complete);
                        speechQueue.offer(complete);
                    }
                }
                } finally {
                    jniGate.unlock();
                }
            }
        } catch (Exception e) {
            log.error("❌ 录音线程异常", e);
        } finally {
            if (line != null) {
                try { line.stop(); line.close(); } catch (Exception ex) {
                    log.error("⚠️ 音频设备关闭异常", ex);
                }
            }
        }
    }

    private void playBeep(int freqHz, int durationMs) {
        try {
            float sampleRate = 8000;
            int samples = (int) (sampleRate * durationMs / 1000.0f);
            byte[] buf = new byte[samples];
            for (int i = 0; i < samples; i++) {
                double angle = 2.0 * Math.PI * freqHz * i / sampleRate;
                buf[i] = (byte) (Math.sin(angle) * props.voice().beep().amplitude());
            }
            AudioFormat fmt = new AudioFormat(sampleRate, 8, 1, true, false);
            try (Clip clip = AudioSystem.getClip()) {
                clip.open(fmt, buf, 0, buf.length);
                clip.start();
                Thread.sleep(durationMs + 50);
                clip.stop();
            }
        } catch (Exception e) {
            log.error("⚠️ 提示音播放失败", e);
        }
    }

    private float calcEnergyFallback(float[] samples) {
        double sum = 0;
        for (float s : samples) sum += s * s;
        return (float) Math.sqrt(sum / samples.length);
    }

    private float[] pcmToFloat(byte[] pcm) {
        return pcmToFloat(pcm, pcm.length);
    }

    private float[] pcmToFloat(byte[] pcm, int len) {
        int n = len / 2;
        float[] samples = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = pcm[2 * i] & 0xff;
            int hi = pcm[2 * i + 1] & 0xff;
            int s = (hi << 8) | lo;
            if (s >= 32768) s -= 65536;
            samples[i] = (float) s / 32768.0f;
        }
        return samples;
    }
}
