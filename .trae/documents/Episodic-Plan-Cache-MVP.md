# Episodic-Plan-Cache MVP 实施计划

## Context（为什么做这件事）

当前 `ToolPlanner` 用 `ConcurrentHashMap<String, CacheEntry>` 做工具方案缓存，有三个痛点：

1. **重启即失忆**：进程重启后所有缓存清空，每次都要重新调 AI 规划（~1s + token 消耗）
2. **精确匹配太死板**：key 是 `normalizeKey(userInput)` 去标点空格数字，"打开记事本" 和 "帮我打开记事本" 是两条不同的 key，无法语义复用
3. **失败学习不沉淀**：连续失败 3 次只是清掉内存缓存，下次相同请求又会重新踩坑

借鉴 ExpeL/MUSE 的"经验学习"思想，把每次成功执行的完整轨迹（用户输入 + 选中的工具 + 每个工具的实际调用记录 + AI 回复）作为 **Episode** 持久化到 Qdrant，下次相似请求来时向量检索复用其方案，失败时累计失败计数达阈值自动归档。

**预期效果**：用得越多越聪明——稳定任务秒级命中（向量检索 ~50ms vs AI 规划 ~1000ms），token 消耗降低 70%+，跨重启保留经验。

**MVP 范围**：只做"episode 持久化 + 向量检索复用 + 失败淘汰"。LLM 反思（successLesson/failureLesson）留到第二阶段验证 MVP 可用后再加。

---

## 现有基础设施（复用，不新建）

| 已有类 | 路径 | 复用点 |
|---|---|---|
| `QdrantConfig` | [QdrantConfig.java](file:///e:/projcket/my/myhelper/src/main/java/com/example/desktopbrain/memory/vector/QdrantConfig.java) | WebClient Bean + collection 初始化模式 |
| `EmbeddingService` | [EmbeddingService.java](file:///e:/projcket/my/myhelper/src/main/java/com/example/desktopbrain/memory/vector/EmbeddingService.java) | `List<Float> embed(String text)` (768维 nomic-embed-text) |
| `VectorMemoryService` | [VectorMemoryService.java](file:///e:/projcket/my/myhelper/src/main/java/com/example/desktopbrain/memory/vector/VectorMemoryService.java) | 参考 Qdrant REST API 调用模式（不直接复用，因为要新 collection） |
| `LoggingToolCallback` | [LoggingToolCallback.java](file:///e:/projcket/my/myhelper/src/main/java/com/example/desktopbrain/service/LoggingToolCallback.java) | 已记录每次工具调用输入输出，改造为可收集到 List |
| `ToolPlanner` | [ToolPlanner.java](file:///e:/projcket/my/myhelper/src/main/java/com/example/desktopbrain/service/ToolPlanner.java) | 现有 plan/cachePlan/onCacheHitFailure 逻辑，三层缓存接入点 |
| `DesktopBrainApplication` | [DesktopBrainApplication.java](file:///e:/projcket/my/myhelper/src/main/java/com/example/desktopbrain/DesktopBrainApplication.java#L410-L482) | processAITurn 成功/失败分支是 episode 记录点 |

**Qdrant 配置**（[application.yml](file:///e:/projcket/my/myhelper/src/main/resources/application.yml#L25-L30)）：localhost:6333，已有 `desktop-memory` collection。新增 `episodes` collection 同样 768维 Cosine。

---

## 三层缓存查询逻辑（核心设计）

```
ToolPlanner.plan(userInput, allTools):
  │
  ├─[Layer 1] 内存缓存（ConcurrentHashMap，key=normalizeKey(userInput)）
  │   命中 → 返回 PlanResult(fromCache=true, episodeId=entry.episodeId)
  │   不查 Qdrant，避免重复 embed（200-500ms）
  │
  ├─[Layer 2] Episode 向量检索（Qdrant episodes collection）
  │   embed(userInput) → search(top_k=3, score_threshold=0.65,
  │                              filter: archived=false AND stability>=0.6)
  │   命中 → 回填内存缓存 → 返回 PlanResult(fromCache=true, episodeId=ep.id)
  │
  └─[Layer 3] AI 规划（现有 doPlan 逻辑）
      返回 PlanResult(fromCache=false, episodeId=null)
```

**淘汰策略独立**：
- 内存缓存：连续失败 3 次 → 清除（现有逻辑保留）
- Episode：failureCount+1，stability<0.3 或连续失败 3 次 → archived=true（不被检索，但数据保留）

---

## 关键设计决策（来自 Plan agent 审查）

1. **episodeId 通过 PlanResult 传递**，不通过 userInput 反查。所有回调方法（onCacheHitSuccess/onCacheHitFailure/cachePlan）签名改为接收 `PlanResult` 而非 `String userInput`。

2. **所有 Qdrant 写操作异步**：`recordSuccess`/`recordFailure`/`incrementSuccess` 用 `CompletableFuture.runAsync`，主流程不等待。Qdrant 故障吞异常 + 日志，不传染主流程。

3. **Qdrant point ID 用 UUID 字符串**（不用数字，避免与现有 desktop-memory collection 混淆）。`recordFailure` 用 setPayload 部分更新（不重传 vector 和 toolCalls）。

4. **stability 冗余存储**：Qdrant payload filter 不支持表达式计算，每次更新 successCount/failureCount 时同步写入 stability 字段。Episode record 里 stability 既是字段（Qdrant 用）也有 computedStability() 方法（Java 端校验用）。

5. **不存失败 episode**：episode 库里全是"曾经成功过"的方案。失败时只对已存在 episode 做 failureCount+1。首次请求就失败（episodeId=null）时所有回调 no-op。

6. **改 resetFailureCount 为 incrementSuccess**：成功时 successCount+1，failureCount 不变。stability 自然随环境变化衰退（不会"老 episode 永不淘汰"）。

7. **ToolCallLog.result 和 args 截断到 500 字符**：避免 OCR 全屏文字等长结果撑爆 Qdrant payload。

8. **collector 用 `Collections.synchronizedList(new ArrayList<>())`**：Spring AI toolCallbacks 串行调用，synchronizedList 足够，CopyOnWriteArrayList 偏重。

9. **EpisodesCollectionInitializer 用 @PostConstruct**（不用 @Bean 返回 boolean 的反模式），直接在 EpisodeCacheService 里做。

---

## 实施步骤（8 步，按顺序）

### 步骤 1：新建 `ToolCallLog` record

**文件**：`src/main/java/com/example/desktopbrain/memory/vector/episode/ToolCallLog.java`

```java
package com.example.desktopbrain.memory.vector.episode;

/** 单次工具调用日志（episode 轨迹的最小单元） */
public record ToolCallLog(
        String toolName,
        String args,        // 截断到 500 字符
        String result,      // 截断到 500 字符
        boolean success,
        long durationMs
) {}
```

### 步骤 2：新建 `Episode` record

**文件**：`src/main/java/com/example/desktopbrain/memory/vector/episode/Episode.java`

```java
package com.example.desktopbrain.memory.vector.episode;

import java.util.List;

/** 一次完整的任务执行轨迹（ExpeL/MUSE 经验学习的最小单元） */
public record Episode(
        String id,                      // UUID 字符串，Qdrant point id
        String userInput,               // 用户原话（用于重新 embed 和展示）
        List<String> selectedToolNames, // 选中的工具
        List<String> missingDescriptions,
        List<ToolCallLog> toolCalls,    // 实际工具调用轨迹
        String aiResponse,              // AI 最终回复（截断到 500 字符）
        int successCount,               // 累计成功次数
        int failureCount,               // 累计失败次数
        boolean archived,               // 是否归档（archived=true 不被检索）
        long timestamp,
        double stability                // 冗余字段：successCount/(success+failure)，用于 Qdrant filter
) {
    /** Java 端实时计算稳定度（不依赖存储的 stability 字段） */
    public double computedStability() {
        int total = successCount + failureCount;
        return total == 0 ? 0.0 : (double) successCount / total;
    }
}
```

### 步骤 3：新建 `EpisodeCacheService`

**文件**：`src/main/java/com/example/desktopbrain/memory/vector/episode/EpisodeCacheService.java`

**核心 API**：
```java
@Service
public class EpisodeCacheService {
    // 向量检索相似 episode（带 filter: archived=false AND stability>=0.6）
    Optional<Episode> findSimilarEpisode(String userInput);
    
    // 新建 episode（首次成功时调用，返回 episodeId）
    // 异步执行，不阻塞主流程
    String recordSuccess(String userInput, PlanResult plan, 
                         List<ToolCallLog> toolCalls, String aiResponse);
    
    // 已存在 episode 失败计数+1（达阈值 archive）
    // 异步执行，episodeId 为 null 时 no-op
    void recordFailure(String episodeId, String failureReason);
    
    // 已存在 episode 成功计数+1（不重置 failureCount，让 stability 自然衰退）
    // 异步执行，episodeId 为 null 时 no-op
    void incrementSuccess(String episodeId);
}
```

**实现要点**：
- `@Value("${qdrant.episodes-collection:episodes}")` 注入 collection 名
- `@PostConstruct` 初始化 collection（仿 [QdrantConfig.qdrantInitCollection](file:///e:/projcket/my/myhelper/src/main/java/com/example/desktopbrain/memory/vector/QdrantConfig.java#L44-L76) 但用 PostConstruct）
- 所有方法 try-catch 吞异常 + `System.err.println` 日志，绝不让 Qdrant 故障传染主流程
- `recordSuccess`：用 `UUID.randomUUID().toString()` 作 point ID，upsert 整个 point（含 vector + payload）
- `recordFailure`/`incrementSuccess`：用 Qdrant `POST /collections/episodes/points/payload` setPayload 部分更新（不重传 vector 和 toolCalls）
- `findSimilarEpisode`：search body 含 `score_threshold: 0.65`, `filter: {must: [{archived: false}, {stability: {gte: 0.6}}]}`
- payload 反序列化用 Jackson `objectMapper.convertValue(payload, Episode.class)`
- 异步用 `CompletableFuture.runAsync(...)` （默认 ForkJoinPool 即可）

### 步骤 4：改造 `LoggingToolCallback`

**文件**：[LoggingToolCallback.java](file:///e:/projcket/my/myhelper/src/main/java/com/example/desktopbrain/service/LoggingToolCallback.java)

**改动**：
- 加字段 `private final List<ToolCallLog> collector;`
- 构造器：`LoggingToolCallback(ToolCallback delegate, List<ToolCallLog> collector)`
- `call()` 成功分支末尾：`collector.add(new ToolCallLog(name, preview(toolInput, 500), preview(result, 500), true, elapsed));`
- `call()` 失败分支末尾（throw 前）：`collector.add(new ToolCallLog(name, preview(toolInput, 500), e.getMessage(), false, elapsed));`
- 保留现有 println 行为不变

### 步骤 5：改造 `ToolPlanner`

**文件**：[ToolPlanner.java](file:///e:/projcket/my/myhelper/src/main/java/com/example/desktopbrain/service/ToolPlanner.java)

**改动**：
1. `PlanResult` record 加字段：`record PlanResult(List<String> selectedToolNames, List<String> missingDescriptions, boolean fromCache, String episodeId) {}`
2. 所有现有 `new PlanResult(...)` 调用补 `episodeId` 参数（5 处，大部分传 null）
3. `CacheEntry` 加字段 `String episodeId`
4. 构造器注入 `EpisodeCacheService`
5. `plan()` 方法改为三层查询（见上文"三层缓存查询逻辑"），episode 命中时回填内存缓存
6. `onCacheHitFailure` / `onCacheHitSuccess` / `cachePlan` 方法签名改为接收 `PlanResult` 而非 `String userInput`：
   - `onCacheHitFailure(PlanResult plan)`：内存缓存 failureCount+1（现有逻辑）+ 异步 `episodeCacheService.recordFailure(plan.episodeId(), ...)`
   - `onCacheHitSuccess(PlanResult plan)`：内存缓存 failureCount 重置（现有逻辑）+ 异步 `episodeCacheService.incrementSuccess(plan.episodeId())`
   - `cachePlan(String userInput, PlanResult plan)`：内存缓存写入（现有逻辑）+ 异步 `episodeCacheService.recordSuccess(...)`（episodeId 为 null 时 recordSuccess 创建新 episode）

### 步骤 6：改造 `DesktopBrainApplication`

**文件**：[DesktopBrainApplication.java](file:///e:/projcket/my/myhelper/src/main/java/com/example/desktopbrain/DesktopBrainApplication.java#L385-L482)

**改动**：
1. `executeWithTools` 方法签名加参数 `List<ToolCallLog> collector`：
   ```java
   private String executeWithTools(ChatClient chatClient, String input,
                                   ToolCallback[] allTools, ToolPlanner.PlanResult plan,
                                   List<ToolCallLog> collector) {
       ToolCallback[] loggedTools = Arrays.stream(allTools)
               .filter(tc -> plan.selectedToolNames().contains(tc.getToolDefinition().name()))
               .map(tc -> new LoggingToolCallback(tc, collector))  // 改为 lambda
               .toArray(ToolCallback[]::new);
       // ... 其余不变
   }
   ```
2. `processAITurn` 开头创建 collector：
   ```java
   List<ToolCallLog> toolCallLogs = Collections.synchronizedList(new ArrayList<>());
   ```
3. 调用 `executeWithTools` 时传入 `toolCallLogs`
4. 成功分支（[L440-L444](file:///e:/projcket/my/myhelper/src/main/java/com/example/desktopbrain/DesktopBrainApplication.java#L440-L444)）：
   ```java
   if (plan.fromCache()) {
       toolPlanner.onCacheHitSuccess(plan);  // 改为传 plan
   } else {
       toolPlanner.cachePlan(userInput, plan, toolCallLogs, response);  // 多传 toolCallLogs + response
   }
   ```
5. `CacheFailureException` 分支（[L447-L471](file:///e:/projcket/my/myhelper/src/main/java/com/example/desktopbrain/DesktopBrainApplication.java#L447-L471)）：
   ```java
   boolean shouldReplace = toolPlanner.onCacheHitFailure(plan);  // 改为传 plan
   // replan
   toolCallLogs.clear();  // 清空 collector 重新收集
   String response = executeWithTools(chatClient, effectiveInput, tools, newPlan, toolCallLogs);
   if (shouldReplace) {
       toolPlanner.cachePlan(userInput, newPlan, toolCallLogs, response);
   }
   ```
6. import `ToolCallLog` 和 `Collections`

### 步骤 7：配置 `application.yml`

**文件**：[application.yml](file:///e:/projcket/my/myhelper/src/main/resources/application.yml#L25-L30)

在现有 `qdrant` 块下加：
```yaml
qdrant:
  host: localhost
  port: 6333
  collection: desktop-memory
  episodes-collection: episodes      # 新增
  vector-size: 768
  episode:                            # 新增
    similarity-threshold: 0.65
    stability-threshold: 0.6
    failure-threshold: 3
    top-k: 3
```

### 步骤 8：（第二阶段，本次不做）LLM 反思

MVP 验证可用后再加 `successLesson` / `failureLesson` 两个异步 LLM 调用 + 两个字段。

---

## 验证方法（端到端测试）

### 启动验证
1. 启动 Qdrant Docker（`docker compose up -d qdrant`）
2. 启动应用，看日志输出 `📦 Qdrant 集合 'episodes' 已创建`
3. `curl http://localhost:6333/collections/episodes` 确认 collection 存在，vectors.size=768

### 功能验证（文字对话模式）
1. 输入 "打开记事本" → AI 执行成功 → 日志显示 `💾 已缓存成功方案`
2. `curl http://localhost:6333/collections/episodes/points/scroll -d '{"limit": 5, "with_payload": true}'` 确认 episode 写入，payload 含 toolCalls 数组
3. 重复输入 "打开记事本" → 日志显示 `💾 命中缓存`（内存缓存）
4. 重启应用
5. 再输入 "打开记事本" → 日志显示 `💾 命中 Episode 缓存`（Qdrant 向量检索）
6. 输入 "帮我打开记事本"（语义相似但 normalizeKey 不同）→ 应该也能命中 episode 缓存（向量检索）

### 失败学习验证
1. 输入一个会失败的任务（如 "打开不存在的应用 xyz"）→ AI 失败 → 日志显示 `⚠️ 缓存方案失败`
2. 重复 3 次 → 第 3 次日志显示 `🗑️ 缓存连续失败 3 次，已清除旧方案`
3. `curl` 查看 episodes collection，对应 episode 的 `archived: true`, `failureCount: 3`

### 降级验证
1. 停掉 Qdrant（`docker stop qdrant`）
2. 输入任意请求 → 应用应正常工作（降级到内存缓存或 AI 规划），日志显示 `❌ Qdrant 搜索失败: ...` 但不抛异常
3. 重启 Qdrant → episode 系统自动恢复

---

## 潜在风险

1. **EmbeddingService 同步阻塞**：每次 `findSimilarEpisode` 多 200-500ms。MVP 接受，后续可加 `ConcurrentHashMap<normalizeKey, vector>` embed 缓存。
2. **episode 数量无限增长**：MVP 不做 TTL。长期运行后 Qdrant 占用空间增加。后续可加定时清理 archived 超过 N 天的 episode。
3. **Spring AI 2.0.0-M3 + Spring Boot 4.1.0-M2**：版本前沿，工具调用相关 bug 先怀疑这里。
4. **processAITurnAsync 与文字主线程潜在并发**：现有 bug，episode 系统不引入也不修复。如果出现 collector 串台要检查这里。
