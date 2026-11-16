# MyHelper

> 共同开发规则（2026-11-11 用户确认）：固定 LLM 文本集中到 LlmTexts；不用 Lambda，以可读性选择循环、方法和 switch，允许 `case ... ->`；所有测试与检查资料统一放入 `checks`。所有任务继续前阅读[24-LLM文本与代码可读性规范](docs/24-LLM文本与代码可读性规范.md)。

更新：2026-11-12。这里是旧 MyHelper 的模块化重构项目。当前重点是递归并发编排、统一世界模型，以及在模型规划前后复用已固化的整条能力或子节点。

## 阅读入口

**[打开文档总目录](docs/README.md)** · [任务窗口、模块与源码](docs/modules/README.md) · [待做与设计](docs/planned/README.md) · [已完成与验证](docs/completed/README.md)

**当前启动状态：还不能直接启动完整 Agent 应用。** 生产源码没有 `main` / `@SpringBootApplication`，也没有创建并调用编排器的业务入口。已实现的任务组件入口是 [AgentOrchestrator.submit](src/main/java/com/example/myhelper/orchestration/AgentOrchestrator.java)，单次模型入口是 [AgentCaller.call](src/main/java/com/example/myhelper/agent/AgentCaller.java)。正式装配和输入入口尚未实现；详见[主流程启动说明](docs/modules/orchestration/12-并行分支循环编排设计.md)。

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
| [world/model](src/main/java/com/example/myhelper/world/model/) | WorldEntity、四个独立类型枚举、引用/向量、ChainPosition 及执行统计/归因，共十二类 | [09](docs/modules/world/09-统一世界模型.md) |
| [llm](src/main/java/com/example/myhelper/llm/) | LlmTexts 固定文本、ModelAdapter 接口及 ConfiguredModelAdapter 的分支分配、会话绑定和客户端解析 | [24](docs/24-LLM文本与代码可读性规范.md)、[27](docs/modules/llm/27-模型适配器开发文档.md) |
| [agent](src/main/java/com/example/myhelper/agent/) | AgentCaller 单次模型调用及可选工具执行，已有本地检查记录，未真实 LLM 联调 | [11](docs/modules/agent/11-单次模型调用模块.md) |
| [persistence](src/main/java/com/example/myhelper/persistence/) | 显式来源/目标读写、Neo4j/Qdrant 适配、同步补偿及配置装配，未真实数据库联调 | [10](docs/modules/persistence/10-统一双写模块分析.md) |
| [orchestration](src/main/java/com/example/myhelper/orchestration/) | 递归 DAG 调度、分支拓扑、工具执行、内存执行图及外部模型会话生命周期接线；不内置模型分配器 | [12](docs/modules/orchestration/12-并行分支循环编排设计.md)、[13](docs/modules/orchestration/13-执行链路记录开发文档.md)、[14](docs/modules/orchestration/14-工具与能力执行开发文档.md) |
| [capability](src/main/java/com/example/myhelper/capability/) | 根入口一次检索、每次 PLAN 后查询、整条/子节点匹配端口、有界内容缓存与 Qdrant HTTP 适配 | [19](docs/modules/capability/19-固化检索与缓存开发文档.md) |

核心组件可由装配方组合，真实 LLM 和数据库尚未联调。编排器九参数构造显式接收 ModelAdapter，生产实现 ConfiguredModelAdapter 已有会话检查。八参数构造可运行纯工具路径，模型路径会报告未装配错误。应用装配、供应商配置绑定、合同匹配、实际门禁策略、固化生成和发布、上下文服务及执行图持久化仍待完成。验证结果以接续记录为准。

## 历史资料

[旧版资料目录](docs/legacy/README.md)汇总了原 01–08、合并稿、早期 Episode 方案和旧构建参考，附原始设计位置及旧源码版本。
