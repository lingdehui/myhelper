# DesktopBrain 架构文档

## 项目概览

DesktopBrain 是一个 Windows 桌面 AI 助手，支持语音唤醒、语音对话、文字对话，通过 MCP 协议调用 robot-mcp 服务控制桌面（GUI 自动化）。

| 属性 | 值 |
|------|-----|
| 框架 | Spring Boot 4.1.0-M2 |
| Java | 25 |
| AI SDK | Spring AI 2.0.0-M3 |
| AI 模型 | DeepSeek (deepseek-v4-pro) |
| 语音引擎 | sherpa-onnx 1.10.39 (纯本地、无网络依赖) |
| 构建工具 | Maven |
| 端口 | 8082 |
| MCP 服务端 | robot-mcp (端口 8081) |

---

## 目录结构

```
windowsAi/
├── src/main/java/com/example/desktopbrain/
│   ├── DesktopBrainApplication.java   # 主入口、状态机、录音线程、AI调用
│   ├── service/
│   │   ├── AudioRecorder.java         # 简单录音封装
│   │   ├── FriendMatcher.java          # 联系人模糊匹配（拼音+编辑距离，@Tool）
│   │   ├── LocalASR.java              # 双引擎语音识别
│   │   ├── TtsService.java            # TTS 语音合成
│   │   ├── VadService.java            # Silero VAD 语音活动检测
│   │   ├── SkillConfig.java           # 技能规则注入
│   │   └── TestController.java        # ASR 测试接口
│   └── util/
│       └── NativeLoader.java          # JNI DLL 提取
├── src/main/java/com/example/tools/   # 工具类（给 AI Tool Calling 用）
│   ├── software/                      # 软件搜索、知识库
│   └── windows/
│       ├── download/                  # 软件下载/安装管理
│       ├── filesystem/                # 文件操作
│       └── system/                    # 系统信息
├── src/main/resources/
│   ├── application.yml                # Spring Boot 配置
│   ├── skills/                         # 技能文件目录（热加载，无需重启）
│   │   ├── wechat-friend-chat.txt       # 微信好友聊天
│   │   ├── sync-wechat-contacts.txt     # 微信联系人同步
│   │   ├── app-scrape.txt              # 通用应用数据抓取
│   │   ├── self-learn.txt              # AI 自主学习（创建新技能）
│   │   ├── wechat-public-account.txt   # 微信搜一搜
│   │   ├── app-launch.txt             # 应用启动
│   │   └── file-operations.txt        # 文件操作技能规则（关键词→指令注入）
│   └── models/
│       ├── asr-bilingual/             # 双语文档 ASR 模型（zipformer，流式）
│       └── tts-zh/                    # 中文 TTS 模型（VITS MELO）
├── models/                            # 独立模型（非 classpath 内）
│   ├── vad/silero_vad.onnx            # Silero VAD v4
│   ├── tts-zh/                        # TTS 模型运行时副本
│   └── asr-offline/                   # Paraformer 离线 ASR（对话用）
│       └── sherpa-onnx-paraformer-zh-small-2024-03-09/
├── lib/
│   ├── native/                        # JNI DLL（onnxruntime + sherpa-onnx）
│   └── sherpa-onnx-v1.10.39-java21.jar
├── docs/
    ├── ARCHITECTURE.md                # 本文档
    └── STATE_MACHINE.md               # 状态机详解
├── wechat-friends.json                # 微信联系人数据（AI自动维护）
```

---

## 组件关系图

```
┌──────────────────────────────────────────────────────────────────┐
│                        DesktopBrainApplication                    │
│                          (主入口 + 状态机)                         │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│   ┌──────────────┐   ┌──────────────┐   ┌──────────────────┐    │
│   │ 录音线程      │   │ 语音处理线程   │   │ 文字对话(主线程)  │    │
│   │ bg-recorder   │   │speech-handler │   │  Scanner + AI   │    │
│   └──────┬───────┘   └──────┬───────┘   └────────┬─────────┘    │
│          │                  │                     │              │
│          ▼                  ▼                     ▼              │
│   ┌──────────────┐   ┌──────────────┐   ┌──────────────────┐    │
│   │  VadService   │   │  SkillConfig  │   │   ChatClient     │    │
│   │  (Silero VAD) │   │  (规则注入)   │   │   (Spring AI)    │    │
│   └──────┬───────┘   └──────────────┘   └────────┬─────────┘    │
│          │                                        │              │
│          ▼                                        ▼              │
│   ┌──────────────┐                       ┌──────────────────┐    │
│   │   LocalASR    │                       │   robot-mcp      │    │
│   │  (双引擎ASR)  │                       │   (端口8081)     │    │
│   └──────┬───────┘                       └──────────────────┘    │
│          │         ┌──────────────┐                              │
│          ▼         │   TtsService  │                             │
│   ┌──────────────┐ │  (VITS MELO)  │                             │
│   │OnlineRecogn. │ │               │                             │
│   │(唤醒词，流式) │ │               │                             │
│   │              │ │               │                             │
│   │OfflineRecogn.│ │               │                             │
│   │(对话，高精度) │ │               │                             │
│   └──────────────┘ └──────────────┘                              │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

---

## 各组件说明

### 1. DesktopBrainApplication（主控制器）

`DesktopBrainApplication.java` 是整个应用的入口和核心，包含：

- **状态机**：`IDLE`（待命，监听唤醒词）和 `VOICE_DIALOG`（语音对话）
- **后台录音线程**：持续录音 → VAD 分割 → ASR 识别 → 推入 `speechQueue`
- **语音处理线程**：从 `speechQueue` 取结果 → 状态机处理 → AI 调用
- **文字对话**：主线程 `Scanner` 读输入 → AI 调用
- **中断机制**：`aiTurnId` (AtomicLong) + `currentAiTurnId` 确保只处理最新结果
- **提示音**：`playBeep()` 生成简单 beep 音反馈
- **工具合并**：启动时将 MCP 工具（robot-mcp）与本地 Tool（FriendMatcher 等）合并为统一工具集

详细状态机逻辑见 [STATE_MACHINE.md](STATE_MACHINE.md)。

### 2. LocalASR（双引擎语音识别）

`LocalASR.java` — 项目中唯一的 ASR 服务，包含两个引擎：

| 引擎 | 模型 | 用途 | 特点 |
|------|------|------|------|
| `onlineRecognizer` | zipformer int8（流式） | 唤醒词检测 | 低延迟，边录边算 |
| `offlineRecognizer` | Paraformer int8（离线） | 语音对话识别 | 高精度，等完整句子再算 |

方法：
- `recognizeOnline(samples, sampleRate)` — 唤醒词识别，逐块流式解码
- `recognizeOffline(samples, sampleRate)` — 对话识别，一次喂入完整音频解码
- `isOfflineAvailable()` — 离线引擎是否可用

模型路径：
- 在线：`src/main/resources/models/asr-bilingual/`（随 jar 打包）
- 离线：`models/asr-offline/sherpa-onnx-paraformer-zh-small-2024-03-09/`（独立文件，不打包）

### 3. VadService（语音活动检测）

`VadService.java` — 使用 Silero VAD v4 神经网络模型：

| 参数 | 值 | 说明 |
|------|-----|------|
| THRESHOLD | 0.5f | 语音判定阈值 |
| MIN_SILENCE_DURATION | 1.2s | 最短静音后切段 |
| MIN_SPEECH_DURATION | 0.25s | 最短语音长度 |
| MAX_SPEECH_DURATION | 8s | 最长语音段（防卡死） |
| WINDOW_SIZE | 512 | 分析窗口大小 |

模型：`models/vad/silero_vad.onnx`（V4，1765KB）

VAD 不可用时会自动回退到**能量检测**（ENERGY_THRESHOLD=0.02f + 2秒静音阈值）。

### 4. TtsService（语音合成）

`TtsService.java` — 使用 sherpa-onnx OfflineTts（VITS MELO 中文模型）：

- 异步播放：`speakAsync(text)` 立即返回，后台 SourceDataLine 播放
- 中断支持：`stop()` 停止当前播放（新语音输入或用户说"停"时触发）
- TTS 播放期间录音跳过（防止回音）
- 模型路径：`models/tts-zh/`（首次自动从 classpath 解压）

### 5. SkillConfig（技能规则注入 + 热加载）

`SkillConfig.java` 扫描 `src/main/resources/skills/` 目录，每个 `.txt` 文件一个技能。

**文件格式**：
```
keywords: 关键词1, 关键词2
规则内容第1行
规则内容第2行
```

- 文件名（不含 .txt）= 技能名
- 第一行 `keywords:` 定义触发关键词（支持正则）
- 其余行 = 注入到 AI prompt 的规则文本

**热加载机制**：每次 `getInstructions()` 调用时检查文件 mtime，有变更自动重新加载，无需重启。AI 通过 `self-learn` 技能创建新文件后立即生效。

| 技能 | 触发关键词 | 规则 |
|------|-----------|------|
| wechat-friend-chat | 发给、发消息、好友 | 先用 findFriend 工具纠错名字，再 Ctrl+F 搜索联系人 |
| sync-wechat-contacts | 同步微信、同步好友 | 截图+OCR 抓取微信联系人列表，生成 wechat-friends.json |
| app-scrape | 抓取、提取数据 | 通用应用数据抓取规则（任意应用） |
| self-learn | 记住、学会、生成技能 | AI 自主学习：失败后总结 → 追加新技能到 skills-config.txt |
| wechat-public-account | 公众号、搜一搜 | 用顶部搜索框选搜一搜 |
| app-launch | 打开、启动 | Win键搜索启动 |
| file-operations | 文件、文件夹 | 资源管理器操作 |

### 6. FriendMatcher（联系人模糊匹配）

`FriendMatcher.java` — 本地 Tool（通过 `@Tool` 注解暴露给 AI）：

- 数据源：`wechat-friends.json`（由 AI 通过"同步微信好友列表"技能自动维护）
- 匹配策略：
  1. 精确匹配 → 立即返回
  2. 包含匹配 → 名字相互包含
  3. 拼音匹配 → 汉字转拼音后比对
  4. Levenshtein 编辑距离 → 最相似名字
  5. 都不匹配 → 列出已知好友列表
- 内置 200+ 常用姓名汉字拼音映射，无需外部依赖

### 7. AudioRecorder（录音工具）

`AudioRecorder.java` — 简单封装 `javax.sound.sampled`，用于 `voice` 文字命令的临时录音。

### 8. NativeLoader（JNI DLL 加载）

`NativeLoader.java` — 启动时从 classpath 提取 4 个 DLL 到工作目录：
- `onnxruntime.dll`
- `onnxruntime_providers_shared.dll`
- `sherpa-onnx-cxx-api.dll`
- `sherpa-onnx-jni.dll`

---

## 外部依赖

### robot-mcp（MCP 服务端）

独立服务，端口 8081，提供 GUI 自动化工具：
- 鼠标键盘控制
- 窗口管理
- 屏幕截图/OCR
- 剪贴板操作

连接配置在 `application.yml` 中：
```yaml
spring.ai.mcp.client.streamable-http.connections.robot-mcp:
  url: http://127.0.0.1:8081/mcp
  request-timeout: 30s
```

### DeepSeek AI API

```yaml
spring.ai.openai:
  api-key: sk-xxx
  base-url: https://api.deepseek.com
  chat.options.model: deepseek-v4-pro
```

---

## 构建与运行

```powershell
# 确保 robot-mcp 已在运行（端口 8081）
# 确保模型文件已就位：
#   - models/vad/silero_vad.onnx
#   - models/asr-offline/sherpa-onnx-paraformer-zh-small-2024-03-09/

# 编译 + 打包
cd e:\projcket\my\windowsAi
.\mvnw clean package -DskipTests

# 运行
java -jar target\desktop-brain-1.0.0.jar
```

---

## 修改同步清单

当修改以下内容时，记得同步更新对应文档：

| 修改内容 | 需更新文档 |
|---------|-----------|
| 状态机逻辑 | STATE_MACHINE.md |
| 新增/修改 Service | ARCHITECTURE.md 对应组件说明 |
| 模型替换/路径变化 | ARCHITECTURE.md 组件说明 + 构建运行 |
| VAD 参数调整 | ARCHITECTURE.md VadService 表格 |
| 技能规则 | skills-config.txt + ARCHITECTURE.md SkillConfig 表格 |
| pom.xml 依赖 | ARCHITECTURE.md 项目概览表格 |
| application.yml 配置 | ARCHITECTURE.md 配置相关章节 |
