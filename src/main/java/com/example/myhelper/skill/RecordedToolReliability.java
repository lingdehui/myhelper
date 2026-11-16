package com.example.myhelper.skill;

import com.example.myhelper.skill.RegisteredToolReliabilityLookup.VerifiedEvidence;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import com.example.myhelper.world.model.ExecutionStatistics;
import com.example.myhelper.world.model.FailureCategory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 读取与当前实现绑定的参数范围和实际执行事实。 */
public final class RecordedToolReliability implements RegisteredToolReliabilityLookup.VerifiedEvidenceLookup {
    /** 传给范围模型的每个范围的最大样本数。 */
    private static final int MAX_MODEL_EXAMPLES = 8;
    /** 参数及验收事实的权威来源。 */
    private final ToolParameterStore parameters;
    /** 已批准范围的语义判断。 */
    private final RangeCheck rangeCheck;

    public RecordedToolReliability(ToolParameterStore parameters, RangeCheck rangeCheck) {
        this.parameters = Objects.requireNonNull(parameters);
        this.rangeCheck = Objects.requireNonNull(rangeCheck);
    }

    @Override
    public Optional<VerifiedEvidence> findInVerifiedRange(ToolRef ref, Map<String, Object> arguments) {
        List<ToolParameterStore.ApprovedRange> approved = parameters.approvedRanges(ref);
        String requestedKey = parameters.exactRangeId(arguments);
        Map<String, Observations> observed = observe(ref, approved, requestedKey);

        VerifiedEvidence exact = null;
        for (var entry : observed.entrySet()) {
            if (entry.getValue().exactSuccess) {
                if (exact != null) {
                    return Optional.empty();
                }
                exact = entry.getValue().evidence(ref, entry.getKey());
            }
        }
        if (exact != null) {
            return Optional.of(exact);
        }

        RangeSelection selection = selectApprovedRange(arguments, approved, observed);
        if (selection.rangeId().isEmpty()) {
            return Optional.empty();
        }
        String id = selection.rangeId().get();
        return Optional.of(observed.getOrDefault(id, new Observations()).evidence(ref, id));
    }

    /** 按已保存的批准顺序选范围。用于归档关联，不把刚保存的事实当作某个范围的证据。 */
    public RangeSelection selectApprovedRange(ToolRef ref, Map<String, Object> arguments) {
        List<ToolParameterStore.ApprovedRange> approved = parameters.approvedRanges(ref);
        if (approved.isEmpty()) {
            return new RangeSelection(RangeVerdict.NO_MATCH, Optional.empty());
        }
        return selectApprovedRange(arguments, approved, observe(ref, approved, null));
    }

    private Map<String, Observations> observe(ToolRef ref, List<ToolParameterStore.ApprovedRange> approved,
                                              String requestedKey) {
        Map<String, Observations> observed = new LinkedHashMap<>();
        parameters.forEach(ref, approved, new java.util.function.Consumer<ToolParameterStore.Execution>() {
            @Override
            public void accept(ToolParameterStore.Execution execution) {
                String id = execution.scope().rangeId();
                Observations scope = observed.get(id);
                if (scope == null) {
                    scope = new Observations();
                    observed.put(id, scope);
                }
                boolean exact = requestedKey != null && requestedKey.equals(parameters.exactRangeId(execution.arguments()));
                scope.accept(exact, execution);
            }
        });
        return observed;
    }

    private RangeSelection selectApprovedRange(Map<String, Object> arguments, List<ToolParameterStore.ApprovedRange> approved,
                                               Map<String, Observations> observed) {
        RangeVerdict unselected = RangeVerdict.NO_MATCH;
        for (var range : approved) {
            Observations scope = observed.getOrDefault(range.id(), new Observations());
            RangeVerdict verdict = rangeCheck.verdict(arguments, range.constraints(), List.copyOf(scope.examples));
            if (verdict == RangeVerdict.MATCH) {
                return new RangeSelection(verdict, Optional.of(range.id()));
            }
            if (verdict == RangeVerdict.UNKNOWN) {
                unselected = RangeVerdict.UNKNOWN;
            }
        }
        return new RangeSelection(unselected, Optional.empty());
    }

    public interface RangeCheck {
        /** 兼容只提供确定性匹配的实现。 */
        boolean matches(Map<String, Object> arguments, Map<String, Object> verifiedRange, List<Example> examples);

        /** 生产模型实现保留明确不匹配与无法判断的区别。 */
        default RangeVerdict verdict(Map<String, Object> arguments, Map<String, Object> verifiedRange,
                                     List<Example> examples) {
            return matches(arguments, verifiedRange, examples) ? RangeVerdict.MATCH : RangeVerdict.NO_MATCH;
        }
    }

    public enum RangeVerdict {
        MATCH, NO_MATCH, UNKNOWN
    }

    public record RangeSelection(
            /** 首个明确匹配、明确无匹配或仍有范围无法判断。 */
            RangeVerdict verdict,
            /** 只有明确匹配时才提供范围编号。 */
            Optional<String> rangeId) {
    }

    public record Example(
            /** 实际成功执行的完整参数。 */
            Map<String, Object> arguments,
            /** 权威执行与验收记录出处。 */
            String source) {
    }

    private static final class Observations {
        /** 供模型读取的有限成功样本。 */
        private final List<Example> examples = new ArrayList<>();
        /** 同版本同范围的实际统计。 */
        private ExecutionStatistics statistics = ExecutionStatistics.empty();
        /** 确认环境失败的执行数。一次执行只保存一条观测。 */
        private long externalFailures;
        /** 是否存在相同的已验收完整参数。 */
        private boolean exactSuccess;

        private void accept(boolean exactArguments, ToolParameterStore.Execution execution) {
            switch (execution.outcome()) {
                case SUCCESS -> {
                    statistics = SkillUseStatistics.success(statistics);
                    exactSuccess |= exactArguments;
                    if (examples.size() < MAX_MODEL_EXAMPLES) {
                        examples.add(new Example(execution.arguments(), execution.source()));
                    }
                }
                case FAILURE -> {
                    statistics = SkillUseStatistics.failure(statistics, execution.failureCause());
                    if (execution.failureCause().category() == FailureCategory.ENVIRONMENT) {
                        externalFailures = Math.incrementExact(externalFailures);
                    }
                }
                case UNKNOWN -> { }
            }
        }

        private VerifiedEvidence evidence(ToolRef ref, String rangeId) {
            return new VerifiedEvidence(ref, statistics, externalFailures, rangeId);
        }
    }
}
