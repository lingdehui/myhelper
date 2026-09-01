# MyHelper — 本地化、能自我进化的 AI 桌面助手

> 说一句"**那个谁**"，它就帮你干活：查资料、装软件、控智能家居、操作屏幕。
>
> 做完的事会记住，重复的事自动脚本化，空闲时还会自己学新本领——**越用越聪明**。

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0--M2-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0--M3-blue.svg)](https://spring.io/projects/spring-ai)
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](LICENSE)

## 它是什么

MyHelper 是一个运行在你自己电脑上的多模态 AI 助手，具备：

- 🎤 **自然语音交互** — 唤醒词激活、流式对话、任意打断、声纹识别身份
- 🧠 **持久化记忆** — 语义检索历史、记住偏好习惯、经验跨重启复用
- ⚙️ **自主执行任务** — 操控屏幕/键鼠、装软件、控家居、查资料
- 🌱 **自我学习进化** — 成功经验沉淀为可复用单元，失败自动归档为规则，空闲自主探索
- 🔌 **设备全连接** — MCP 协议统一管理 GUI、智能家居、工具链

## 核心亮点

### 三层缓存 — 记住了就零成本重复

```
第一次: "帮我查天气"  → AI 规划 + 执行
第二次: "再查天气"     → 向量命中，直接回放（~200ms）
第三次: 连续成功 5 次   → 升级为脚本化，纯 Java 按步执行（零 Token）
```

### 经验闭环 — 越用越聪明

```
成功 → 沉淀为可复用 Unit（完全相同/某步不同/差异大 三分支）
失败 → AI 归因（计划问题/环境问题）→ FailureCause 复用
归纳 → 每天凌晨 3 点跨案例提炼规则 → 注入规划 prompt
```

### 自主探索 — 空闲时自学新能力

```
空闲 30 分钟 → 自动巡检 → 云端决策学什么 → 主管线执行 → 结果压缩为摘要
（上下文压缩器保证探索过程不会无限膨胀）
```

### 自我进化 — 工具不够自己造

```
现有工具不够？AI 自动生成 Java 工具类 → 编译 → 动态加载
同一工具连续 3 次失败 → 自动删除，防止垃圾代码堆积
```

## 架构

```
┌──────────────────────────────────────────────────────────────────┐
│                          MyHelper                                │
│                                                                  │
│  语音层            核心执行层            记忆系统                 │
│  ──────            ──────               ──────                   │
│  唤醒词检测        TurnProcessor        Qdrant (向量, bge-m3)    │
│  双模 ASR          ToolPlanner          Neo4j  (知识图谱)         │
│  声纹识别          三层缓存             Unit   (统一经验单元)      │
│  TTS + 打断        UniversalUnitExecutor                          │
│                            │                                     │
│                     ┌──────┴──────┐    自主探索引擎               │
│                     │   工具层    │    ───────────               │
│                     │  MCP 协议   │    空闲 30 分钟触发           │
│                     │  138 个 @Tool│   云端决策 + 上下文压缩       │
│                     │  25 个生成   │    串行执行                  │
│                     └─────────────┘                              │
└──────────────────────────────────────────────────────────────────┘
```

## 快速开始

> **前置要求**：Windows x64、JDK 25、Docker Desktop

```bash
# 一键启动（自动：Docker → Ollama → robot-mcp → myhelper）
.\start-env.bat

# 或手动分步：
docker compose up -d            # Neo4j + Qdrant + Home Assistant
ollama pull bge-m3              # 中文嵌入模型（约 2GB）
mvn compile -DskipTests
mvn spring-boot:run
```

**验证地址**：

| 服务 | 地址 |
|------|------|
| 应用 | http://localhost:8082 |
| Neo4j | http://localhost:7474 |
| Qdrant | http://localhost:6333 |
| Ollama | http://localhost:11434 |
| robot-mcp | http://localhost:8081/mcp |

## 交互方式

| 方式 | 操作 |
|------|------|
| **语音** | 说"那个谁"唤醒 → 对话 → 说"退出"结束 |
| **文字** | 终端直接输入，回复语音播报 |
| **打断** | AI 说话/思考时直接开口，实时中断 |
| **探索** | 说"学点新东西"手动触发自学 |

## 技术栈

| 层 | 技术 |
|----|------|
| 语言 | Java 25 |
| 框架 | Spring Boot 4.1.0-M2 / Spring AI 2.0.0-M3 |
| 模型 | DeepSeek（主对话）+ bge-m3（中文嵌入） |
| 语音 | Sherpa-ONNX（ASR + TTS + 声纹）+ Silero VAD |
| 向量 | Qdrant（1024 维） |
| 图谱 | Neo4j（知识图谱） |
| 设备 | MCP 协议（robot-mcp）+ Home Assistant REST |

## 项目规模

| 指标 | 数量 |
|------|------|
| @Tool 方法 | 138 |
| 生成工具 | 25（@GeneratedTool） |
| Neo4j 实体 | 10 |
| 核心模块 | 15+ |

## 文档

| 文档 | 内容 |
|------|------|
| [01-系统总架构](docs/01-系统总架构.md) | 技术栈、模块划分、核心流程 |
| [02-代码实现清单](docs/02-代码实现清单.md) | 类与方法清单 |
| [03-核心执行流程](docs/03-核心执行流程.md) | 语音→规划→执行→记忆全链路 |
| [04-对话与语音系统](docs/04-对话与语音系统.md) | 状态机、唤醒、打断、ASR/TTS |
| [05-记忆与向量系统](docs/05-记忆与向量系统.md) | Unit 统一单元、向量检索 |
| [06-工具与技能体系](docs/06-工具与技能体系.md) | MCP/@Tool/生成工具体系 |
| [07-数据库与部署](docs/07-数据库与部署.md) | Neo4j/Qdrant/Ollama 部署 |
| [08-工具自生成与动态加载](docs/08-工具自生成与动态加载.md) | AI 生成工具机制 |
| [10-探索知识与记忆维护](docs/10-探索知识与记忆维护.md) | 自主探索 + 记忆清理 |
| [11-记忆价值评分系统](docs/11-记忆价值评分系统.md) | 价值评分与清理策略 |

## License

[MIT](LICENSE)
