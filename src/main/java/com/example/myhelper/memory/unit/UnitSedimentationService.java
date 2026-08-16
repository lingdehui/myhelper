package com.example.myhelper.memory.unit;

import com.example.myhelper.common.AiResponseUtils;
import com.example.myhelper.config.ModelRouter;
import com.example.myhelper.memory.vector.episode.ReflectService;
import com.example.myhelper.memory.vector.episode.ToolCallLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 成功沉淀服务（文档 15 v1.7 §7 / §12）。
 *
 * <p>把一次成功的执行轨迹异步生成一个 PLAN_STEP Unit 落库（Neo4j + Qdrant 双写）。
 * 复用现有 {@link ReflectService#extractSignature} 做 args 模板化（具体值→$var），
 * 复用 {@code successLesson} 作为 Unit 的 notes。差异更新：目标相同时只 successCount+1，不重复建 Unit。</p>
 *
 * <p>生成在线程池 planGenerationPool 中执行，失败直接丢弃（不落库）。</p>
 */
@Service
public class UnitSedimentationService {

    private static final Logger log = LoggerFactory.getLogger(UnitSedimentationService.class);

    private final ExecutorService planGenerationPool;
    private final UnitStore unitStore;
    private final ReflectService reflectService;

    public UnitSedimentationService(@Qualifier("planGenerationPool") ExecutorService planGenerationPool,
                                    UnitStore unitStore,
                                    ReflectService reflectService) {
        this.planGenerationPool = planGenerationPool;
        this.unitStore = unitStore;
        this.reflectService = reflectService;
    }

    /**
     * 异步沉淀一个成功执行轨迹为 Unit。
     *
     * @param mode          模型模式（NORMAL / EXPLORATION）
     * @param userInput     用户原话（作为 Unit goal / matchText）
     * @param toolCalls     本次成功执行的完整轨迹
     * @param successLesson 已由调用方算出的成功经验（可空），避免重复调用 AI
     * @return CompletableFuture，true 表示本次新建/变更了 PLAN_STEP Unit（需要触发分类重同步）
     */
    public CompletableFuture<Boolean> sediment(ModelRouter.Mode mode, String userInput,
                                               List<ToolCallLog> toolCalls, String successLesson) {
        if (toolCalls == null || toolCalls.isEmpty()) return CompletableFuture.completedFuture(false);

        List<ToolCallLog> successful = toolCalls.stream()
                .filter(tc -> tc.success()
                        && !"exploration_step".equals(tc.toolName())
                        && !tc.toolName().startsWith("planStep_")
                        && !AiResponseUtils.isMetaTool(tc.toolName()))
                .toList();
        if (successful.isEmpty()) return CompletableFuture.completedFuture(false);

        return CompletableFuture.supplyAsync(() -> {
            try {
                // 复用签名提取：args 模板化（"张三"→"$contact"）
                ReflectService.SignatureExtraction extraction =
                        reflectService.extractSignature(mode, userInput, successful);
                List<ToolCallLog> script = extraction.templatedToolCalls();
                Map<String, String> signature = extraction.signature();
                if (script.isEmpty()) return false;

                List<String> notes = (successLesson != null && !successLesson.isBlank())
                        ? List.of(successLesson) : List.of();

                // 差异检测与合并（§12）：
                //   - 完全相同 → 仅 successCount+1
                //   - 某步不同 → notes 写差异 + 建 FALLBACK 变体
                //   - 差异较大 → 新建
                List<Unit> similar = unitStore.findSimilar(userInput, 3);
                for (Unit u : similar) {
                    if (u.unitKind() != UnitKind.PLAN_STEP || !sameGoal(u.goal(), userInput)) continue;
                    if (sameScript(u.script(), script)) {
                        unitStore.incrementSuccess(u.unitId());
                        log.info("🔄 Unit 差异更新: 完全相同，复用 {}（{} 步）", shortId(u.unitId()), script.size());
                        return false;
                    }
                    if (diffOneStep(u.script(), script)) {
                        unitStore.appendNote(u.unitId(), "发现变体：" + diffSummary(u.script(), script));
                        String variantId = buildUnit(script, userInput, notes, signature);
                        unitStore.linkFallback(u.unitId(), variantId, null);
                        log.info("🔀 Unit 差异更新: 某步不同 → notes + FALLBACK {}→{}",
                                shortId(u.unitId()), shortId(variantId));
                        return true;
                    }
                    // 差异较大 → 落到新建分支
                }

                String unitId = buildUnit(script, userInput, notes, signature);
                log.info("📗 Unit 成功沉淀: {}（{} 步）", shortId(unitId), script.size());
                return true;
            } catch (Exception e) {
                log.warn("⚠️ 生成中的 Unit 丢弃: {}", e.getMessage());
                return false;
            }
        }, planGenerationPool);
    }

    /** 失败处理 §6：把失败前已成功的前 N-1 步沉淀为可复用 PLAN_STEP。 */
    public CompletableFuture<Boolean> sedimentSalvageable(ModelRouter.Mode mode, String userInput, List<ToolCallLog> successfulPrefix) {
        if (successfulPrefix == null || successfulPrefix.isEmpty()) return CompletableFuture.completedFuture(false);
        return sediment(mode, userInput, successfulPrefix, "从失败执行中提取的可复用成功步骤");
    }

    /** 创建 PLAN_STEP Unit，并把内部工具注册为 TOOL Unit + CONTAINS 挂接（§7.1/§12）。 */
    private String buildUnit(List<ToolCallLog> script, String userInput,
                             List<String> notes, Map<String, String> signature) {
        String unitId = UUID.randomUUID().toString();
        // §7.2 脚本化判定：无递归子步骤（均为叶子工具） + 无数据流依赖 + 步骤数 ≤ 5
        boolean scriptable = script.size() <= 5 && !hasDataFlowDependency(script);
        // 剥离工具清单噪声：只存「学习目标」作为 matchText/goal，与检索侧 ToolPlanner 保持一致
        String learningGoal = AiResponseUtils.extractLearningGoal(userInput);
        Unit unit = new Unit(
                unitId,
                UnitKind.PLAN_STEP,
                Unit.buildMatchText(learningGoal, null),
                learningGoal,
                null,
                notes,
                signature,
                Map.of(),
                null,
                scriptable,
                script,
                1,
                0,
                Unit.calcStability(1, 0),
                List.of(),
                List.of(),
                Unit.UnitStatus.ACTIVE);
        unitStore.save(unit);

        // 新工具先注册为 TOOL Unit，再通过 CONTAINS 挂接（§12 第5步）
        int order = 1;
        for (ToolCallLog step : script) {
            String toolUnitId = registerToolUnit(step.toolName());
            if (toolUnitId != null) {
                unitStore.linkContains(unitId, toolUnitId, order++);
            }
        }
        return unitId;
    }

    /** 复用/新建叶子 TOOL Unit。 */
    private String registerToolUnit(String toolName) {
        if (toolName == null || toolName.isBlank()) return null;
        Optional<Unit> existing = unitStore.findByToolName(toolName);
        if (existing.isPresent()) return existing.get().unitId();
        String id = UUID.randomUUID().toString();
        Unit tool = new Unit(
                id, UnitKind.TOOL, toolName, toolName, null,
                List.of(), Map.of(), Map.of(), toolName,
                false, List.of(), 0, 0, 0.0,
                List.of(), List.of(), Unit.UnitStatus.ACTIVE);
        unitStore.save(tool);
        return id;
    }

    private boolean sameScript(List<ToolCallLog> a, List<ToolCallLog> b) {
        if (a == null || b == null) return a == b;
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!stepKey(a.get(i)).equals(stepKey(b.get(i)))) return false;
        }
        return true;
    }

    private boolean diffOneStep(List<ToolCallLog> a, List<ToolCallLog> b) {
        if (a == null || b == null) return false;
        if (a.size() != b.size()) return false;
        int diff = 0;
        for (int i = 0; i < a.size(); i++) {
            if (!stepKey(a.get(i)).equals(stepKey(b.get(i)))) diff++;
        }
        return diff == 1;
    }

    private String diffSummary(List<ToolCallLog> a, List<ToolCallLog> b) {
        if (a == null || b == null) return "";
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            String ka = i < a.size() ? stepKey(a.get(i)) : "-";
            String kb = i < b.size() ? stepKey(b.get(i)) : "-";
            if (!ka.equals(kb)) return "步骤" + (i + 1) + ": " + ka + " → " + kb;
        }
        return "";
    }

    private String stepKey(ToolCallLog tc) {
        return tc.toolName() + "(" + (tc.args() == null ? "" : tc.args()) + ")";
    }

    /** §7.2 条件2：是否含数据流依赖（引用 $stepName.varName 形式的前序输出）。 */
    private boolean hasDataFlowDependency(List<ToolCallLog> script) {
        if (script == null) return false;
        for (ToolCallLog step : script) {
            String args = step.args();
            if (args != null && args.matches("(?s).*\\$[A-Za-z_]\\w*\\.[A-Za-z_]\\w*.*")) {
                return true;
            }
        }
        return false;
    }

    private boolean sameGoal(String a, String b) {
        return a != null && b != null
                && AiResponseUtils.normalizeKey(a).equals(AiResponseUtils.normalizeKey(b));
    }

    private String shortId(String unitId) {
        return unitId == null ? "null" : unitId.substring(0, Math.min(8, unitId.length())) + "...";
    }
}
