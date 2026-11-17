package com.example.myhelper.skill;

import com.example.myhelper.agent.AgentCaller;
import com.example.myhelper.gate.SkillWriteGate;
import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.tasklog.TaskLogPort;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 由闲时调度方调用。来源记录由任务记录模块保存。 */
public final class SkillConsolidationService {
    /** 单次模型调用入口。 */
    private final AgentCaller caller;
    /** 固化使用的模型。 */
    private final String modelRef;
    /** 候选、审核和发布的存储边界。 */
    private final Repository repository;
    /** 候选、审核和发布写入前的必填字段校验。 */
    private final SkillWriteGate writeGate = new SkillWriteGate();
    /** 候选描述的 JSON 协议。 */
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public SkillConsolidationService(AgentCaller caller, String modelRef, Repository repository) {
        this.caller = Objects.requireNonNull(caller);
        this.modelRef = required(modelRef);
        this.repository = Objects.requireNonNull(repository);
    }

    public Candidate generate(Evidence evidence) throws JsonProcessingException {
        Optional<Candidate> existing = repository.findCandidate(evidence.source());
        if (existing.isPresent()) {
            return existing.get();
        }
        var request = new AgentCaller.Request(modelRef, List.of(
                new SystemMessage(LlmTexts.SKILL_CONSOLIDATION),
                new UserMessage(json.writeValueAsString(evidence))),
                List.of(), AgentCaller.Mode.CALL_ONLY);
        // 固化从已封存的来源运行派生，本次模型调用拥有自己的调用身份。
        var logContext = new TaskLogPort.Context(null, evidence.source().rootRunId(), null, null, null, 0);
        var output = caller.call(request, List.of(), AgentCaller.Purpose.SKILL_CONSOLIDATION, logContext)
                .response().getResult().getOutput();
        if (!output.getToolCalls().isEmpty()) {
            throw new IllegalArgumentException("固化模型返回了工具调用");
        }
        Description description = json.readValue(output.getText(), Description.class);
        Candidate candidate = new Candidate(evidence, description);
        writeGate.checkCandidate(candidate);
        return repository.saveCandidateIfAbsent(candidate);
    }

    public void review(Candidate candidate, Review review) {
        writeGate.checkReview(candidate, review);
        repository.saveReview(candidate, review);
    }

    public Published publish(Candidate candidate) {
        writeGate.checkCandidate(candidate);
        return repository.publishApproved(candidate);
    }

    public interface Repository {
        Optional<Candidate> findCandidate(Source source);

        /** 按来源原子去重并返回实际保存的候选。 */
        Candidate saveCandidateIfAbsent(Candidate candidate);

        /** 核对候选完全一致，再保存独立审核决定。 */
        void saveReview(Candidate candidate, Review review);

        /** 核对同一候选已批准，索引就绪后返回版本，重复请求复用该版本。 */
        Published publishApproved(Candidate candidate);
    }

    public record Source(
            /** 根运行身份。 */
            String rootRunId,
            /** 已封闭快照的序号。 */
            long sequence,
            /** 已验收子图身份。 */
            String sliceId) {
        public Source {
            required(rootRunId);
            required(sliceId);
            if (sequence < 1) {
                throw new IllegalArgumentException("快照序号必须为正数");
            }
        }
    }

    public record Evidence(
            /** 固定来源。 */
            Source source,
            /** 权威记录提供的验收证据引用。 */
            String acceptanceRef,
            /** 证据覆盖的范围。 */
            String verifiedScope,
            /** 提取方已映射好参数和依赖的计划。 */
            TaskPlan plan) {
        public Evidence {
            Objects.requireNonNull(source);
            required(acceptanceRef);
            required(verifiedScope);
            Objects.requireNonNull(plan);
        }
    }

    public record Description(
            /** 建议名称。 */
            String name,
            /** 技能摘要。 */
            String summary,
            /** 已知限制。 */
            String limitations) {
        public Description {
            required(name);
            required(summary);
            required(limitations);
        }
    }

    public record Candidate(
            /** 验收依据及固定计划。 */
            Evidence evidence,
            /** 待审核的模型描述。 */
            Description description) {
        public Candidate {
            Objects.requireNonNull(evidence);
            Objects.requireNonNull(description);
        }
    }

    public record Review(
            /** 独立审核员身份。 */
            String reviewer,
            /** 审核结论。 */
            boolean approved,
            /** 审核依据。 */
            String reason) {
        public Review {
            required(reviewer);
            required(reason);
        }
    }

    public record Published(
            /** 已发布的 Unit 身份。 */
            String unitId,
            /** 已发布修订。 */
            long revision) {
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("固化字段不能为空");
        }
        return value;
    }
}
