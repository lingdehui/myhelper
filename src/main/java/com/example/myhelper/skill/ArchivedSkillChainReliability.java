package com.example.myhelper.skill;

import com.example.myhelper.orchestration.ExecutionTraceStore.Node;
import com.example.myhelper.orchestration.ExecutionTraceStore.NodeKind;
import com.example.myhelper.orchestration.ExecutionTraceStore.NodeStatus;
import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.records.RunArchivePort.ArchivedRun;
import com.example.myhelper.records.TaskRecordPort.EventKind;
import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.skill.RecordedToolReliability.Example;
import com.example.myhelper.skill.RecordedToolReliability.RangeCheck;
import com.example.myhelper.skill.RecordedToolReliability.RangeVerdict;
import com.example.myhelper.skill.SkillChainExecutionStore.ChainRef;
import com.example.myhelper.skill.SkillChainExecutionStore.Fact;
import com.example.myhelper.skill.SkillChainExecutionStore.Range;
import com.example.myhelper.world.model.FailureCategory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/** 从已验收的固化链执行事实计算精确修订及批准范围内的可靠度。 */
public final class ArchivedSkillChainReliability implements SkillChainReliability {
    /** 给范围模型的成功事实示例数上限。 */
    private static final int MAX_EXAMPLES = 8;
    /** 发布状态、批准范围及实际运行事实共用的存储。 */
    private final SkillChainExecutionStore store;
    /** 当前能力查询的命名空间，历史归档仅消费同一权威来源。 */
    private final String namespace;
    /** 复用已有的批准范围判断。 */
    private final RangeCheck rangeCheck;

    public ArchivedSkillChainReliability(DualWriteStore storage, DualWriteStore.Database authority,
                                 String namespace, RangeCheck rangeCheck) {
        this.store = new SkillChainExecutionStore(storage, authority);
        TaskPlan.requireText(namespace, "namespace");
        this.namespace = namespace;
        this.rangeCheck = Objects.requireNonNull(rangeCheck);
    }

    /** 无批准范围、未匹配或缺有效样本时无分数。评分不更新统计或发布实体。 */
    @Override
    public OptionalDouble score(String skillId, long revision, Map<String, Object> arguments) {
        ChainRef chain = new ChainRef(skillId, revision);
        Map<String, Object> input = TaskPlan.immutableMap(Objects.requireNonNull(arguments, "arguments"));
        Range range = store.approvedRange(chain).orElse(null);
        if (range == null) {
            return OptionalDouble.empty();
        }
        Counts counts = new Counts(store.canonical(input));
        store.forEach(chain, range, counts);
        double total = (double) counts.successes + counts.methodFailures;
        if (total == 0) {
            return OptionalDouble.empty();
        }
        if (!counts.knownArguments
                && rangeCheck.verdict(input, range.constraints(), List.copyOf(counts.examples)) != RangeVerdict.MATCH) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(counts.successes / total);
    }

    /** 只消费完整的已提交用户归档。重复消费复用同一次链执行的事实。 */
    public void record(ArchivedRun run) {
        if (run.internalValidation() || !run.graphComplete() || !run.evidenceComplete()) {
            return;
        }
        Map<String, ExecutionEvent> verifications = new LinkedHashMap<>();
        for (ExecutionEvent event : run.events()) {
            if (event.kind() != EventKind.VERIFIED || event.nodeId() == null) {
                continue;
            }
            Node node = run.graph().nodes().get(event.nodeId());
            if (node == null || node.kind() != NodeKind.UNIT) {
                continue;
            }
            ExecutionEvent previous = verifications.putIfAbsent(node.id(), event);
            if (previous != null && !previous.equals(event)) {
                throw new IllegalStateException("同一次固化链执行包含多份验收事实");
            }
        }
        for (var entry : verifications.entrySet()) {
            Node node = run.graph().nodes().get(entry.getKey());
            Fact fact = acceptedFact(node, entry.getValue());
            if (fact != null && store.record(fact).isEmpty()) {
                associateApprovedRange(fact);
            }
        }
    }

    private Fact acceptedFact(Node node, ExecutionEvent event) {
        if (!namespace.equals(node.input().get("namespace"))
                || !(node.input().get("revision") instanceof Number revision)
                || !(node.input().get("arguments") instanceof Map<?, ?> input)
                || !node.runId().equals(event.runId()) || node.planRevision() != event.planRevision()
                || !(event.details().get("outcome") instanceof Map<?, ?> outcome)) {
            return null;
        }
        boolean verified = Boolean.TRUE.equals(event.details().get("performed"));
        Object counted = event.details().get("chainAttemptCounted");
        if (Boolean.FALSE.equals(counted)) {
            return null;
        }
        boolean success = verified && node.status() == NodeStatus.SUCCESS && "SUCCESS".equals(outcome.get("status"));
        boolean failure = (verified || Boolean.TRUE.equals(counted)) && node.status() == NodeStatus.FAILED
                && "FAILED".equals(outcome.get("status"));
        if (!success && !failure) {
            return null;
        }
        Map<String, Object> arguments = copyArguments(input);
        if (!store.canonical(arguments).equals(store.canonical(event.details().get("arguments")))) {
            throw new IllegalStateException("固化链验收输入与实际绑定的参数不一致");
        }
        ChainRef chain = new ChainRef(node.goal(), revision.longValue());
        FailureCategory category = failure ? failureCategory(event) : null;
        String error = outcome.get("error") instanceof String detail ? detail : null;
        return new Fact(chain, event.taskId(), event.rootRunId(), node.id(), event.eventId(),
                arguments, success, error, category);
    }

    private void associateApprovedRange(Fact fact) {
        Range range = store.approvedRange(fact.chain()).orElse(null);
        if (range != null && rangeCheck.verdict(fact.arguments(), range.constraints(), List.of()) == RangeVerdict.MATCH) {
            store.associate(fact, range);
        }
    }

    private static FailureCategory failureCategory(ExecutionEvent event) {
        if (event.details().get("failureCategory") instanceof String category) {
            return FailureCategory.valueOf(category);
        }
        // 旧归档只有明确的验收失败结论，保留未知归因。
        return FailureCategory.UNKNOWN;
    }

    private static Map<String, Object> copyArguments(Map<?, ?> input) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (var entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String field)) {
                throw new IllegalStateException("固化链验收参数键须为字符串");
            }
            arguments.put(field, entry.getValue());
        }
        return arguments;
    }

    private final class Counts implements Consumer<Fact> {
        /** 已绑定的完整参数 JSON。 */
        private final String requested;
        /** 同范围实际成功的参数及验收依据。 */
        private final List<Example> examples = new ArrayList<>();
        /** 同范围实际验收成功次数。 */
        private long successes;
        /** 同范围失败次数，已确认的环境失败排除。 */
        private long methodFailures;
        /** 相同完整参数已经获得同范围证据。 */
        private boolean knownArguments;

        private Counts(String requested) {
            this.requested = requested;
        }

        @Override
        public void accept(Fact fact) {
            knownArguments |= requested.equals(store.canonical(fact.arguments()));
            if (fact.success()) {
                successes = Math.incrementExact(successes);
                if (examples.size() < MAX_EXAMPLES) {
                    examples.add(new Example(fact.arguments(), fact.rootRunId() + ":" + fact.evidenceId()));
                }
            } else if (fact.failureCategory() != FailureCategory.ENVIRONMENT) {
                methodFailures = Math.incrementExact(methodFailures);
            }
        }
    }
}
