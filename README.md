# MyHelper

> 共同开发规则（2026-11-11 用户确认）：固定 LLM 文本集中到 LlmTexts；不用 Lambda，以可读性选择循环、方法和 switch，允许 `case ... ->`；所有测试与检查资料统一放入 `checks`。所有任务继续前阅读[24-LLM文本与代码可读性规范](docs/24-LLM文本与代码可读性规范.md)。

更新：2026-11-18。这里是旧 MyHelper 的模块化重构项目。当前提供终端与 Swing 桌面调试入口，使用同一套递归并发编排执行任务。

## 阅读入口

**[应用入口怎么启动](docs/modules/interaction/20-交互与语音开发文档.md#应用启动链路)** · [入口与循环思维导图](docs/completed/已实现架构图.md#一次任务的思维导图) · [文档总目录](docs/README.md) · [模块索引](docs/modules/README.md) · [待做与设计](docs/planned/README.md) · [已完成与验证](docs/completed/README.md)

## 启动命令行

Java 从 [MyHelperApplication.main](src/main/java/com/example/myhelper/MyHelperApplication.java) 进入，由它启动非 Web 的 Spring Boot 容器，再读取终端输入。Spring AI 提供模型客户端，编排器需要模型决策时才发起请求。 [启动与任务调用链](docs/modules/interaction/20-交互与语音开发文档.md#应用启动链路)

使用 JDK 25，从项目根目录运行。

```powershell
mvn -o -DskipTests package
java -jar target/desktop-brain-1.0.0.jar
```

离线打包需要本机已有项目依赖及打包插件。本机验证使用了临时 Maven 全局配置，通过 `-gs` 选择默认本地缓存，避免当前镜像设置与已有缓存来源不一致。已有可执行 JAR 时直接运行第二行，也可用 IDE 运行 [MyHelperApplication](src/main/java/com/example/myhelper/MyHelperApplication.java)。

启动后输入一行任务，回车提交，终端按运行 ID 显示结果。输入 `/exit` 等待已提交的结果后退出。实际处理文字需要可用的 OpenAI 兼容模型连接，可通过 `SPRING_AI_OPENAI_BASE_URL`、`SPRING_AI_OPENAI_API_KEY`、`SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL` 覆盖现有配置。

默认输出任务实时进度。启用正式归档后，可在终端输入 `/history <taskId> <rootRunId>` 查看已结束运行的日志。两个 ID 对应进度日志的 `task=` 和 `root=`。 [任务日志](docs/modules/tasklog/任务日志开发文档.md)

默认启用任务记录和启动恢复，需可连接的 Neo4j。只试普通文字任务时，可用 `--myhelper.records.enabled=false` 跳过这部分。默认没有活动业务工具，固化检索默认关闭。设备只读工具需服务配置、任务授权、资源解析及设备映射。 [交互与语音](docs/modules/interaction/20-交互与语音开发文档.md) · [设备接线](docs/modules/tools/25-工具管理与隔离运行设计.md#8-设备状态查询与感知接线)

## 启动桌面调试界面

使用 JDK 25 打包后，从 Windows 桌面会话运行以下命令。

```powershell
java -jar target/desktop-brain-1.0.0.jar --myhelper.ui=desktop
```

在 IDE 运行 `MyHelperApplication` 时，把 `--myhelper.ui=desktop` 放入程序参数。点击右下角雷姆单手拎着的小包打开对话，拖动角色可移动位置。头顶的半透明小气泡随任务出现，悬停展开文字，单击打开任务树和时间线。角色轮廓与气泡椭圆之外的区域可以直接操作桌面。已结束的任务可从对话的最近任务列表查看。右键角色选择「退出 MyHelper」关闭应用。

桌面使用与终端相同的模型和记录配置。只调试界面时可加 `--myhelper.records.enabled=false`，以跳过正式记录和启动恢复，此时仍需配置可用的模型端点。 [Swing 开发文档](docs/modules/interaction/31-Swing桌面调试界面开发文档.md)

雷姆默认在连续空闲 2 分钟后调用模型规划短动作和本地道具。任务受理、执行和实际收尾期间按任务阶段播放固定姿势。调试时可传入 `--myhelper.desktop.behavior.idle-delay=5s`，关闭空闲规划使用 `--myhelper.desktop.behavior.enabled=false`。 [短期行为规划器](docs/modules/interaction/32-短期行为规划器设计.md#6-首版实现) · [基础动作](docs/modules/interaction/31-Swing桌面调试界面开发文档.md#7-雷姆基础动作)

测试和替身已按用户要求移到独立 [checks](checks/README.md) 目录，`src` 只保留业务代码和资源；checks 中的 Harness/检查 main 不能当成应用启动入口。

- [重写架构与进度](docs/重写架构与进度.md)：开始任务必读，区分用户确认、建议、实现和验证。
- [重构项目总文档](docs/00-重构项目总文档.md)：目标架构、完整模块目录、主流程和实施顺序。
- [单次主流程](docs/modules/agent/11-单次模型调用模块.md)：一次模型调用和可选工具执行。
- [循环并发主流程](docs/modules/orchestration/12-并行分支循环编排设计.md)：模型通过编排工具递归提交任务。
- [模型适配器交接](docs/modules/llm/27-模型适配器开发文档.md)：分配、绑定、回收及配置解析由独立模块实现，循环只接入合同。
- [执行链路记录](docs/modules/orchestration/13-执行链路记录开发文档.md)：节点 Map、依赖、参数流和共享读取合同。
- [固化检索与缓存](docs/modules/capability/19-固化检索与缓存开发文档.md)：规划前按用户目标检索，规划后按 LLM 任务再次检索、匹配与缓存。
- [技能固化](docs/modules/skill/16-技能固化开发文档.md)：从已验证子图提取、审核并发布能力。

## 当前源码与验证范围

| 模块 | 实际内容 | 文档 |
| --- | --- | --- |
| [world](src/main/java/com/example/myhelper/world/) | 统一实体与引用、状态和独立观测、可信裁决、设备查询、引用展开和按授权组装规划上下文 | [09](docs/modules/world/09-统一世界模型.md) · [23](docs/modules/world/23-世界模型查询与上下文开发文档.md) |
| [llm](src/main/java/com/example/myhelper/llm/) | LlmTexts 固定文本、ModelAdapter 接口及 ConfiguredModelAdapter 的分支分配、会话绑定和客户端解析 | [24](docs/24-LLM文本与代码可读性规范.md)、[27](docs/modules/llm/27-模型适配器开发文档.md) |
| [agent](src/main/java/com/example/myhelper/agent/) | AgentCaller 单次模型调用及可选工具执行，已有本地检查记录，未真实 LLM 联调 | [11](docs/modules/agent/11-单次模型调用模块.md) |
| [persistence](src/main/java/com/example/myhelper/persistence/) | 显式来源/目标读写、Neo4j/Qdrant 适配、同步补偿及配置装配，未真实数据库联调 | [10](docs/modules/persistence/10-统一双写模块分析.md) |
| [orchestration](src/main/java/com/example/myhelper/orchestration/) | 递归 DAG 调度、分支拓扑、工具执行、内存执行图及外部模型会话生命周期接线；不内置模型分配器 | [12](docs/modules/orchestration/12-并行分支循环编排设计.md)、[13](docs/modules/orchestration/13-执行链路记录开发文档.md)、[14](docs/modules/orchestration/14-工具与能力执行开发文档.md) |
| [capability](src/main/java/com/example/myhelper/capability/) | 根入口和规划后检索、一意图多链展开、按范围和可靠度排序、固定版本缓存。正式入口待接业务 Matcher | [19](docs/modules/capability/19-固化检索与缓存开发文档.md) |
| [skill](src/main/java/com/example/myhelper/skill/) | 候选审核、单链与意图归组发布、归档参数事实、链级可靠度；尚待正式启动装配和完整自动分流 | [16](docs/modules/skill/16-技能固化开发文档.md) |
| [records](src/main/java/com/example/myhelper/records/) | Neo4j 任务与指令记录、事实、固定运行归档和恢复处置；正式启动默认启用 | [28](docs/modules/records/28-执行记录与恢复端口开发文档.md) |

已有本地应用启动、回环模型协议和组件检查。真实模型、数据库和设备联调的范围见[待做索引](docs/planned/README.md)，历次本地验证见[检查记录](checks/README.md)。

## 历史资料

[旧版资料目录](docs/legacy/README.md)汇总了原 01–08、合并稿、早期 Episode 方案和旧构建参考，附原始设计位置及旧源码版本。

### 持久任务与重启恢复

正式入口默认启用 Neo4j 任务记录和启动恢复；数据库不可用会明确报告启动失败。已有任务沿用 taskId，恢复运行新建 rootRunId，暂停和取消的原目标不会自动恢复。配置 myhelper.records.recovery-batch-size 默认 4，myhelper.records.state-directory 缺省为用户目录下 .myhelper/task-runtime；同一安装保持该目录固定。仅在明确不需要持久恢复的演示中使用 --myhelper.records.enabled=false。

运行中的事件和最终归档读取、未知动作核对及单机所有权边界见[任务记录与恢复](docs/modules/records/28-执行记录与恢复端口开发文档.md#13-2026-11-17-正式持久任务与启动恢复已实现)。
