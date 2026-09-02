# 历史迭代：v1 脚本化 Unit

> 状态：已退役，仅用于理解架构演进，不代表当前运行逻辑。

## 当时的执行模型

v1 将一次成功任务的工具调用轨迹同时保存为 Unit 的扁平执行脚本：

```text
用户请求
→ Qdrant 语义命中 Unit
→ PlanMatcher 调用模型判断是否适用并提取变量
→ scriptable=true 时由 PlanExecutor 回放 script
→ 否则把历史计划交给模型参考执行
```

Unit 当时包含：

- `scriptable`
- `script: List<ToolCallLog>`
- `params`
- `CONTAINS` 子 Unit

因此同一能力同时存在两份执行结构：扁平 `script` 和图中的 `CONTAINS` 树。

## 退役原因

1. `script` 与 `CONTAINS` 重复表达步骤，存在不一致风险。
2. 语义命中后仍调用 PlanMatcher，重复任务不能真正零 Token。
3. 是否能够直接执行更取决于当前环境，而不仅是历史成功次数。
4. 状态缺失、过期、执行结果验证无法由 `scriptable` 一个布尔值表达。
5. 参数、步骤、失败归因分散在两套执行器中，维护成本持续增加。

## v2 迁移结果

| v1 | 当前模型 |
|---|---|
| `script` | 删除；步骤只存在于 `CONTAINS` 树 |
| `scriptable` | `directExecutionStatus` |
| `PlanMatcher` | 删除；由语义召回质量和 ContextUnit 条件控制 |
| `PlanExecutor` | 删除；统一由 `UniversalUnitExecutor` 执行 |
| 脚本参数 | `CONTAINS.argumentsBase64` |
| 是否适用 | `requiredContextIds` 指向 REQUIREMENT |
| 是否达成 | `expectedContextIds` 指向 EXPECTATION |
| 环境变化 | ContextUnit `refreshUnitId` 调用观察 Unit 刷新 |

当前权威设计见：[13-递归世界模型与条件直达](../13-递归世界模型与条件直达.md)。
