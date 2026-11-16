package com.example.myhelper.capability;

import com.example.myhelper.gate.RuntimeGate;
import com.example.myhelper.llm.LlmTexts;
import java.util.Objects;

/** 兼容现有装配的固化适用性实现，只组合权威发布状态与已注入门禁。 */
public final class CheckedCapabilityUse implements CapabilityUsePort {
    /** 权威能力状态查询端口，缓存命中不代替执行资格。 */
    private final CapabilitySearchPort search;
    /** 现有业务门禁，具体规则由装配方提供。 */
    private final RuntimeGate checks;

    // 把原来分散在循环里的既有检查接成独立对象，不增加新的默认放行策略。
    public CheckedCapabilityUse(CapabilitySearchPort search, RuntimeGate checks) {
        // 查询端口未提供时不能擅自批准能力。
        this.search = Objects.requireNonNull(search, "search");
        // 门禁必须明确注入，不能因组件缺失改为全部允许。
        this.checks = Objects.requireNonNull(checks, "checks");
    }

    @Override
    public UseDecision evaluate(UseRequest request) {
        // 参数错误与明确拒绝不同，保持异常向上传递。
        Objects.requireNonNull(request, "request");
        // 保留先查发布状态、再过门禁的短路顺序，状态已拒绝时不继续调用门禁。
        boolean usable = search.isAvailable(request.ref())
                && checks.allow(request.goal(), request.arguments(), request.ref());
        // 允许只表示本次可执行，不包含执行结果验收或对未来请求的承诺。
        if (usable) {
            return new UseDecision(true, null);
        }
        // 使用已有集中反馈，不在这里猜测业务修复或补偿方式。
        return new UseDecision(false, LlmTexts.CAPABILITY_NOT_ALLOWED);
    }
}
