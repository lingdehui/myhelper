# MyHelper — 本地化、能自我进化的 AI 桌面助手

完整文档从 [docs/README.md](docs/README.md) 开始。该入口区分当前实现、历史版本、研究参考和审计草稿。

> 说一句"**那个谁**"，它就帮你干活：查资料、装软件、控智能家居、操作屏幕。
>
> 做完的事会记住，环境条件满足时直接复用 Unit，空闲时还会自己学新本领——**越用越聪明**。

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0--M2-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0--M3-blue.svg)](https://spring.io/projects/spring-ai)

## 它是什么

MyHelper 是一个运行在你自己电脑上的多模态 AI 助手，具备：

- 🎤 **自然语音交互** — 唤醒词激活、流式对话、任意打断、声纹识别身份
- 🧠 **持久化记忆** — 语义检索历史、记住偏好习惯、经验跨重启复用
- 🌍 **统一世界模型** — 用 WorldObject + ContextUnit 统一表达对象、状态、条件、预期、推断和人格
- ⚙️ **自主执行任务** — 操控屏幕/键鼠、装软件、控家居、查资料
- 🌱 **自我学习进化** — 成功经验沉淀为可复用单元，失败自动归档为规则，空闲自主探索
- 🔌 **设备全连接** — MCP 协议统一管理 GUI、智能家居、工具链

## 核心亮点

### 三层缓存 — 记住了就零成本重复

```
第一次: "帮我查天气"  → AI 规划 + 执行
第二次: "再查天气"     → 向量命中，直接回放（~200ms）
第三次: 连续验证达标    → 检查 ContextUnit 状态后直接执行 Unit 树（零 Token）
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
环境/用户/系统自身 → 感知与状态更新 Unit
                          │
                          ▼
        WorldObject + ContextUnit 统一世界模型（Neo4j）
          │ 当前可信上下文                 ▲ 执行结果/新观测
          ▼                                │
语音/文字 → TurnProcessor → ToolPlanner → Unit
                                      │
                         REQUIREMENT 条件判断
                                      │
                         CONTAINS 能力树执行
                                      │
                         EXPECTATION 结果验证
                                      ▼
                     本地 @Tool / MCP / Home Assistant

ContextUnit：STATE 去重标准状态；OBSERVATION 保留不可变历史快照并通过 stateId 指向 STATE
Qdrant：Unit、工具和记忆的语义检索入口
自主探索 / 反思 / 元优化：读取世界状态并沉淀新能力
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
| @Tool 方法 | 133 |
| 生成工具 | 24（@GeneratedTool） |
| Neo4j 实体 | 15 |
| 核心模块 | 15+ |

## 目录结构

| 目录 | 内容 |
|------|------|
| `src/main/java` | Java 业务源码，按领域包组织 |
| `src/main/resources` | 配置、Prompt、Skill 和随包模型 |
| `docs` | 当前设计、审计记录、参考设计和历史迭代 |
| `scripts` | 诊断脚本与网络辅助脚本 |
| `data/novel-materials` | 可版本化的小说创作素材 |
| `data/private` | 联系人、会话参数等本机私密数据，不进入 Git |
| `novels` | 小说运行输出，不进入 Git |
| `models` | 运行时外置模型 |
| `lib` | Sherpa JNI 依赖和唯一的 native DLL 源目录 |

详细规则见：[项目目录约定](docs/00-项目目录约定.md)。

## 文档

| 文档 | 内容 |
|------|------|
| [00-项目目录约定](docs/00-项目目录约定.md) | 文件归属、隐私与生成物规则 |
| [01-系统总架构](docs/01-系统总架构.md) | 技术栈、模块划分、核心流程 |
| [02-代码实现清单](docs/02-代码实现清单.md) | 类与方法清单 |
| [03-核心执行流程](docs/03-核心执行流程.md) | 语音→规划→执行→记忆全链路 |
| [04-对话与语音系统](docs/04-对话与语音系统.md) | 状态机、唤醒、打断、ASR/TTS |
| [05-记忆与向量系统](docs/05-记忆与向量系统.md) | Unit 统一单元、向量检索 |
| [06-工具与技能体系](docs/06-工具与技能体系.md) | MCP/@Tool/生成工具体系 |
| [07-数据库与部署](docs/07-数据库与部署.md) | Neo4j/Qdrant/Ollama 部署 |
| [08-工具自生成与动态加载](docs/08-工具自生成与动态加载.md) | AI 生成工具机制 |
| [09-小说创作质量门禁](docs/09-小说创作质量门禁.md) | 小说计划、审阅、修订与提交门禁 |
| [10-探索知识与记忆维护](docs/10-探索知识与记忆维护.md) | 自主探索 + 记忆清理 |
| [11-记忆价值评分系统](docs/11-记忆价值评分系统.md) | 价值评分与清理策略 |
| [12-元自我优化](docs/12-元自我优化.md) | 配置实验、热应用、验证和回滚 |
| [13-递归世界模型与条件直达](docs/13-递归世界模型与条件直达.md) | WorldObject / ContextUnit / Unit 与状态刷新闭环 |
| [历史迭代：v1 脚本化 Unit](docs/history/历史迭代-v1-脚本化Unit.md) | 已退役执行模型及迁移原因 |
