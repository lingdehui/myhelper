# DesktopBrain 状态机详解

## 双模式状态机

```
                   说"那个谁"
  ┌─────────┐ ───────────────> ┌──────────────┐
  │  IDLE    │                  │ VOICE_DIALOG │
  │  (待命)   │ <─────────────── │  (语音对话)   │
  └─────────┘  5秒沉默 / "退出"  └──────────────┘
       │                              │
       │ 文字输入                      │ 语音输入
       ▼                              ▼
  ┌─────────┐                  ┌──────────────┐
  │ AI 处理  │                  │ AI 处理(异步) │
  │ (同步)   │                  │ + TTS 播报   │
  └─────────┘                  └──────────────┘
```

---

## 模式详细说明

### IDLE 模式（待命）

- **监听唤醒词**："那个谁"
- 后端录音线程持续运行，VAD 分割 → 在线 ASR 识别 → 推入队列
- 语音处理线程检查识别结果是否包含唤醒词
- 文字输入直接触发 AI 调用
- 非唤醒词的语音会被忽略（打日志不处理）

### VOICE_DIALOG 模式（语音对话）

- **唤醒后自动进入**，TTS 说"我在"
- 所有语音识别结果都会送去 AI 处理（异步，不阻塞录音）
- 支持中断和退出命令
- 5 秒无语音自动回到 IDLE（VAD 感知：说话中不计时）

---

## 中断机制

使用 `AtomicLong aiTurnId` + `volatile currentAiTurnId` 实现：

```java
// 启动 AI 调用时:
long myTurnId = aiTurnId.incrementAndGet();  // 获取唯一 ID
currentAiTurnId = myTurnId;                   // 标记当前正在执行

// AI 结果返回时:
if (currentAiTurnId == myTurnId) {
    // 我的结果仍然有效，处理并播报
    ttsService.speakAsync(response);
}
// 否则: 结果过期丢弃（被新输入或"停"中断了）

// 用户说"停":
currentAiTurnId = -1;  // 让上一个 turn 的结果被丢弃
ttsService.stop();     // 停止当前语音播放
```

### 中断触发条件

| 触发方式 | 效果 |
|---------|------|
| 语音对话中说"停"/"打断"/"取消" | 丢弃当前 AI 结果 + 停止 TTS，保持对话模式 |
| 新语音输入到达（AI 正在执行时） | `aiTurnId.incrementAndGet()` 生成新 ID，旧结果自动被丢弃 |
| 说"退出"/"再见"/"结束对话" | 中断 AI + 停止 TTS + 回到 IDLE |
| 5 秒无语音超时 | 回到 IDLE |

---

## 超时机制（VAD 感知）

**不是固定计时器**，而是基于 `silenceCount` 计数器：

```java
// 每 500ms poll 一次队列
String text = speechQueue.poll(500, MILLISECONDS);

if (text == null) {
    if (vadService.isSpeech()) {
        silenceCount = 0;  // 用户还在说话，不计数
    } else {
        silenceCount++;
        if (currentAiTurnId == -1 && silenceCount >= 10) {
            // 10 × 500ms = 5 秒沉默 + AI 未在执行 → 超时退出
            mode = Mode.IDLE;
        }
    }
} else {
    silenceCount = 0;  // 有新语音输入，归零
}
```

**关键点**：
- VAD 检测到用户正在说话 → `silenceCount` 不会增长 → 不会超时
- 只有 VAD 也说沉默了 + AI 不忙 → 才开始计时
- AI 执行期间（`currentAiTurnId != -1`）即使沉默也不超时

---

## 音频处理流水线

```
麦克风 → TargetDataLine (16kHz, 16bit mono)
  │
  ├─ TTS 播放中? → 跳过此帧（防回音）
  │
  ├─ VAD (Silero v4) 逐帧喂入
  │     │
  │     ├─ 检测到语音段? → 能量校验 → 拼接尾音缓冲(200ms) → ASR 识别
  │     │
  │     └─ 未就绪? → 能量检测回退 (ENERGY_THRESHOLD=0.02, 静音2秒切段)
  │
  └─ ASR:
       ├─ IDLE 模式 → recognizeOnline() (OnlineRecognizer, 流式)
       └─ VOICE_DIALOG 模式 → recognizeOffline() (OfflineRecognizer, 离线)
```

### 尾音缓冲 (200ms)

VAD 可能会在发音末尾切掉最后一个字（如"那个谁"丢"谁"），通过保留最近 200ms 音频拼接到每段末尾解决。

```java
float[] trailBuf = new float[3200];  // 200ms @16kHz
// 持续环形写入
// 每段识别时将 trailBuf 拼接到 speech segment 后面
```

---

## TTS 播放流程

```
speakAsync(text)
  │
  ├─ tts.generate(text)  → 合成音频（同步）
  │
  ├─ playQueue.offer(audio)  → 推入播放队列
  │
  └─ playbackLoop 线程:
       ├─ playQueue.take()  → 取出任务
       ├─ SourceDataLine 播放 PCM
       ├─ playing = true  → 录音线程跳过
       └─ 播放完毕: playing = false
```

**防回音**：TTS 播放期间（`ttsService.isPlaying() == true`），录音线程的音频帧直接丢弃，不喂给 VAD/ASR。

---

## AI 调用流程

```
processAITurn(userInput)
  │
  ├─ 获取唯一 turnId = aiTurnId.incrementAndGet()
  │
  ├─ currentAiTurnId = turnId  (标记"AI 忙")
  │
  ├─ SkillConfig.getInstructions(userInput)  → 匹配技能规则
  │     └─ 如果有匹配 → 拼接到 userInput 前面
  │
  ├─ chatClient.prompt()
  │     .user(effectiveInput)
  │     .toolCallbacks(mcpTools)    → Spring AI 自动路由给 robot-mcp
  │     .call().content()
  │
  ├─ 检查 currentAiTurnId == turnId?
  │     ├─ 是 → 打印结果 + TTS 播报
  │     └─ 否 → 丢弃（被中断了）
  │
  └─ finally: currentAiTurnId = -1  (标记"空闲")
```

### 语音模式特殊处理

- voice 对话模式中 AI 调用是**异步**的（`processAITurnAsync` 用独立线程）
- 这样后台录音线程不会被阻塞，用户随时可以说"停"中断

---

## 状态机变量一览

| 变量 | 类型 | 用途 |
|------|------|------|
| `mode` | `volatile Mode` | 当前模式：IDLE / VOICE_DIALOG |
| `aiTurnId` | `AtomicLong` | AI 调用自增 ID 生成器 |
| `currentAiTurnId` | `volatile long` | 当前正在执行的 turnId，-1 表示空闲 |
| `silenceCount` | `volatile int` | 连续沉默计数（500ms/次） |
| `speechQueue` | `LinkedBlockingQueue<String>` | 语音识别结果队列 |
| `WAKE_WORD` | `"那个谁"` | 唤醒词 |
| `ENERGY_THRESHOLD` | `0.02f` | 能量检测回退阈值 |

---

## 快速排查

| 现象 | 可能原因 | 检查位置 |
|------|---------|---------|
| 唤醒不了 | VAD 阈值太高/唤醒词不匹配 | VadService.THRESHOLD, WAKE_WORD |
| 识别不准 | ASR 模型问题 | LocalASR 引擎选择 |
| 说话中自动退出 | silenceCount 逻辑 | handleSpeechEvents 超时检查 |
| TTS 没声音 | 模型未解压/音频设备 | models/tts-zh/, 系统音频输出 |
| AI 不响应 | robot-mcp 未启动/API key | 检查 8081 端口、application.yml |
| "停"不生效 | currentAiTurnId 逻辑 | interruptCurrentAi() |
