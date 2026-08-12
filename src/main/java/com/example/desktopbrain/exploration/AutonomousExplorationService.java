package com.example.desktopbrain.exploration;

import com.example.desktopbrain.common.PromptLoader;
import com.example.desktopbrain.config.DesktopBrainProperties;
import com.example.desktopbrain.config.SystemEnvironmentService;
import com.example.desktopbrain.memory.vector.episode.EpisodeCacheService;
import com.example.desktopbrain.memory.vector.episode.FailureExperienceHandler;
import com.example.desktopbrain.service.TurnProcessor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.desktopbrain.config.ModelRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 自主探索服务：空闲时收集上下文 → LLM 决策 → 提交后台执行。
 */
@Service
public class AutonomousExplorationService {

    private static final Logger log = LoggerFactory.getLogger(AutonomousExplorationService.class);

    private final IdleDetectionService idleDetection;
    private final EpisodeCacheService episodeCache;
    private final FailureExperienceHandler failureHandler;
    private final TurnProcessor turnProcessor;
    private final PromptLoader promptLoader;
    private final DesktopBrainProperties props;
    private final ObjectMapper objectMapper;

    private final ModelRouter modelRouter;
    private volatile ToolCallback[] tools;  // volatile: 启动后由 DesktopBrainApplication 设置完整列表
    private final SystemEnvironmentService envService;

    /** 探索串行锁：同一时间只有一个探索会话在执行（避免多会话抢屏幕/浏览器） */
    private final java.util.concurrent.locks.ReentrantLock exploreLock = new java.util.concurrent.locks.ReentrantLock();

    /** 动态基础能力映射：工具名+描述 → 能力分组（setAllTools 时自动构建，随工具变化而更新） */
    private record CapabilityDef(String name, List<String> toolNames, String taskHint) {}
    private volatile Map<String, CapabilityDef> dynamicCapabilities = Map.of();

    /** 重复目标黑名单：目标（标准化）→ 连续失败次数，≥2次不再作为候选 */
    private final Map<String, Integer> failedGoalCounts = new java.util.LinkedHashMap<>();
    /** 黑名单条目记录时间（epoch ms），用于 30 分钟过期 */
    private final Map<String, Long> goalFailedAt = new java.util.LinkedHashMap<>();
    private static final int BLACKLIST_THRESHOLD = 2;
    /** 黑名单过期时间：30 分钟 */
    private static final long BLACKLIST_TTL_MS = 30 * 60 * 1000;

    public AutonomousExplorationService(IdleDetectionService idleDetection,
                                         EpisodeCacheService episodeCache,
                                         FailureExperienceHandler failureHandler,
                                         TurnProcessor turnProcessor,
                                         PromptLoader promptLoader,
                                         DesktopBrainProperties props,
                                         ModelRouter modelRouter,
                                         SystemEnvironmentService envService) {
        this.idleDetection = idleDetection;
        this.episodeCache = episodeCache;
        this.failureHandler = failureHandler;
        this.turnProcessor = turnProcessor;
        this.promptLoader = promptLoader;
        this.props = props;
        this.objectMapper = new ObjectMapper();
        this.modelRouter = modelRouter;
        this.tools = new ToolCallback[0];  // 占位，启动后 setAllTools() 会填充
        this.envService = envService;
    }

    /** 由 DesktopBrainApplication 在启动后调用，传入完整工具列表（含生成工具） */
    public void setAllTools(ToolCallback[] allTools) {
        this.tools = allTools;
        this.dynamicCapabilities = buildDynamicCapabilities(allTools);
        log.info("🔧 探索服务已接收 {} 个工具, 检测到 {} 项基础能力", allTools.length, dynamicCapabilities.size());
    }

    /**
     * 定时巡检：每 1 分钟触发 3 个并发探索会话（包月模式，全速运行）。
     * 与手动触发（ExplorationTool）共享同一入口。
     */
    @Scheduled(fixedDelayString = "${desktopbrain.exploration.check-interval-ms:60000}",
               initialDelayString = "${desktopbrain.exploration.initial-delay-ms:60000}")
    public void scheduledExplore() {
        // 串行执行：同一时间只能有一个探索会话（操作屏幕/浏览器不能并发）
        tryExplore();
    }

    /**
     * 检查并触发探索（由定时任务或外部调用）。
     * 异步执行，不阻塞调用方。
     */
    public void tryExplore() {
        // TODO: 回头启用空闲检查
        // if (!idleDetection.shouldExplore()) return;
        doExplore();
    }

    /** 手动/强制触发探索，跳过空闲和免打扰检查。 */
    public void forceExplore() {
        doExplore();
    }

    private void doExplore() {
        CompletableFuture.runAsync(() -> {
            // 串行锁：如果上一个探索会话还在跑，直接跳过
            if (!exploreLock.tryLock()) {
                log.info("⏭️ 上一个探索会话仍在运行（屏幕/浏览器被占用），跳过本次");
                return;
            }
            try {
                log.info("🔍 开始自主探索会话...");

                // 0. 拉取已有数据（供兜底判断用）
                List<EpisodeCacheService.LearnedTopic> learnedTopics =
                        episodeCache.getRecentLearnedTopics(15);
                List<EpisodeCacheService.AttemptedTopic> attemptedTopics =
                        episodeCache.getRecentlyAttemptedTopics(20);

                // 0.5 P0-1: 构建失败目标黑名单（同一目标 FAILED ≥2次 → 跳过，但已有 ACTIVE 的不拉黑）
                Map<String, Integer> blacklisted = buildBlacklist(attemptedTopics, learnedTopics);

                // 1. 收集上下文（传入黑名单用于过滤候选）
                String context = buildContext(learnedTopics, attemptedTopics, blacklisted);

                // 2. LLM 决策
                ExplorationDecision decision = decide(context);

                // 3. 兜底：AI SKIP了但基础能力还有缺失 → 强制LEARN
                if ((decision == null || "SKIP".equalsIgnoreCase(decision.decision()))) {
                    List<String> missingBasics = computeMissingBasics(learnedTopics, blacklisted);
                    if (!missingBasics.isEmpty()) {
                        String firstMissing = missingBasics.get(0);
                        CapabilityDef def = dynamicCapabilities.get(firstMissing);
                        String task = def != null ? def.taskHint() : "学习" + firstMissing;
                        log.info("🛡️ AI 想SKIP但基础能力缺失，强制LEARN: {}", firstMissing);
                        decision = new ExplorationDecision(
                                "LEARN",
                                "基础能力缺失，强制学习: " + firstMissing,
                                task,
                                "internal_tool_probing",
                                List.of("鼠标键盘", "文件与窗口", "浏览器与Web"),
                                List.of("选择对应工具", "执行基础操作", "验证操作成功"),
                                "成功掌握" + firstMissing + "的基本用法",
                                "工具调用返回成功结果",
                                "HIGH"
                        );
                    } else {
                        log.info("⏭️ 探索跳过: {}", decision != null ? decision.reason() : "决策失败");
                        return;
                    }
                }

                // 3.5 P0-1: 判断目标是否在黑名单中 → 拦截
                if (isBlacklisted(decision.learningGoal(), blacklisted)) {
                    log.warn("⏭️ 探索目标已被黑名单拦截（已失败≥{}次）: {} → 跳过", BLACKLIST_THRESHOLD, decision.learningGoal());
                    return;
                }

                log.info("📚 探索目标: {} (方法: {}, 分类: {}, 优先级: {})", decision.learningGoal(), decision.learningMethod(), decision.toolCategories(), decision.priority());

                // 3.6 P0-1: 记录本次目标（用于后续黑名单判断）
                String normalizedGoal = normalizeGoal(decision.learningGoal());
                failedGoalCounts.merge(normalizedGoal, 1, Integer::sum);
                goalFailedAt.put(normalizedGoal, System.currentTimeMillis());  // 每次失败刷新过期时间
                // 清理旧条目（只保留最近 50 个）
                if (failedGoalCounts.size() > 50) {
                    String first = failedGoalCounts.keySet().iterator().next();
                    failedGoalCounts.remove(first);
                    goalFailedAt.remove(first);
                }

                // 4. 将学习目标送入主流程管线（TurnProcessor），复用 Plan→Execute→Reflect→Store
                String methodHint = switch (decision.learningMethod()) {
                    case "internal_tool_probing" -> "【学习方法：工具探测】在临时环境中测试已有工具的参数组合，发现新用法。你可以组合调用多个工具来探索它们的边界行为。";
                    case "web_research" -> "【学习方法：网络研究】使用浏览器工具搜索技术文档、阅读网页、提取并摘要关键信息。可以访问任意网址，优先查阅技术文档。";
                    case "download_and_learn" -> "【学习方法：下载实操】先检查电脑是否已有该软件（防重复下载）→ 用 createLearningWorkspace 创建临时目录 → 下载安装到临时目录 → 试用并记录 → 学完调用 cleanupLearningWorkspace 删除整个临时目录。下载前后都在知识库记录软件状态！";
                    default -> "【学习方法：自由探索】你可以自己决定用什么方式学习，使用任何可用的工具。";
                };

                // P0-2: 构建相关工具列表摘要（前30个工具名+描述，硬注入不让AI想象）
                String relevantToolsSummary = buildRelevantToolsSummary(decision);

                String learningInput = "【自主学习会话】\n" + methodHint
                        + "\n\n" + relevantToolsSummary
                        + "\n\n⚠️ 工具搜索提示：所有工具都是英文名！规划步骤前务必先调用 listAllTools 查看全部工具名称。调用 searchTool 时必须同时传入 中英文两个关键词，例如 searchTool(\"鼠标控制\", \"mouse click\") —— 系统会先用中文搜、搜不到自动用英文兜底。"
                        + "\n\n学习目标：" + decision.learningGoal()
                        + "\n期望成果：" + decision.expectedOutcome()
                        + "\n成功标准：" + decision.successCriteria();
                turnProcessor.processExploration(modelRouter, tools, learningInput);

            } catch (Exception e) {
                log.error("❌ 自主探索决策失败", e);
            } finally {
                exploreLock.unlock();
            }
        });
    }

    /** 构建上下文 prompt */
    private String buildContext(List<EpisodeCacheService.LearnedTopic> learnedTopics,
                                 List<EpisodeCacheService.AttemptedTopic> attemptedTopics,
                                 Map<String, Integer> blacklisted) {
        // 直接拉取最近失败模式（不用语义搜索硬编码关键词，让AI自行判断相关性）
        List<EpisodeCacheService.FailureSearchResult> failures =
                episodeCache.getRecentFailurePatterns(10);
        StringBuilder failureText = new StringBuilder("无");
        if (failures != null && !failures.isEmpty()) {
            failureText.setLength(0);
            failures.forEach(f -> failureText.append("- ").append(f.description()).append("\n"));
        }

        // 近期已学主题（成功经验 = AI 真正学到的东西，可验证/调整/优化）
        StringBuilder learnedText = new StringBuilder("暂无");
        if (learnedTopics != null && !learnedTopics.isEmpty()) {
            learnedText.setLength(0);
            for (EpisodeCacheService.LearnedTopic t : learnedTopics) {
                learnedText.append("- ").append(t.goal());
                if (t.lesson() != null && !t.lesson().isBlank()) {
                    learnedText.append("（经验: ").append(t.lesson()).append("）");
                }
                if (t.toolNames() != null && !t.toolNames().isEmpty()) {
                    learnedText.append(" [工具: ").append(String.join(", ", t.toolNames())).append("]");
                }
                learnedText.append("\n");
            }
        }

        // 近期尝试过的主题（包括失败的，让AI避免原地打转）
        // P0-1: 标注黑名单目标
        StringBuilder attemptedText = new StringBuilder("暂无");
        if (attemptedTopics != null && !attemptedTopics.isEmpty()) {
            attemptedText.setLength(0);
            for (EpisodeCacheService.AttemptedTopic t : attemptedTopics) {
                String marker = isBlacklisted(t.goal(), blacklisted) ? " ⛔已黑名单" : "";
                attemptedText.append("- [").append(t.status()).append("] ").append(t.goal()).append(marker).append("\n");
            }
        }

        // 知识库数量（从 Qdrant 实时查询 ACTIVE Episode 总数）
        int knowledgeCount = episodeCache.countActiveEpisodes();

        // 构建分类化工具概览（按前缀分组，避免单个工具列表过长）
        String toolOverview = buildToolOverview();

        // 能力清单：按难度分级展示已掌握能力 + 缺失的基础能力（黑名单已过滤）
        String capabilityInventory = buildCapabilityInventory(learnedTopics, blacklisted);

        String template = promptLoader.getAutonomousExploration();
        return template
                .replace("{os_info}", envService.getOsInfo())
                .replace("{unresolved_failures}", failureText.toString())
                .replace("{recently_learned}", learnedText.toString())
                .replace("{capability_inventory}", capabilityInventory)
                .replace("{recently_attempted}", attemptedText.toString())
                .replace("{unsolved_questions}", "暂无记录")
                .replace("{tool_list_with_usage}", toolOverview)
                .replace("{knowledge_count}", String.valueOf(knowledgeCount));
    }

    // ========== 能力清单：难度分级（动态，基于实际工具） ==========

    /** 能力检测规则：工具名/描述匹配 → 基础能力名称。只定义分类逻辑，不写死具体工具。 */
    private static final java.util.LinkedHashMap<String, String> CAPABILITY_RULES = new java.util.LinkedHashMap<>();
    static {
        // 工具名或描述中包含这些关键词 → 归类到对应基础能力
        CAPABILITY_RULES.put("截屏",        "screenshot|capture.*screen|截图|截屏|screen.*capture|snapshot");
        CAPABILITY_RULES.put("滚动页面",    "scroll|page.*down|page.*up|翻页|往下翻|往上翻");
        CAPABILITY_RULES.put("OCR识别文字",  "ocr|findText|readText|文字识别|光学字符|find.*text.*screen");
        CAPABILITY_RULES.put("读文件",       "read.*file|readFile|读文件|^read_|getFileContent|cat");
        CAPABILITY_RULES.put("写文件",       "write.*file|writeFile|写文件|^write_|save.*file|echo");
        CAPABILITY_RULES.put("鼠标点击",     "click|leftClick|rightClick|doubleClick|点击|鼠标.*击");
        CAPABILITY_RULES.put("键盘输入",     "type|keyboard|按键|press.*key|输入文字|keyPress");
        CAPABILITY_RULES.put("打开网页",     "browser|navigate|chromium|打开.*网页|open.*url|goto");
        CAPABILITY_RULES.put("移动鼠标",     "mouse.*move|moveMouse|移动鼠标|move.*cursor|moveTo");
    }

    /** 扫描所有工具，自动归类到基础能力分组 → 生成 CapabilityDef */
    private Map<String, CapabilityDef> buildDynamicCapabilities(ToolCallback[] allTools) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        Map<String, String> firstDescriptions = new LinkedHashMap<>();

        for (ToolCallback tc : allTools) {
            String name = tc.getToolDefinition().name();
            String desc = tc.getToolDefinition().description();
            String combined = (name + " " + (desc != null ? desc : "")).toLowerCase();

            for (var entry : CAPABILITY_RULES.entrySet()) {
                if (combined.matches(".*(" + entry.getValue() + ").*")) {
                    groups.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(name);
                    firstDescriptions.putIfAbsent(entry.getKey(),
                            desc != null && desc.length() > 0 ? desc : name);
                    break; // 一个工具只归入第一个匹配的能力
                }
            }
        }

        Map<String, CapabilityDef> result = new LinkedHashMap<>();
        for (var entry : groups.entrySet()) {
            String capName = entry.getKey();
            List<String> toolNames = entry.getValue();
            String desc = firstDescriptions.getOrDefault(capName, "");
            // 从工具描述截取前40字作为任务提示
            String hint = desc.length() > 40 ? desc.substring(0, 40) + "..." : desc;
            result.put(capName, new CapabilityDef(capName, toolNames,
                    "学习" + capName + "：使用 " + String.join("/", toolNames.subList(0, Math.min(3, toolNames.size()))) + " → 验证操作成功"));
        }
        return Collections.unmodifiableMap(result);
    }

    /** 根据目标文本和工具名判断难度等级（1-4） */
    private int classifyDifficulty(String goal, List<String> toolNames) {
        if (goal == null) goal = "";
        if (toolNames == null) toolNames = List.of();
        String goalLower = goal.toLowerCase();
        String toolsStr = String.join(" ", toolNames).toLowerCase();

        // 系统级关键词 → ⭐⭐⭐⭐
        if (goalLower.matches(".*(安装|docker|数据库|mysql|postgres|neo4j|redis|k8s|kubernetes|配置.*环境|部署|system.*config).*")
                || toolsStr.matches(".*(install|docker|database|config.*env|setup).*")) {
            return 4;
        }

        // 有依赖流程关键词 → ⭐⭐⭐
        if (goalLower.matches(".*(下载.*保存|下载.*写入|download.*save|download.*write|编译|compile|构建|build|打包|package).*")
                || toolsStr.matches(".*(download|compile|build).*")) {
            return 3;
        }
        // 多工具(3+)组合 → ⭐⭐⭐
        Set<String> toolCategories = new HashSet<>();
        for (String tn : toolNames) {
            toolCategories.add(toolCategoryFor(tn));
        }
        if (toolCategories.size() >= 3) return 3;

        // 组合操作关键词 → ⭐⭐
        if (goalLower.matches(".*(打开.*截图|搜索.*点击|search.*click|open.*capture|browse.*extract|网页.*识别).*")
                || toolCategories.size() == 2) {
            return 2;
        }

        // 默认 → ⭐
        return 1;
    }

    /** 简单归类工具名到功能类别 */
    private String toolCategoryFor(String toolName) {
        if (toolName == null) return "unknown";
        String n = toolName.toLowerCase();
        if (n.matches(".*(screenshot|capture|截图|screen).*")) return "screenshot";
        if (n.matches(".*(scroll|page|翻页|滚动).*")) return "scroll";
        if (n.matches(".*(ocr|findText|readText|文字识别).*")) return "ocr";
        if (n.matches(".*(read.*file|读文件|^read_|^cat_|^type_).*")) return "file_read";
        if (n.matches(".*(write.*file|写文件|^write_|^save_|^echo_).*")) return "file_write";
        if (n.matches(".*(click|点击|mouse.*click).*")) return "click";
        if (n.matches(".*(type|键盘|keyboard|按键|press.*key).*")) return "keyboard";
        if (n.matches(".*(browser|navigate|打开.*网页|open.*browser|chromium).*")) return "browser";
        if (n.matches(".*(mouse.*move|移动.*鼠标|光标).*")) return "mouse_move";
        return "other";
    }

    /** 构建能力清单：按难度分级展示已掌握+缺失，缺失项附带具体学习任务（黑名单已过滤） */
    private String buildCapabilityInventory(List<EpisodeCacheService.LearnedTopic> learnedTopics,
                                             Map<String, Integer> blacklisted) {
        // 按难度分组
        Map<Integer, List<EpisodeCacheService.LearnedTopic>> byLevel = new LinkedHashMap<>();
        byLevel.put(1, new ArrayList<>());
        byLevel.put(2, new ArrayList<>());
        byLevel.put(3, new ArrayList<>());
        byLevel.put(4, new ArrayList<>());

        if (learnedTopics != null) {
            for (EpisodeCacheService.LearnedTopic t : learnedTopics) {
                int level = classifyDifficulty(t.goal(), t.toolNames());
                byLevel.get(level).add(t);
            }
        }

        // 缺失的基础能力（基于动态能力映射 + 黑名单过滤）
        List<String> missingBasics = computeMissingBasics(learnedTopics, blacklisted);

        StringBuilder sb = new StringBuilder();

        // 总览
        int total = byLevel.values().stream().mapToInt(List::size).sum();
        sb.append("📊 能力总览：共 ").append(total).append(" 个成功案例\n");
        String[] stars = {"", "⭐", "⭐⭐", "⭐⭐⭐", "⭐⭐⭐⭐"};
        for (int lv = 1; lv <= 4; lv++) {
            int count = byLevel.get(lv).size();
            sb.append("  难度").append(stars[lv]).append("：").append(count).append(" 个");
            if (!byLevel.get(lv).isEmpty()) {
                List<String> examples = byLevel.get(lv).stream()
                        .limit(3)
                        .map(t -> t.goal().length() > 30 ? t.goal().substring(0, 30) + "..." : t.goal())
                        .toList();
                sb.append("（如：").append(String.join("、", examples)).append("）");
            }
            sb.append("\n");
        }

        // P0-1: 被黑名单拦截的能力单独提示
        if (!blacklisted.isEmpty()) {
            sb.append("\n⛔ 以下能力已被暂时冻结（多次失败，跳过）：\n");
            for (var entry : blacklisted.entrySet()) {
                sb.append("  - ").append(entry.getKey()).append("（失败").append(entry.getValue()).append("次）\n");
            }
        }

        // 缺失的基础能力（附具体学习任务，AI必须从中选，黑名单已过滤）
        if (!missingBasics.isEmpty()) {
            sb.append("\n⚠️ 你必须从以下候选任务中选择一个学习（禁止SKIP！）：\n");
            int idx = 1;
            for (String b : missingBasics) {
                CapabilityDef def = dynamicCapabilities.get(b);
                String task = def != null ? def.taskHint() : "学习" + b;
                sb.append("  候选").append(idx).append(": 【").append(b).append("】").append(task).append("\n");
                idx++;
            }
            sb.append("  用 internal_tool_probing 方法，工具分类选「鼠标键盘」或「文件与窗口」或「浏览器与Web」。\n");
        } else if (!blacklisted.isEmpty()) {
            sb.append("\n⚠️ 所有基础能力已被尝试但部分被冻结。请在非冻结的已有能力基础上向难度⭐⭐扩展。\n");
        } else {
            sb.append("\n✅ 基础能力已覆盖，可以在已有基础上向难度⭐⭐扩展。\n");
        }

        return sb.toString();
    }

    /** 从 learnedTopics + 动态能力映射计算缺失的基础能力（黑名单已过滤） */
    private List<String> computeMissingBasics(List<EpisodeCacheService.LearnedTopic> learnedTopics,
                                                Map<String, Integer> blacklisted) {
        if (dynamicCapabilities.isEmpty()) return List.of();

        // 收集已学主题中覆盖的能力名称
        Set<String> covered = new HashSet<>();
        List<EpisodeCacheService.LearnedTopic> safe = learnedTopics != null ? learnedTopics : List.of();
        for (EpisodeCacheService.LearnedTopic t : safe) {
            String combined = (t.goal() != null ? t.goal() : "")
                    + " " + String.join(" ", t.toolNames() != null ? t.toolNames() : List.of());
            for (var entry : CAPABILITY_RULES.entrySet()) {
                if (combined.toLowerCase().matches(".*(" + entry.getValue() + ").*")) {
                    covered.add(entry.getKey());
                }
            }
        }

        // P0-1: 黑名单过滤 — 被黑名单拦截的能力不列入候选
        List<String> missing = new ArrayList<>();
        for (String capName : dynamicCapabilities.keySet()) {
            if (covered.contains(capName)) continue;
            // 检查该能力的 taskHint 是否匹配黑名单中的任何目标
            CapabilityDef def = dynamicCapabilities.get(capName);
            if (def != null && isBlacklisted(def.taskHint(), blacklisted)) {
                log.info("⛔ 能力「{}」已被黑名单拦截（taskHint: {}），跳过", capName, def.taskHint());
                continue;
            }
            if (isBlacklisted(capName, blacklisted)) {
                log.info("⛔ 能力名「{}」已被黑名单拦截，跳过", capName);
                continue;
            }
            missing.add(capName);
        }
        return missing;
    }

    /** 构建分类化工具概览（按 MCP 前缀/功能分组，精简版） */
    private String buildToolOverview() {
        List<String> toolNames = new ArrayList<>();
        for (ToolCallback tc : tools) {
            toolNames.add(tc.getToolDefinition().name());
        }
        Collections.sort(toolNames);

        // 按前缀分组
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String name : toolNames) {
            int idx = name.indexOf('_');
            String prefix = idx > 0 ? name.substring(0, idx) : name;
            // 合并短前缀
            if (prefix.length() <= 3 || prefix.equals("browser") || prefix.equals("chromium")) {
                prefix = "浏览器与Web";
            } else if (prefix.equals("click") || prefix.equals("type") || prefix.equals("scroll") || prefix.equals("press") || prefix.equals("key") || prefix.equals("mouse")) {
                prefix = "鼠标键盘";
            } else if (prefix.equals("find") || prefix.equals("read") || prefix.equals("write") || prefix.equals("glob") || prefix.equals("edit") || prefix.equals("delete") || prefix.equals("open")) {
                prefix = "文件与窗口";
            } else if (prefix.equals("search") || prefix.equals("list")) {
                prefix = "工具查询";
            } else if (prefix.equals("get") || prefix.equals("set")) {
                prefix = "系统信息";
            } else if (prefix.equals("generate") || prefix.equals("fix") || prefix.equals("searchCodebase")) {
                prefix = "代码工具";
            } else if (prefix.equals("query") || prefix.equals("store")) {
                prefix = "知识库";
            } else if (prefix.equals("consult") || prefix.equals("fallback")) {
                prefix = "AI辅助";
            }
            groups.computeIfAbsent(prefix, k -> new ArrayList<>()).add(name);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            sb.append("【").append(e.getKey()).append("】");
            sb.append(String.join(", ", e.getValue()));
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 从 AI 返回的文本中提取 JSON 对象 */
    private static String extractJson(String text) {
        if (text == null || text.isBlank()) return null;
        String s = text.trim();
        // 0. 去掉可能的思考标签（如 deepseek-r1 的 <｜end▁of▁thinking｜>...）
        s = s.replaceAll("(?s)___[^_]*___", "");
        s = s.replaceAll("(?s)^\\s*", "");
        s = s.trim();
        // 1. 去掉 markdown 代码块
        s = s.replaceAll("(?s)```[a-zA-Z]*\\s*", "```"); // normalize ```json → ```
        if (s.startsWith("```")) {
            s = s.substring(3);
            int end = s.lastIndexOf("```");
            if (end > 0) s = s.substring(0, end);
        }
        s = s.trim();
        // 2. 找第一个 { 或 [ 到对应的结尾
        int start = s.indexOf('{');
        if (start < 0) start = s.indexOf('[');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        char openChar = s.charAt(start);
        char closeChar = openChar == '{' ? '}' : ']';
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (escaped) { escaped = false; continue; }
                if (c == '\\') { escaped = true; continue; }
                if (c == '"') inString = false;
                continue;
            }
            if (c == '"') { inString = true; continue; }
            if (c == openChar) depth++;
            else if (c == closeChar) { depth--; if (depth == 0) return s.substring(start, i + 1); }
        }
        return null;
    }

    // ========== P0-1: 黑名单机制 ==========

    /** 从最近尝试主题构建黑名单：同一目标 FAILED ≥2次 → 列入。已有 ACTIVE 经验的目标不拉黑。超过 30 分钟自动过期。 */
    private Map<String, Integer> buildBlacklist(List<EpisodeCacheService.AttemptedTopic> attemptedTopics,
                                                 List<EpisodeCacheService.LearnedTopic> learnedTopics) {
        Map<String, Integer> blacklisted = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        if (attemptedTopics == null || attemptedTopics.isEmpty()) return blacklisted;

        // 先收集已掌握的目标（ACTIVE），这些不拉黑
        Set<String> masteredGoals = new LinkedHashSet<>();
        if (learnedTopics != null) {
            for (EpisodeCacheService.LearnedTopic lt : learnedTopics) {
                String normalized = normalizeGoal(lt.goal());
                if (!normalized.isBlank()) masteredGoals.add(normalized);
            }
        }

        for (EpisodeCacheService.AttemptedTopic t : attemptedTopics) {
            if (!"FAILED".equalsIgnoreCase(t.status())) continue;
            String key = normalizeGoal(t.goal());
            if (key.isBlank()) continue;

            // 已有 ACTIVE 成功经验 → 不拉黑
            if (masteredGoals.contains(key)) {
                log.debug("🛡️ 不拉黑 '{}'（已有 ACTIVE 成功经验）", t.goal());
                continue;
            }

            int count = blacklisted.merge(key, 1, Integer::sum);
            if (count >= BLACKLIST_THRESHOLD) {
                log.debug("⛔ 黑名单: {}（FAILED {}次）", t.goal(), count);
            }
        }
        // 合并内存中的 failedGoalCounts（30 分钟过期）
        for (var entry : failedGoalCounts.entrySet()) {
            String key = entry.getKey();
            Long recordedAt = goalFailedAt.get(key);
            if (recordedAt != null && (now - recordedAt) > BLACKLIST_TTL_MS) {
                continue; // 已过期，跳过
            }
            blacklisted.merge(key, entry.getValue(), Integer::sum);
        }
        return blacklisted;
    }

    /** 检查目标是否在黑名单中（模糊匹配：归一化后包含） */
    private boolean isBlacklisted(String goal, Map<String, Integer> blacklisted) {
        if (goal == null || goal.isBlank() || blacklisted.isEmpty()) return false;
        String normalized = normalizeGoal(goal);
        if (normalized.length() < 5) return false; // 太短不判断
        // 精确匹配
        if (blacklisted.containsKey(normalized)) return true;
        // 模糊匹配：黑名单中的 key 是 normalized goal 的子串
        for (String blocked : blacklisted.keySet()) {
            if (blocked.length() < 5) continue;
            if (normalized.contains(blocked) || blocked.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** 目标归一化：去标点、空白、小写 */
    private String normalizeGoal(String goal) {
        if (goal == null || goal.isBlank()) return "";
        return goal.replaceAll("[\\s\\p{P}]", "").toLowerCase();
    }

    // ========== P0-2: 强制工具发现 ==========

    /** 构建与决策目标相关的工具摘要（前30个工具名+描述，硬注入不让AI想象） */
    private String buildRelevantToolsSummary(ExplorationDecision decision) {
        if (tools == null || tools.length == 0) return "（工具列表暂不可用）";

        // 根据决策的工具分类筛选相关工具
        List<String> relevantCategories = decision.toolCategories();
        List<String> categoryLower = relevantCategories != null
                ? relevantCategories.stream().map(String::toLowerCase).toList()
                : List.of();

        StringBuilder sb = new StringBuilder("📋 可用工具清单（必须使用已有工具，禁止调用不存在的工具名）：\n");
        List<ToolCallback> sorted = new ArrayList<>(List.of(tools));
        // 优先展示与分类相关的工具
        sorted.sort((a, b) -> {
            String descA = a.getToolDefinition().description();
            String descB = b.getToolDefinition().description();
            String nameA = a.getToolDefinition().name();
            String nameB = b.getToolDefinition().name();
            String combinedA = (nameA + " " + (descA != null ? descA : "")).toLowerCase();
            String combinedB = (nameB + " " + (descB != null ? descB : "")).toLowerCase();

            int scoreA = 0, scoreB = 0;
            for (String cat : categoryLower) {
                if (combinedA.contains(cat.toLowerCase())) scoreA++;
                if (combinedB.contains(cat.toLowerCase())) scoreB++;
            }
            return Integer.compare(scoreB, scoreA); // 高分在前
        });

        int limit = Math.min(30, sorted.size());
        for (int i = 0; i < limit; i++) {
            ToolCallback tc = sorted.get(i);
            String name = tc.getToolDefinition().name();
            String desc = tc.getToolDefinition().description();
            String shortDesc = (desc != null && desc.length() > 80)
                    ? desc.substring(0, 80) + "..."
                    : (desc != null ? desc : "");
            sb.append("  ").append(name);
            if (!shortDesc.isBlank()) sb.append(" — ").append(shortDesc);
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 获取探索用 ChatClient — 统一走 ModelRouter */
    private ChatClient explorationClient() {
        return modelRouter.chat(ModelRouter.Mode.EXPLORATION);
    }

    /** 调用模型做探索决策 */
    private ExplorationDecision decide(String context) {
        try {
            String response = explorationClient().prompt()
                    .user(context)
                    .call()
                    .content();

            if (response == null || response.isBlank()) {
                log.warn("⏭️ 探索决策: AI 返回空响应");
                return null;
            }

            String json = extractJson(response);
            if (json == null) {
                log.warn("⏭️ 探索决策: 无法提取 JSON（前 200 字符）: {}", response.substring(0, Math.min(200, response.length())));
                return null;
            }

            Map<String, Object> map = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});

            String decision = String.valueOf(map.getOrDefault("decision", "SKIP"));
            if ("SKIP".equalsIgnoreCase(decision)) {
                String reason = String.valueOf(map.getOrDefault("reason", ""));
                return new ExplorationDecision("SKIP", reason, null, null, null, null, null, null, "LOW");
            }

            @SuppressWarnings("unchecked")
            List<String> steps = (List<String>) map.get("steps");
            @SuppressWarnings("unchecked")
            List<String> toolCategories = (List<String>) map.get("toolCategories");
            String learningMethod = String.valueOf(map.getOrDefault("learningMethod", "other"));

            return new ExplorationDecision(
                    "LEARN",
                    String.valueOf(map.getOrDefault("reason", "")),
                    String.valueOf(map.getOrDefault("learningGoal", "")),
                    learningMethod,
                    toolCategories != null ? toolCategories : List.of(),
                    steps != null ? steps : List.of(),
                    String.valueOf(map.getOrDefault("expectedOutcome", "")),
                    String.valueOf(map.getOrDefault("successCriteria", "")),
                    String.valueOf(map.getOrDefault("priority", "MEDIUM"))
            );

        } catch (Exception e) {
            log.error("❌ 探索决策失败: {}", e.getMessage());
            return null;
        }
    }
}
