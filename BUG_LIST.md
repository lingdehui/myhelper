# Bug 列表（自主探索学习系统）

> 来源：2026-08-15 一次自主学习会话日志（system.log）
> 排除：端口 8082 冲突（用户操作失误，不修）

优先级定义：P0 = 阻断核心流程（学不到东西/反复空转）；P1 = 数据与正确性；P2 = 健壮性与工程质量。

---

## P0

### B1. searchTool 搜索失效（根因：embedding 未连接 + 工具描述是英文）

- **现象**：AI 用中文关键词「OCR识别文字/文字识别/屏幕OCR/识别窗口文字…」搜索全部「无匹配」，英文兜底 `OCR text recognition` 也搜不到；但精确传英文工具名 `ocrWindow` / `ocrRegion` 却能搜到。
- **证据**：`system.log` 13:44:17 ~ 13:48:14 连续 40+ 次 `searchTool` 无匹配；`tool-registry-windows-amd64` 集合 `count=0`（实测）。
- **根因（已实测验证，两层）**：
  1. **主因：embedding 服务未连接 → tool-registry 向量集合全空。** `EmbeddingService` 默认连 `http://localhost:11434`，但 `application.yml` 只配了 `embedding.model`、未配 `embedding.ollama.base-url`，实际隧道是 `11435`（ModelRouter 用的也是 11435）。端口不一致 + 隧道未建 → `embed()` 抛异常被 `catch` 静默吞掉 → 工具向量永远写不进 Qdrant（`system.log` 的 `protectMemory 写入 Qdrant 失败: 400 Bad Request` 即症状）→ `tool-registry` count=0 → 向量搜索永远空。
  2. **次因：工具描述是英文。** 分类树底层 `tools` 字段是纯英文工具名（`ocrRegion/ocrWindow/ocrMonitor…`），工具 `description` 也是英文，关键词兜底 `contains(中文词)` 对英文描述失效，中文搜索彻底无门。
- **修复方向**：
  1. 修正 embedding 的 Ollama 地址（11434→11435，与 ModelRouter 对齐），并确保隧道连通、`nomic-embed-text` 已拉取；
  2. `embed()` 失败时明确告警/抛错，不要静默吞掉导致「看似同步成功实则 0 条」；
  3. 工具入库时补充中文描述/中文别名，让关键词兜底对中文生效（配合工具描述中文化）。

### B2. 工具名被截断 → AI 脑补幻觉工具名

- **现象**：`listMonitors` → `istMonitors`、`captureScreen` → `aptureScreen`，AI 调用不存在的工具后被驳回重试。
- **证据**：`system.log` 13:44:11 / 13:54:53，`DefaultToolCallingManager` 提示 "name was truncated due to length limits"。
- **定位**：Spring AI 工具调用阶段工具名被截断（首字母丢失），`TurnProcessor` 的脑补检测只做「存在性校验」，没有做「近似名纠错」。
- **修复方向**：自定义 `McpToolNamePrefixGenerator` / 工具名规范化；或脑补检测时做首字母恢复与模糊匹配（编辑距离），把 `istMonitors` 纠正回 `listMonitors` 再执行。

### B3. Unit 缓存语义检索串扰（错误命中缓存计划）

- **现象**：OCR 会话与鼠标点击会话都命中了同一个「移动鼠标」缓存单元 `c07ff2c7`（稳定度 1.00），`PlanMatcher` 反复判定「计划不适用」再降级。
- **证据**：`system.log` 13:43:40 / 13:53:32 命中同一 unit；13:44:09 / 13:54:40 两次「计划不适用」降级。
- **定位**：`UnitStore` 语义检索把「整个 userInput（含工具清单）」作为检索向量，不同学习目标被混在一起；缓存键未剥离「学习目标」以外的噪音。
- **修复方向**：检索前提取「学习目标」摘要作为检索向量；提高匹配阈值[system.log](system.log)；或给 Unit 增加目标类型标签做过滤。

---

## P1

### B4. Neo4j schema 未初始化（关系/标签/属性缺失刷屏）

- **现象**：`FALLBACK`、`DISABLES` 关系类型、`FailureCause` 标签及其属性（`reason`/`inputArgs`/`suggestedUnitIdsJson` 等）持续报 `does not exist`，从启动到结束刷屏几十次。
- **证据**：`system.log` 13:40:12 ~ 14:00:15 大量 `cypher.unrecognized` WARN。
- **定位**：实体类有定义（`FailureCauseNode`/`DisablesRelation`/`FallbackRelation`），但 Neo4j 数据库里未创建对应约束/关系，缺首次初始化或迁移。
- **修复方向**：启动时执行 Cypher 创建约束与索引，或补迁移脚本，确保写入前 schema 存在。

### B5. 失败会话被当作成功沉淀

- **现象**：OCR 会话最终「校验未通过」，但日志仍记录 `Unit 成功沉淀: cb666136…（2 步）`。
- **证据**：`system.log` 13:49:26 校验未通过 → 13:49:46 成功沉淀。
- **定位**：`UnitSedimentationService` 的成功/失败判定未与「校验结果」挂钩，失败路径也走了成功沉淀。
- **修复方向**：校验未通过时不沉淀为成功 Unit（或标记为失败样本），只沉淀真正通过校验的计划。

### B6. 变量签名提取错乱

- **现象**：把 `searchTool` 的参数 `keyword`/`keywordEn` 当成「鼠标移动坐标变量」提取，污染计划复用。
- **证据**：`system.log` 13:49:38 提取 `[keyword, keywordEn]`；13:50:47 提取 `[firstKeywordZh, firstKeywordEn, secondKeywordZh, secondKeywordEn]`。
- **定位**：`ReflectService` 提取变量签名时未过滤元工具（`searchTool`/`listAllTools` 等），把检索参数误当成业务变量。
- **修复方向**：提取变量前过滤掉元工具；只从「实际执行目标操作的工具调用」中提取变量。

---

## P2

### B7. ToolCategoryService String→Map 强转崩溃（分类同步静默失败）

- **现象**：`class java.lang.String cannot be cast to class java.util.Map`，异常被 catch 吞掉只 `log.error`。
- **证据**：`system.log` 13:43:37 `❌ 工具分类同步失败`。
- **定位**：`ToolCategoryService.flattenTree()` 顶层 `for (Map node : nodes)` 迭代时，DeepSeek 返回的分类 JSON 顶层混入了字符串；`asMapList()` 只保护 `children`，未保护顶层。
- **修复方向**：`readValue` 后对顶层列表也用 `asMapList` 过滤，或清理非 Map 元素再进 `flattenTree`。

### B8. OCR 识别精度差（整词被识别成单字母）

- **现象**：`findTextOnScreen` 找「Copy」返回单个字母「o」、找「Cut」返回「u」，导致 AI 无法验证右键菜单。
- **证据**：`system.log` 13:57:09 / 14:03:54 / 14:04:01。
- **定位**：`ScreenButtonService.findTextOnScreen()` 的 OCR 匹配逻辑用「包含关系」找 word，Tesseract 把词拆成单字符后被误匹配。
- **修复方向**：提高 OCR 质量（psm/语言包/缩放）；匹配改为「整词/短语精确匹配」而非单字符包含；对匹配结果加置信度与长度校验。

### B9. 自主学习执行失控/低效/破坏性

- **现象**：一个「学习鼠标点击」目标跑了 14 分钟，大量无意义动作——按 8 次 BACKSPACE、`WIN+D` 最小化所有窗口、`typeText("HELLO123")` 打进真实 PowerShell、反复 `findTextOnScreen` 搜单字；目标未达成还动了用户桌面。
- **证据**：`system.log` 13:50:30 ~ 14:04:59。
- **定位**：`AutonomousExplorationService` 的探索目标生成 + AI 执行缺乏「步数上限 / 超时 / 危险操作拦截 / 目标校验提前终止」。
- **修复方向**：加执行步数与耗时上限；拦截破坏性操作（WIN+D、WIN+M、向真实窗口 typeText）；目标校验失败即提前终止并归因，而非无限重试。

---

## 建议修复顺序（供决策）

1. **B1 + B2**（先打通「找到工具 + 正确调用工具」的执行链路，其余都是建立在这之上的）
2. **B3 + B5 + B6**（修学习记忆的正确性，避免继续污染缓存）
3. **B4**（schema 补齐，消除刷屏、保证失败经验能落地）
4. **B7 + B8 + B9**（工程健壮性与探索收敛）

（注：`BUG_LIST.md` 由 AI 生成，按本次日志归纳，待逐个修复时回填状态与方案。）
