package com.example.myhelper.skill;

import com.example.myhelper.agent.AgentCaller;
import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.skill.RecordedToolReliability.RangeVerdict;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 单次无工具调用，只判断参数是否符合既有验证范围。 */
public final class ModelToolRangeCheck implements RecordedToolReliability.RangeCheck {
    /** 单次模型调用入口。 */
    private final AgentCaller caller;
    /** 用于范围判断的模型引用。 */
    private final String modelRef;
    /** 严格读取单个 JSON 结果。 */
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);

    public ModelToolRangeCheck(AgentCaller caller, String modelRef) {
        this.caller = Objects.requireNonNull(caller);
        if (modelRef == null || modelRef.isBlank()) {
            throw new IllegalArgumentException("范围判断模型引用不能为空");
        }
        this.modelRef = modelRef;
    }

    @Override
    public boolean matches(Map<String, Object> arguments, Map<String, Object> verifiedRange,
                           List<RecordedToolReliability.Example> examples) {
        return verdict(arguments, verifiedRange, examples) == RangeVerdict.MATCH;
    }

    @Override
    public RangeVerdict verdict(Map<String, Object> arguments, Map<String, Object> verifiedRange,
                                List<RecordedToolReliability.Example> examples) {
        try {
            String input = json.writeValueAsString(Map.of(
                    "arguments", arguments, "verifiedRange", verifiedRange, "examples", examples));
            var request = new AgentCaller.Request(modelRef, List.of(
                    new SystemMessage(LlmTexts.TOOL_PARAMETER_RANGE_CHECK), new UserMessage(input)),
                    List.of(), AgentCaller.Mode.CALL_ONLY);
            var output = caller.call(request).response().getResult().getOutput();
            if (!output.getToolCalls().isEmpty()) {
                return RangeVerdict.UNKNOWN;
            }
            RangeVerdict verdict = json.readValue(output.getText(), RangeVerdict.class);
            return verdict == null ? RangeVerdict.UNKNOWN : verdict;
        } catch (RuntimeException | JsonProcessingException failure) {
            // 此处只捕获模型与响应解析失败。数据库查询和实际样本保存由外层完成。
            return RangeVerdict.UNKNOWN;
        }
    }
}
