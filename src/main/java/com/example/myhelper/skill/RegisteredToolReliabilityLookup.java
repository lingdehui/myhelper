package com.example.myhelper.skill;

import com.example.myhelper.tools.ToolDirectory;
import com.example.myhelper.tools.ToolDirectory.Registration;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import com.example.myhelper.world.model.ExecutionStatistics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 将工具来源登记和同一实现的范围证据合成为可靠度样本。 */
public final class RegisteredToolReliabilityLookup implements ToolReliability.Lookup {
    /** 当前实现和范围没有配置时使用零权重先验。 */
    private static final ToolDirectory.Prior NO_PRIOR = new ToolDirectory.Prior(0, 0);
    /** 与执行入口使用同一工具绑定的目录快照。 */
    private final ToolDirectory directory;
    /** 提供已确认范围和真实统计。 */
    private final VerifiedEvidenceLookup evidenceLookup;

    public RegisteredToolReliabilityLookup(ToolDirectory directory, VerifiedEvidenceLookup evidenceLookup) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.evidenceLookup = Objects.requireNonNull(evidenceLookup, "evidenceLookup");
    }

    @Override
    public Optional<ToolReliability.Sample> findInVerifiedRange(String toolName, Map<String, Object> arguments) {
        Registration registration = directory.find(toolName).orElse(null);
        if (registration == null) {
            return Optional.empty();
        }
        return findInVerifiedRange(registration.ref(), arguments);
    }

    @Override
    public Optional<ToolReliability.Sample> findInVerifiedRange(ToolRef ref, Map<String, Object> arguments) {
        Registration tool = directory.find(ref.toolName()).orElse(null);
        if (tool == null || !tool.ref().equals(ref)) {
            return Optional.empty();
        }
        Optional<VerifiedEvidence> evidence = evidenceLookup.findInVerifiedRange(ref, arguments);
        if (evidence.isEmpty() || !ref.equals(evidence.get().ref())) {
            return Optional.empty();
        }
        return Optional.of(sample(tool, evidence.get()));
    }

    @Override
    public Map<String, ToolReliability.Sample> planningSamples(ToolRef ref) {
        Registration tool = directory.find(ref.toolName()).orElse(null);
        if (tool == null || !tool.ref().equals(ref)) {
            return Map.of();
        }
        Map<String, ToolReliability.Sample> samples = new LinkedHashMap<>();
        for (VerifiedEvidence evidence : evidenceLookup.planningEvidence(ref)) {
            if (ref.equals(evidence.ref())) {
                samples.put(evidence.verifiedRangeId(), sample(tool, evidence));
            }
        }
        return Collections.unmodifiableMap(samples);
    }

    private static ToolReliability.Sample sample(Registration tool, VerifiedEvidence verified) {
        ToolDirectory.Prior prior = tool.priorFor(verified.verifiedRangeId()).orElse(NO_PRIOR);
        return new ToolReliability.Sample(verified.statistics(), verified.externalFailures(),
                new ToolReliability.Prior(prior.successRate(), prior.weight()), tool.trustedAtomicTool());
    }

    public interface VerifiedEvidenceLookup {
        /** 按工具实现和完整参数查当前有效修订及已验证范围。需要读数据库时经统一存储入口。 */
        Optional<VerifiedEvidence> findInVerifiedRange(ToolRef ref, Map<String, Object> arguments);

        /** 只返回当前已批准范围的有界统计。 */
        default List<VerifiedEvidence> planningEvidence(ToolRef ref) {
            return List.of();
        }
    }

    public record VerifiedEvidence(
            /** 实际命中的工具实现身份。 */
            ToolRef ref,
            /** 当前有效修订和适用范围的实际统计。 */
            ExecutionStatistics statistics,
            /** 按执行身份去重的非方法失败数。 */
            long externalFailures,
            /** 已验证范围的稳定编号，用于读取同范围配置先验。 */
            String verifiedRangeId) {
    }
}
