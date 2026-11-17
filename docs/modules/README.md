# 模块、源码与任务窗口

更新于 2026-11-17。目录名优先对应当前代码包。第 13、14 篇主要对应 `orchestration` 包。逐包的实际逻辑、检查与已知问题见[全量实现与代码复核](../completed/全量实现与代码复核.md)。

| 模块 | 维护窗口 | 当前源码或接入点 | 文档 |
| --- | --- | --- | --- |
| `agent` 单次主流程 | 单次主流程 | [AgentCaller](../../src/main/java/com/example/myhelper/agent/AgentCaller.java) | [11 单次模型调用](agent/11-单次模型调用模块.md) |
| `orchestration` 并发循环 | 循环可并发调用主流程 | [orchestration 包](../../src/main/java/com/example/myhelper/orchestration/) | [12 并行分支循环](orchestration/12-并行分支循环编排设计.md) |
| 执行链路图 | 循环提供接入，存储实现可替换 | [ExecutionTracePort](../../src/main/java/com/example/myhelper/records/ExecutionTracePort.java) · [ExecutionTraceStore](../../src/main/java/com/example/myhelper/orchestration/ExecutionTraceStore.java) | [13 执行链路记录](orchestration/13-执行链路记录开发文档.md) |
| `records` 任务记录与恢复事项 | 循环窗口交付独立记录模块：Neo4j 聚合仓储、指令、固定归档；重复派发策略和外部审核结论独立 | [StoredTaskRecords](../../src/main/java/com/example/myhelper/records/StoredTaskRecords.java) · [ToolReplayPolicy](../../src/main/java/com/example/myhelper/records/ToolReplayPolicy.java) · [TaskRecordPort](../../src/main/java/com/example/myhelper/records/TaskRecordPort.java) | [28 当前实现与职责整改](records/28-执行记录与恢复端口开发文档.md#15-2026-11-17-恢复策略边界与可读性整改) |
| `tasklog` 任务日志 | 日志模块 | [tasklog 包](../../src/main/java/com/example/myhelper/tasklog/) | [任务日志](tasklog/任务日志开发文档.md) |
| `application` 恢复入口组件 | 已接正式应用：进程所有权、同宿主旧运行核对、后台分批恢复 | [StartupTaskRecovery](../../src/main/java/com/example/myhelper/application/StartupTaskRecovery.java) · [TaskRecoveryService](../../src/main/java/com/example/myhelper/application/TaskRecoveryService.java) | [21 装配与恢复](deployment/21-装配部署与集成验收.md) · [28 当前实现](records/28-执行记录与恢复端口开发文档.md#13-2026-11-17-正式持久任务与启动恢复已实现) |
| 工具与能力执行 | 循环可并发调用主流程提供接入记录 | [SpringToolExecutor](../../src/main/java/com/example/myhelper/orchestration/SpringToolExecutor.java) · [TaskPlan](../../src/main/java/com/example/myhelper/orchestration/TaskPlan.java) | [14 工具与能力执行](orchestration/14-工具与能力执行开发文档.md) |
| `llm` 模型适配 | 模型适配器 | [llm 包](../../src/main/java/com/example/myhelper/llm/) | [18 模型接入与路由](llm/18-模型接入与路由开发文档.md) · [27 模型适配器](llm/27-模型适配器开发文档.md) |
| `world` 世界模型 | 世界模型 | [world/model](../../src/main/java/com/example/myhelper/world/model/) | [09 统一世界模型](world/09-统一世界模型.md) · [23 世界查询与上下文](world/23-世界模型查询与上下文开发文档.md) |
| `persistence` 数据库读写 | 数据库双写；统一承担全部数据库查询和保存 | [DualWriteStore](../../src/main/java/com/example/myhelper/persistence/DualWriteStore.java) · [StorageQuery](../../src/main/java/com/example/myhelper/persistence/StorageQuery.java) | [10 双库读写、查询与补偿](persistence/10-统一双写模块分析.md) |
| `capability` 固化检索 | 循环可并发调用主流程提供接入记录 | [capability 包](../../src/main/java/com/example/myhelper/capability/) | [19 固化检索与缓存](capability/19-固化检索与缓存开发文档.md) |
| `gate` 门禁与验收 | 固化写入校验已实现，通用业务验收待接续 | [SkillWriteGate](../../src/main/java/com/example/myhelper/gate/SkillWriteGate.java) · [编排器 Checks 接口](../../src/main/java/com/example/myhelper/orchestration/AgentOrchestrator.java) | [15 门禁与验收](gate/15-门禁与验收开发文档.md) |
| `skill` 技能固化 | 固化技能窗口，数据库窗口补仓储与接线检查 | [skill 包](../../src/main/java/com/example/myhelper/skill/) 已有生产候选/审核/双库发布、参数仓储、模型范围查询及装配；实际执行自动记账和归档消费待完成 | [16 技能固化](skill/16-技能固化开发文档.md) |
| `exploration` 自主探索 | 设计待接续 | 开发设计 | [17 自主探索](exploration/17-自主探索开发文档.md) |
| `tools` 工具管理 | 工具生成管理 | [tools 包](../../src/main/java/com/example/myhelper/tools/)已有来源目录和范围先验配置。动态发布、隔离、公共变量待实现 | [25 工具管理与隔离运行](tools/25-工具管理与隔离运行设计.md) |
| `interaction` 交互与语音 | 应用入口 | [MyHelperApplication.main](../../src/main/java/com/example/myhelper/MyHelperApplication.java) 启动应用，[interaction 包](../../src/main/java/com/example/myhelper/interaction/) 接收文字、提交任务并显示结果 | [20 应用启动链路](interaction/20-交互与语音开发文档.md#应用启动链路) · [29 桌面交互设计](../planned/29-桌面助手调试界面设计.md) · [31 Swing 开发接线](interaction/31-Swing桌面调试界面开发文档.md) |
| `interaction.desktop.behavior` 桌面短期行为 | 32 短期行为规划器 | [行为模块](../../src/main/java/com/example/myhelper/interaction/desktop/behavior/) · [Swing 执行](../../src/main/java/com/example/myhelper/interaction/desktop/DesktopBehaviorView.java) · [运行活动仲裁](../../src/main/java/com/example/myhelper/application/TaskActivity.java) | [32 实现与验证](interaction/32-短期行为规划器设计.md#6-首版实现) |
| `deployment` 应用装配 | 总 | [MyHelperApplication](../../src/main/java/com/example/myhelper/MyHelperApplication.java) 已提供命令行应用启动；生产业务装配与外部服务联调按模块接续 | [21 装配部署与集成验收](deployment/21-装配部署与集成验收.md) |
| `extensions` 旧扩展 | 设计待接续 | 按场景恢复 | [22 扩展模块边界](extensions/22-扩展模块开发边界.md) |

跨模块的验收、任务进度及恢复设计在 [26 任务验收、进度与断点恢复](../planned/26-任务验收进度与断点恢复设计.md)。总窗口的入口为[项目总文档](../00-重构项目总文档.md)与[接续记录](../重写架构与进度.md)。

26 第 10 节说明中断与指令重规划目标，28 记录已实现的端口、收尾及应用侧组件。正式单机持久恢复见 28 第 13 节；真实数据库与业务影响核对继续联调。输入及启动职责见 20、21。模型适配器已接外部画像排序，缺画像时沿用配置顺序，生产画像仍待装配。
