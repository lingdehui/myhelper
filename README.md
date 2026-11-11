# MyHelper

> 共同开发规则（2026-11-11 用户确认）：固定 LLM 文本集中到 LlmTexts；不用 Lambda，以可读性选择循环、方法和 switch，允许 `case ... ->`。所有任务继续前阅读[24-LLM文本与代码可读性规范](docs/24-LLM文本与代码可读性规范.md)。

更新：2026-11-11。这里是旧 MyHelper 的模块化重构项目。当前重点是递归并发编排、统一世界模型，以及在模型规划前后复用已固化的整条能力或子节点。

## 阅读入口

**当前启动状态：还不能直接启动完整 Agent 应用。** 生产源码没有 `main` / `@SpringBootApplication`，也没有创建并调用编排器的业务入口。已实现的任务组件入口是 [AgentOrchestrator.submit](src/main/java/com/example/myhelper/orchestration/AgentOrchestrator.java)，单次模型入口是 [AgentCaller.call](src/main/java/com/example/myhelper/agent/AgentCaller.java)。正式装配和输入入口尚未实现；详见[主流程启动说明](docs/12-并行分支循环编排设计.md)。

测试和替身已按用户要求移到独立 [checks](checks/README.md) 目录，`src` 只保留业务代码和资源；checks 中的 Harness/检查 main 不能当成应用启动入口。

- [重写架构与进度](docs/重写架构与进度.md)：开始任务必读，区分用户确认、建议、实现和验证。
- [重构项目总文档](docs/00-重构项目总文档.md)：目标架构、完整模块目录、主流程和实施顺序。
- [循环并发主流程](docs/12-并行分支循环编排设计.md)：模型通过编排工具递归提交任务。
- [执行链路记录](docs/13-执行链路记录开发文档.md)：节点 Map、依赖、参数流和共享读取合同。
- [固化检索与缓存](docs/19-固化检索与缓存开发文档.md)：规划前按用户目标检索，规划后按 LLM 任务再次检索、匹配与缓存。
- [技能固化](docs/16-技能固化开发文档.md)：从已验证子图提取、审核并发布能力。

## 当前源码与验证范围

| 模块 | 实际内容 | 文档 |
| --- | --- | --- |
| [world/model](src/main/java/com/example/myhelper/world/model/) | WorldEntity、四个独立类型枚举、引用/向量、ChainPosition 及执行统计/归因，共十二类 | [09](docs/09-统一世界模型.md) |
| [llm](src/main/java/com/example/myhelper/llm/) | 固定模型提示、工具说明/Schema 及执行反馈集中维护；模型路由尚未实现 | [24](docs/24-LLM文本与代码可读性规范.md) |
| [agent](src/main/java/com/example/myhelper/agent/) | AgentCaller 单次模型调用及可选工具执行，已有本地检查记录，未真实 LLM 联调 | [11](docs/11-单次模型调用模块.md) |
| [persistence](src/main/java/com/example/myhelper/persistence/) | 显式来源/目标读写、Neo4j/Qdrant 适配、同步补偿及配置装配，未真实数据库联调 | [10](docs/10-统一双写模块分析.md) |
| [orchestration](src/main/java/com/example/myhelper/orchestration/) | 循环接入单次调用、模型提交计划、递归 DAG 并发、工具执行与内存执行图 | [12](docs/12-并行分支循环编排设计.md)、[13](docs/13-执行链路记录开发文档.md)、[14](docs/14-工具与能力执行开发文档.md) |
| [capability](src/main/java/com/example/myhelper/capability/) | 根入口一次检索、每次 PLAN 后查询、整条/子节点匹配端口、有界内容缓存与 Qdrant HTTP 适配 | [19](docs/19-固化检索与缓存开发文档.md) |

核心组件已可组合运行，本地替身模型、ToolCallback 及本地 HTTP 测试覆盖主链路；真实 LLM/数据库尚未联调。生产合同匹配与门禁策略仍需注入，固化生成/审核/发布、上下文服务、执行图持久化和完整应用装配尚未实现。旧 target、模型资源和依赖不代表完整应用已可运行；验证详情见接续及各模块文档。

## 历史资料

原始设计：`C:/Users/l4259/Desktop/myhelper设计文档.txt`。旧文档保留作重构背景，不作为当前实现清单。

| 文档 | 历史主题 |
| --- | --- |
| [01](docs/01-系统总架构.md) | 系统总架构 |
| [02](docs/02-代码实现清单.md) | 代码实现清单 |
| [03](docs/03-核心执行流程.md) | 核心执行流程 |
| [04](docs/04-对话与语音系统.md) | 对话与语音 |
| [05](docs/05-记忆与向量系统.md) | 记忆与向量 |
| [06](docs/06-工具与技能体系.md) | 工具与技能 |
| [07](docs/07-数据库与部署.md) | 数据库与部署 |
| [08](docs/08-工具自生成与动态加载.md) | 工具生成 |
| [my.md](my.md) | 历史合并稿 |
