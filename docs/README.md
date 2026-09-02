# MyHelper 文档中心

> 当前基线：2026-09-02。代码是最终事实；本文定义文档边界和推荐阅读顺序。

## 当前架构一句话

MyHelper 是一个本地优先的长期自主助手：`WorldObject + ContextUnit` 表达统一世界模型，
`Unit` 表达可递归组合的能力图，Qdrant 负责语义召回，Neo4j 保存结构真相；新任务由模型探索，
成功经验沉淀为 Unit，后续在可信世界状态满足时确定性执行。

## 推荐阅读顺序

1. [系统总架构](01-系统总架构.md)：系统边界、模块和完整闭环。
2. [核心执行流程](03-核心执行流程.md)：一轮请求从语音到执行、验证和沉淀。
3. [递归世界模型与条件直达](13-递归世界模型与条件直达.md)：世界状态、快照、条件和刷新。
4. [记忆与向量系统](05-记忆与向量系统.md)：Unit 图、语义索引、图归纳和数据流。
5. [工具与技能体系](06-工具与技能体系.md)：工具发现、规划、MCP和技能配置。
6. [对话与语音系统](04-对话与语音系统.md)：VAD、ASR、声纹、打断和TTS。
7. [数据库与部署](07-数据库与部署.md)：Neo4j、Qdrant、模型和启动方式。
8. [代码实现清单](02-代码实现清单.md)：按包定位源码。

## 专题文档

| 文档 | 内容 |
|---|---|
| [工具自生成与动态加载](08-工具自生成与动态加载.md) | 缺失工具生成、编译和加载 |
| [小说创作质量门禁](09-小说创作质量门禁.md) | 小说子系统质量校验 |
| [探索知识与记忆维护](10-探索知识与记忆维护.md) | 自主探索、知识沉淀和清理 |
| [记忆价值评分系统](11-记忆价值评分系统.md) | Unit质量、保留和清理 |
| [元自我优化](12-元自我优化.md) | 配置实验、指标和回滚 |
| [项目目录约定](00-项目目录约定.md) | 文件、模型、数据和脚本边界 |

## 三种归纳不要混淆

| 机制 | 触发 | 产物 | 用途 |
|---|---|---|---|
| 在线Unit折叠 | 每次成功沉淀 | 复用已有PLAN_STEP的CONTAINS边 | 新经验不重复已有子图 |
| 离线Unit图归纳 | 每天03:15 | 公共PLAN_STEP和重写后的CONTAINS | 整理旧图、参数提升、形成层级 |
| 经验规则归纳 | 每天03:00 | RuleNode | 给模型规划注入跨案例教训 |

## 当前定时任务

| 时间/间隔 | 服务 | 作用 |
|---|---|---|
| 每60秒巡检 | `AutonomousExplorationService` | 空闲满足条件时探索 |
| 每小时 | `MetaOptimizationService` | 受控配置实验与回滚 |
| 每5分钟 | `UnitStore.compensateMissingUnits` | 补偿缺失Qdrant索引 |
| 03:00 | `RuleInductionService` | 归纳通用规划规则 |
| 03:15 | `UnitGraphCompactionService` | 提取公共Unit路径并重写旧图 |
| 03:30 | `ExperienceQualityService` | 刷新Unit经验质量 |
| 04:00 | `MemoryMaintenanceService` | 清理低价值Unit |
| 04:30 | `ContextUnitMaintenanceService` | 维护快照、推断和历史保留 |

后台自主总开关为 `myhelper.autonomous.enabled`。各任务仍有各自配置条件；表中为默认值。

## 文档状态边界

- `docs/00`～`docs/13`：当前实现文档。
- `docs/history/`：已退役设计，只用于解释演进，不得作为当前实现依据。
- `docs/reference/`：研究参考或早期设想，默认不代表已经实现。
- `docs/audits/`：阶段性审计记录，可能已经被后续代码修复。

## 核心不变量

- Unit执行结构只有Neo4j中的带参数 `CONTAINS` 图，没有第二份扁平脚本。
- 存储结构允许多个父Unit共享子Unit，因此整体是DAG/图；单次执行按当前入口递归展开为有序调用树。
- Qdrant只做召回索引；命中后必须回Neo4j读取结构。
- 历史观测与标准状态都使用ContextUnit；OBSERVATION通过`stateId`指向STATE。
- Unit的REQUIREMENT/EXPECTATION最终匹配STATE；状态不可信时调用`refreshUnitId`指向的Unit。
- 父子参数保存在CONTAINS关系；上下游数据使用`$step.var`和`outputSignature`传递。
- 结构归纳尽量确定性完成；只有语义命名、规则总结等模糊环节交给模型。
