# 探索模式上下文膨胀治理方案

> **阶段性历史审计。** 其中部分问题已经实现修复；当前探索实现以
> [探索知识与记忆维护](../10-探索知识与记忆维护.md)和源码为准。

## 1. 目标

将探索模式每轮调用的上下文从**无限增长（1M+ token）**控制到**可预测、稳定（< 50k token）**，同时保留探索所需的记忆、失败经验、能力清单。

---

## 2. 总体思路

**把“对话历史”和“探索状态”分离。**

- 对话历史（messages）：每轮只传当前轮必要信息，不跨轮累积
- 探索状态：用结构化摘要 + 内存环形缓冲 + 检索系统维护

---

## 3. 实施步骤（按优先级）

### 第一步：止血（Token 守卫 + 步数上限）

加两个硬限制，防止再撞 1M 上限。

```java
public class ExplorationGuard {
    private static final int MAX_TOKENS = 800_000;
    private static final int MAX_STEPS = 10;

    public boolean canContinue(List<Message> messages) {
        return estimateTokens(messages) < MAX_TOKENS;
    }

    private int estimateTokens(List<Message> messages) {
        int chars = messages.stream()
            .mapToInt(m -> m.getContent() != null ? m.getContent().length() : 0)
            .sum();
        return chars / 3;
    }
}
```

在探索循环里每步执行前检查，超限或超步数立刻终止本次会话。

---

### 第二步：核心——上下文压缩器

每轮探索完成后，压缩成 1 行摘要，只保留最近 3-5 条。

```java
public record ExplorationSummary(
    String goal,
    String tools,
    String result,
    String lesson
) {
    public String toText() {
        return String.format(
            "[%s] | 工具:%s | 结果:%s | 教训:%s",
            goal, tools, result, lesson
        );
    }
}
```

维护一个环形缓冲：

```java
private final Deque<ExplorationSummary> summaries = new ArrayDeque<>();

public void addSummary(ExplorationSummary summary) {
    summaries.addFirst(summary);
    if (summaries.size() > 5) summaries.removeLast();
}

public List<ExplorationSummary> getRecentSummaries(int n) {
    return summaries.stream().limit(n).toList();
}
```

每次 `buildContext()` 只注入最近 3 条摘要。

---

### 第三步：瘦身 buildContext

| 原内容 | 改为 |
|--------|------|
| 能力清单全文 | 只注入**缺失项**，每项一句话 |
| 工具分类概览 | **不注入**，需要时用 `searchTool` |
| 失败模式 | 只注入与当前 goal 相关的 Top 2 |
| 近期已学主题 | 只注入最近 3 条摘要 |

```java
private String buildContext(String currentGoal) {
    String learned = getRecentSummaries(3).stream()
        .map(ExplorationSummary::toText)
        .collect(Collectors.joining("\n"));
    
    String failures = getRelevantFailures(currentGoal, 2).stream()
        .map(f -> "⚠️ " + f.description())
        .collect(Collectors.joining("\n"));
    
    String missing = getMissingCapabilities().stream()
        .map(c -> "候选: " + c.name() + " - " + c.hint())
        .collect(Collectors.joining("\n"));
    
    return String.format("""
        [最近已学]
        %s
        
        [相关失败提示]
        %s
        
        [缺失基础能力]
        %s
        """, learned, failures, missing);
}
```

---

### 第四步：改造探索循环

把跨轮 messages 共享改为**每轮独立调用**，状态只通过摘要传递。

```java
public void doExplore() {
    if (!exploreLock.tryLock()) return;
    try {
        ExplorationGuard guard = new ExplorationGuard();
        
        for (int step = 0; step < guard.getMaxSteps(); step++) {
            // 1. 构建精简上下文
            String context = buildContext(currentGoal);
            
            // 2. 独立调用，不维护跨轮 messages
            String decision = modelRouter.chat(Mode.EXPLORATION)
                .prompt()
                .user(context)
                .call()
                .content();
            
            // 3. 执行决策...
            
            // 4. 执行完后压缩本轮，加入摘要缓冲
            ExplorationSummary summary = compressor.compress(round);
            addSummary(summary);
            
            // 5. 检查 token 预算
            if (!guard.canContinue(messages)) break;
        }
    } finally {
        exploreLock.unlock();
    }
}
```

---

## 4. 单轮上下文大小估算（改后）

| 内容 | 大小 |
|------|------|
| 系统提示 | ~2k token |
| 最近 3 条摘要 | ~500 token |
| 失败提示 Top 2 | ~300 token |
| 缺失能力 | ~500 token |
| 工具 schema（选中） | ~3k token |
| **合计** | **< 10k token** |

---

## 5. 实施顺序

| 步骤 | 工作量 | 效果 |
|------|--------|------|
| 加 Token 守卫 + 步数上限 | 0.5 天 | 立即止血，不再撞 1M |
| 摘要压缩器 + 环形缓冲 | 0.5 天 | 核心，上下文不再累积 |
| buildContext 瘦身 | 0.5 天 | 单轮体积大幅下降 |
| 改造探索循环 | 0.5 天 | 彻底隔离跨轮 messages |
| **总计** | **2 天** | 上下文稳定可控 |

---

## 6. 关键决策记录

| 决策 | 内容 |
|------|------|
| 状态存储方式 | 摘要 + 内存环形缓冲，不靠 messages 累积 |
| 摘要保留数量 | 最近 5 条，注入 3 条 |
| 能力清单注入 | 只注入缺失项 |
| 工具分类 | 不注入，按需 searchTool |
| 失败模式 | 只注入与当前 goal 相关的 Top 2 |
| Token 上限 | 800k（留余量） |
| 最大步数 | 10 步 |

这个方案可以直接落地，需要我给出某一步的完整代码可以继续问。
