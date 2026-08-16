package com.example.myhelper.memory.vector.episode;

import com.example.myhelper.common.AiResponseUtils;
import com.example.myhelper.common.PromptLoader;
import com.example.myhelper.config.ModelRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 反思服务（ExpeL/MUSE 的 Reflect 环节）。
 *
 * <p>用户逻辑中加入的关键缺失项：Plan→Execute→<b>Reflect</b>→Memorize 闭环。
 * 不只是"成功就存/失败就+1"，而是每次执行后让 AI 反思提取经验教训：</p>
 *
 * <ul>
 *   <li><b>成功反思</b>：AI 总结一句关键成功经验（successLesson），
 *       下次类似请求复用计划时作为 hint 提示 AI "上次这样做成功了"</li>
 *   <li><b>失败反思</b>：AI 分析失败原因 + 归因（计划问题 vs 环境问题），
 *       提取一句失败教训（failureLesson），下次命中时作为警示</li>
 * </ul>
 *
 * <p>归因结果直接决定失败处理路径：
 * 计划问题→失败数+1（惩罚计划）；环境问题→分段继续（不惩罚计划）。</p>
 *
 * <p>反思是同步的（~300ms 轻量 AI 调用），因为：
 * 成功反思结果要随 episode 一起存；失败反思结果要决定归因路径。</p>
 */
@Service
public class ReflectService {

    private static final Logger log = LoggerFactory.getLogger(ReflectService.class);

    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;
    private final ObjectMapper objectMapper;

    public ReflectService(ModelRouter modelRouter,
                           PromptLoader promptLoader) {
        this.modelRouter = modelRouter;
        this.objectMapper = new ObjectMapper();
        this.promptLoader = promptLoader;
    }

    private ChatClient client(ModelRouter.Mode mode) {
        return modelRouter.chat(mode);
    }

    /**
     * 失败分析结果。
     *
     * @param lesson     失败教训（30字内，下次作为警示）
     * @param isPlanIssue 是否是计划逻辑问题（true=惩罚计划 / false=环境问题不惩罚）
     */
    public record FailureAnalysis(String lesson, boolean isPlanIssue) {}

    /** 执行校验结果 */
    public record VerificationResult(
            boolean success,
            String reason,
            /** 校验失败时可独立复用的连续步骤索引链（如 [[0,1], [3]] 表示第0-1步和第3步可复用） */
            List<List<Integer>> salvageableChains
    ) {
        public static VerificationResult ok() { return new VerificationResult(true, "OK", List.of()); }
        public static VerificationResult failed(String reason, List<List<Integer>> chains) { return new VerificationResult(false, reason, chains); }
    }

    /**
     * AI 校验执行效果 + 失败时提取可复用步骤链。
     *
     * <p>核心逻辑：工具执行成功 ≠ 实际效果达标。让 AI 判断任务是否真正完成，
     * 如果没完成，AI 同时指出哪些连续的步骤是可以独立复用的（如"打开浏览器→输入网址"）。
     * 这些步骤链会被存为 ATOMIC 通用模板，下次不同任务也能复用。</p>
     *
     * @param userInput  用户原话
     * @param toolCalls  执行轨迹（含成功/失败标记）
     * @param aiResponse AI 最终回复
     * @return 校验结果；AI 调用失败时默认返回 success=true（不阻塞流程）
     */
    public VerificationResult verifyExecution(ModelRouter.Mode mode, String userInput, List<ToolCallLog> toolCalls, String aiResponse) {
        String stepsSummary = formatSteps(toolCalls);
        String prompt = promptLoader.getVerifyExecution()
                .formatted(userInput, stepsSummary, AiResponseUtils.truncate(aiResponse, 300));

        try {
            String response = client(mode).prompt().user(prompt).call().content();
            return parseVerificationResult(response);
        } catch (Exception e) {
            log.error("⚠️ 执行校验失败: {}", e.getMessage());
            // AI故障时保守处理：不存错误结果，让上层重试
            return VerificationResult.failed("校验服务不可用: " + e.getMessage(), List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private VerificationResult parseVerificationResult(String response) {
        if (response == null || response.isBlank()) return VerificationResult.ok();
        String json = AiResponseUtils.stripMarkdownCodeBlock(response);
        try {
            var parsed = objectMapper.readValue(json, java.util.Map.class);
            boolean success = !Boolean.FALSE.equals(parsed.get("success"));
            String reason = (String) parsed.getOrDefault("reason", "");

            List<List<Integer>> chains = List.of();
            List<List<Object>> rawChains = (List<List<Object>>) parsed.get("salvageableChains");
            if (rawChains != null && !rawChains.isEmpty()) {
                chains = rawChains.stream()
                        .map(c -> c.stream().map(n -> ((Number) n).intValue()).toList())
                        .toList();
            }

            return new VerificationResult(success, reason, chains);
        } catch (Exception e) {
            log.error("⚠️ 校验JSON解析失败: {}", e.getMessage());
            return VerificationResult.failed("校验返回格式错误", List.of());
        }
    }

    /**
     * 变量签名提取结果。
     *
     * @param signature          变量名 → 来源描述（"user_input" 表示从用户输入提取）
     * @param templatedToolCalls args 已模板化（具体值→$varName）的 toolCalls
     */
    public record SignatureExtraction(
            Map<String, String> signature,
            List<ToolCallLog> templatedToolCalls
    ) {
        /** 提取失败的空结果（不模板化，原样保留 toolCalls） */
        public static SignatureExtraction fallback(List<ToolCallLog> original) {
            return new SignatureExtraction(Map.of(), original);
        }
    }

    /**
     * 提取变量签名 + 模板化 toolCalls 的 args。
     *
     * <p>用户核心设计："存 episode 时 AI 把 toolCalls 的 args 模板化（'张三'→'$contact'），
     * 并提取 signature（变量名列表）。执行时 AI 提取变量值，PlanExecutor 做变量替换"。</p>
     *
     * <p>此方法让 AI 分析执行轨迹，找出 args 里的具体值（如联系人名、消息内容），
     * 替换为 $变量名 占位符，并记录变量来源。这样下次"发微信给李四"命中 episode 时，
     * PlanMatcher 提取 contact=李四，PlanExecutor 把 $contact 替换成李四。</p>
     *
     * @param userInput 用户原话
     * @param toolCalls 执行轨迹（args 是具体值）
     * @return 提取结果（signature + 模板化后的 toolCalls）；AI 失败时 fallback 原样返回
     */
    @SuppressWarnings("unchecked")
    public SignatureExtraction extractSignature(ModelRouter.Mode mode, String userInput, List<ToolCallLog> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return SignatureExtraction.fallback(toolCalls);
        }

        // 过滤元工具：只从实际执行目标操作的工具调用中提取变量签名（B6 修复）
        List<ToolCallLog> businessSteps = toolCalls.stream()
                .filter(tc -> !AiResponseUtils.isMetaTool(tc.toolName()))
                .toList();
        if (businessSteps.isEmpty()) {
            log.info("⚠️ 轨迹仅含元工具（搜索/列举），跳过变量签名提取");
            return SignatureExtraction.fallback(toolCalls);
        }

        StringBuilder stepsDesc = new StringBuilder();
        for (int i = 0; i < businessSteps.size(); i++) {
            ToolCallLog step = businessSteps.get(i);
            stepsDesc.append(i + 1).append(". ").append(step.toolName())
                    .append(" args=").append(AiResponseUtils.truncate(step.args(), 200)).append("\n");
        }

        String prompt = promptLoader.getExtractSignature()
                .formatted(userInput, stepsDesc);

        try {
            String response = client(mode).prompt().user(prompt).call().content();
            return parseSignatureExtraction(response, businessSteps);
        } catch (Exception e) {
            log.error("⚠️ 签名提取失败，原样保留 toolCalls: {}", e.getMessage());
            return SignatureExtraction.fallback(businessSteps);
        }
    }

    @SuppressWarnings("unchecked")
    private SignatureExtraction parseSignatureExtraction(String response, List<ToolCallLog> original) {
        if (response == null || response.isBlank()) {
            return SignatureExtraction.fallback(original);
        }
        String json = AiResponseUtils.stripMarkdownCodeBlock(response);
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);

            // 解析 signature
            Map<String, String> signature = new java.util.LinkedHashMap<>();
            List<Map<String, Object>> vars = (List<Map<String, Object>>) parsed.getOrDefault("variables", List.of());
            for (Map<String, Object> v : vars) {
                String name = String.valueOf(v.get("name"));
                String source = String.valueOf(v.getOrDefault("source", "user_input"));
                signature.put(name, source);
            }

            // 过滤荒谬签名：超过 5 个变量 或 全是 keywordN 模式 → 拒绝
            if (signature.size() > 5 || signature.keySet().stream().allMatch(k -> k.matches("keyword\\d+"))) {
                log.info("⚠️ 签名提取不适用（{} 个变量/自动编号），保留原样", signature.size());
                return SignatureExtraction.fallback(original);
            }

            // 解析模板化后的 args，重建 toolCalls
            Map<Integer, String> templatedArgs = new java.util.HashMap<>();
            List<Map<String, Object>> steps = (List<Map<String, Object>>) parsed.getOrDefault("steps", List.of());
            for (Map<String, Object> s : steps) {
                int index = ((Number) s.get("index")).intValue();
                String args = String.valueOf(s.get("args"));
                templatedArgs.put(index, args);
            }

            List<ToolCallLog> templated = new java.util.ArrayList<>();
            for (int i = 0; i < original.size(); i++) {
                ToolCallLog orig = original.get(i);
                String newArgs = templatedArgs.getOrDefault(i, orig.args());
                templated.add(new ToolCallLog(orig.toolName(), newArgs,
                        orig.result(), orig.success(), orig.durationMs()));
            }

            log.info("✅ 提取变量签名: {}", signature.keySet());
            return new SignatureExtraction(signature, templated);
        } catch (Exception e) {
            log.error("⚠️ 签名 JSON 解析失败: {}", e.getMessage());
            return SignatureExtraction.fallback(original);
        }
    }

    /**
     * 成功反思：AI 总结关键成功经验。
     *
     * @param userInput  用户原话
     * @param toolCalls  执行轨迹
     * @param aiResponse AI 最终回复
     * @return 成功经验（30字内）；AI 调用失败时返回 null
     */
    public String reflectSuccess(ModelRouter.Mode mode, String userInput, List<ToolCallLog> toolCalls, String aiResponse) {
        String stepsSummary = formatSteps(toolCalls);
        String prompt = promptLoader.getReflectSuccess()
                .formatted(userInput, stepsSummary, AiResponseUtils.truncate(aiResponse, 200));

        try {
            String result = client(mode).prompt().user(prompt).call().content();
            return AiResponseUtils.truncate(result, 100);
        } catch (Exception e) {
            log.error("⚠️ 成功反思失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 失败反思 + 归因：AI 分析失败原因，判断是计划问题还是环境问题。
     *
     * @param userInput    用户原话
     * @param toolCalls    执行轨迹（可能部分执行）
     * @param errorMessage 失败错误信息
     * @return 失败分析（教训 + 是否计划问题）；AI 调用失败时返回默认值（环境问题，不惩罚计划）
     */
    public FailureAnalysis reflectFailure(ModelRouter.Mode mode, String userInput, List<ToolCallLog> toolCalls, String errorMessage) {
        return reflectFailure(client(mode), userInput, toolCalls, errorMessage);
    }

    /** 使用指定 ChatClient 的失败反思（探索模式可传入云端客户端） */
    public FailureAnalysis reflectFailure(ChatClient client, String userInput, List<ToolCallLog> toolCalls, String errorMessage) {
        String stepsSummary = formatSteps(toolCalls);
        String prompt = promptLoader.getReflectFailure()
                .formatted(userInput, stepsSummary, AiResponseUtils.truncate(errorMessage, 300));

        try {
            String response = client.prompt().user(prompt).call().content();
            return parseFailureAnalysis(response);
        } catch (Exception e) {
            log.error("⚠️ 失败反思失败: {}", e.getMessage());
            // 默认当作环境问题（不惩罚计划）
            return new FailureAnalysis(null, false);
        }
    }

    /**
     * 解析失败分析的 JSON。
     */
    @SuppressWarnings("unchecked")
    private FailureAnalysis parseFailureAnalysis(String response) {
        if (response == null || response.isBlank()) {
            return new FailureAnalysis(null, false);
        }
        String json = AiResponseUtils.stripMarkdownCodeBlock(response);
        try {
            var parsed = objectMapper.readValue(json, java.util.Map.class);
            boolean isPlanIssue = Boolean.TRUE.equals(parsed.get("isPlanIssue"));
            String lesson = (String) parsed.getOrDefault("lesson", null);
            return new FailureAnalysis(AiResponseUtils.truncate(lesson, 100), isPlanIssue);
        } catch (Exception e) {
            log.error("⚠️ 失败分析 JSON 解析失败: {}", e.getMessage());
            return new FailureAnalysis(null, false);
        }
    }

    /** 格式化工具调用轨迹为简洁文本 */
    private static String formatSteps(List<ToolCallLog> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return "（无步骤记录）";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolCallLog step = toolCalls.get(i);
            sb.append(i + 1).append(". ")
              .append(step.toolName()).append("(").append(AiResponseUtils.truncate(step.args(), 100)).append(")")
              .append(step.success() ? " ✅" : " ❌").append("\n");
        }
        return sb.toString();
    }
}
