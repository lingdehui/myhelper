package com.example.myhelper.memory.vector.episode;

import com.example.myhelper.common.AiResponseUtils;
import com.example.myhelper.common.PromptLoader;
import com.example.myhelper.config.ModelRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划匹配器：AI 判断 episode 是否适用于当前输入 + 提取变量值。
 *
 * <p>用户核心逻辑："下次再匹配类似的→直接让AI判断这个成功计划是否可用→
 * 可用就按计划走→不可用新拟计划"。</p>
 *
 * <p>命中 Episode 后，不直接复用，而是先调 AI 判断：
 * <ol>
 *   <li>这个计划（步骤+变量签名）是否适用于当前用户输入？</li>
 *   <li>如果适用，从用户输入中提取变量值（如 contact=张三, message=明天开会）</li>
 * </ol>
 * 适用 → PlanExecutor 按计划执行（变量替换）；
 * 不适用 → 走 AI 新规划路径。</p>
 */
@Service
public class PlanMatcher {

    private static final Logger log = LoggerFactory.getLogger(PlanMatcher.class);

    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public PlanMatcher(ModelRouter modelRouter, PromptLoader promptLoader) {
        this.modelRouter = modelRouter;
        this.objectMapper = new ObjectMapper();
        this.promptLoader = promptLoader;
    }

    /**
     * 匹配结果。
     *
     * @param applicable 计划是否适用于当前输入
     * @param variables  提取的变量值（applicable=true 时有效，key=变量名, value=变量值）
     * @param reason     判断理由（用于日志和调试）
     */
    public record MatchResult(boolean applicable, Map<String, String> variables, String reason) {
        /** 不适用的快捷构造 */
        public static MatchResult notApplicable(String reason) {
            return new MatchResult(false, Collections.emptyMap(), reason);
        }
        /** 适用的快捷构造 */
        public static MatchResult applicable(Map<String, String> variables, String reason) {
            return new MatchResult(true, variables, reason);
        }
    }

    /**
     * AI 判断 episode 是否适用于当前输入，提取变量值。
     *
     * <p>ATOMIC 通用步骤：直接适用（无需变量匹配，步骤即开即用）。</p>
     *
     * @param userInput 用户原话
     * @param episode   命中的候选 episode
     * @return 匹配结果；AI 调用失败时返回 notApplicable（降级到新规划）
     */
    public MatchResult match(String userInput, Episode episode) {
        return match(userInput, episode, modelRouter.chat());
    }

    /** 使用指定 ChatClient 判断（探索模式走云端时传入 cloudOnly 客户端） */
    public MatchResult match(String userInput, Episode episode, ChatClient client) {
        if (client == null) return match(userInput, episode);
        return doMatch(userInput, episode, client);
    }

    private MatchResult doMatch(String userInput, Episode episode, ChatClient client) {
        if (episode.toolCalls() == null || episode.toolCalls().isEmpty()) {
            return MatchResult.notApplicable("计划没有可执行步骤");
        }

        // ATOMIC 通用步骤：检查关键词相关性后才适用
        // 不能无条件适用，否则 "发微信给张三" 的 ATOMIC 会被匹配到 "打开记事本"
        if (episode.unitType() == Episode.UnitType.ATOMIC && episode.isGeneric()) {
            String reason = "ATOMIC 通用步骤: " + episode.userInput();
            if (isSemanticallyRelated(userInput, episode.userInput())) {
                log.info("🧩 ATOMIC 通用步骤适用: {}", reason);
                return MatchResult.applicable(Map.of(), reason);
            } else {
                log.info("🧩 ATOMIC 通用步骤不匹配当前输入，跳过: {}", reason);
                return MatchResult.notApplicable("ATOMIC 工具链与用户输入不相关");
            }
        }

        // 构造步骤描述
        StringBuilder stepsDesc = new StringBuilder();
        for (int i = 0; i < episode.toolCalls().size(); i++) {
            ToolCallLog step = episode.toolCalls().get(i);
            stepsDesc.append(i + 1).append(". ").append(step.toolName())
                    .append("(").append(step.args()).append(")\n");
        }

        // 构造变量列表
        String varList = episode.signature() == null || episode.signature().isEmpty()
                ? "（无变量，直接复用即可）"
                : String.join(", ", episode.signature().keySet());

        String prompt = promptLoader.getPlanMatch()
                .formatted(stripPii(userInput), episode.userInput(), stepsDesc, varList);

        try {
            String response = client.prompt().user(prompt).call().content();
            return parseMatchResult(response);
        } catch (Exception e) {
            log.error("⚠️ PlanMatcher AI 判断失败: {}", e.getMessage());
            return MatchResult.notApplicable("AI 判断异常: " + e.getMessage());
        }
    }

    /**
     * 轻量级语义相关性检查（免 AI 调用）：提取 ATOMIC 工具链中的关键词，
     * 检查是否与用户输入有重叠。避免 "发微信" ATOMIC 被匹配到 "打开记事本"。
     *
     * @param userInput   用户原话
     * @param toolChain   工具链描述（如 "findFriend→typeTextViaClipboard"）
     * @return 是否语义相关
     */
    private static boolean isSemanticallyRelated(String userInput, String toolChain) {
        if (userInput == null || toolChain == null) return false;

        // 1. 提取 ATOMIC 工具链中的中文关键词（从 userInput 字段提取）
        String inputLower = userInput.toLowerCase();
        String chainLower = toolChain.toLowerCase();

        // 2. 从工具名拆出英文关键词（camelCase 拆分）
        //    例: "findFriend" → ["find", "friend"], "typeTextViaClipboard" → ["type", "text", "via", "clipboard"]
        Set<String> keywords = new HashSet<>();
        for (String toolName : chainLower.split("→")) {
            for (String word : toolName.split("(?=[A-Z])")) {
                if (word.length() >= 2) keywords.add(word.toLowerCase());
            }
        }

        // 3. 附加中文语义映射（工具名 → 中文意图关键词）
        Map<String, List<String>> semanticMap = Map.of(
            "findfriend", List.of("找", "查", "搜索", "联系人", "微信", "好友"),
            "type", List.of("发", "输入", "打字", "消息", "发送", "写"),
            "text", List.of("消息", "文字", "内容"),
            "clipboard", List.of("粘贴", "复制"),
            "click", List.of("点击", "按", "点"),
            "window", List.of("窗口", "界面"),
            "ocr", List.of("识别", "扫描"),
            "presskey", List.of("按键", "快捷键"),
            "mousemove", List.of("鼠标", "移动"),
            "leftclick", List.of("点击", "左键")
        );

        // 4. 检查输入与关键词的匹配
        for (String kw : keywords) {
            List<String> mapped = semanticMap.getOrDefault(kw, List.of());
            for (String mk : mapped) {
                if (inputLower.contains(mk)) return true;
            }
            // 英文关键词直接查
            if (inputLower.contains(kw)) return true;
        }

        // 5. 中文 bigram 重叠（来自 MyHelperApplication 的 extractKeywords 思路）
        Set<String> inputBigrams = new HashSet<>();
        for (int i = 0; i < userInput.length() - 1; i++) {
            String bigram = userInput.substring(i, i + 2);
            if (!bigram.matches("[\\p{Punct}\\s]+")) inputBigrams.add(bigram);
        }
        for (String kw : keywords) {
            for (String bigram : inputBigrams) {
                if (bigram.equals(kw)) return true;
            }
        }

        return false;
    }

    /**
     * 解析 AI 返回的 JSON 为 MatchResult。
     * 容错处理：去掉可能的 markdown 代码块标记，解析失败返回 notApplicable。
     */
    @SuppressWarnings("unchecked")
    private MatchResult parseMatchResult(String response) {
        if (response == null || response.isBlank()) {
            return MatchResult.notApplicable("AI 返回空");
        }

        String json = AiResponseUtils.stripMarkdownCodeBlock(response);
        json = fixJsonKeys(json);

        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            boolean applicable = Boolean.TRUE.equals(parsed.get("applicable"));
            String reason = (String) parsed.getOrDefault("reason", "");
            Map<String, String> variables = Collections.emptyMap();
            if (applicable && parsed.containsKey("variables")) {
                Map<String, Object> rawVars = (Map<String, Object>) parsed.get("variables");
                variables = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, Object> e : rawVars.entrySet()) {
                    variables.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            if (applicable) {
                log.info("✅ 计划可用（变量: {}）{}", variables, reason);
                return MatchResult.applicable(variables, reason);
            } else {
                log.info("❌ 计划不适用: {}", reason);
                return MatchResult.notApplicable(reason);
            }
        } catch (Exception e) {
            log.error("⚠️ PlanMatcher JSON 解析失败: {} | 原始: {}", e.getMessage(), json);
            return MatchResult.notApplicable("JSON 解析失败");
        }
    }

    /**
     * 修复 AI 模型输出的坏 JSON（qwen2.5 有时输出 {key: val} 缺引号）。
     * 给未加引号的 key 加上双引号，避免 Jackson 解析失败。
     */
    private static String fixJsonKeys(String json) {
        if (json == null || json.isBlank()) return json;
        // 把 :key: 这种格式修复为 :"key":
        // 正则匹配：冒号后紧跟非引号、非空格的标识符
        String fixed = json.replaceAll(":([a-zA-Z_][a-zA-Z0-9_]*)", ":\"$1\"");
        // 把对象开头 {key: 修复为 {"key":
        fixed = fixed.replaceAll("\\{([a-zA-Z_][a-zA-Z0-9_]*)", "{\"$1\"");
        // 把逗号后 key: 修复为 ,"key":
        fixed = fixed.replaceAll(",\\s*([a-zA-Z_][a-zA-Z0-9_]*)", ",\"$1\"");
        return fixed;
    }

    /**
     * 脱敏：去掉明显 PII（11位手机号、18位身份证号），保留语义用于匹配。
     * 传给云端 AI 做 PlanMatcher 时使用。
     */
    private static String stripPii(String input) {
        if (input == null) return null;
        return input
                .replaceAll("1[3-9]\\d{9}", "[手机号]")
                .replaceAll("\\d{17}[\\dXx]", "[身份证]");
    }
}
