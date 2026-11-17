package com.example.myhelper.interaction.desktop.behavior;

import com.example.myhelper.agent.AgentCaller;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Action;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Color;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Context;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Pattern;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Pose;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Prop;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Slot;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Style;
import com.example.myhelper.interaction.desktop.behavior.BehaviorPlan.Type;
import com.example.myhelper.llm.LlmTexts;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** 通过既有单次调用入口生成纯数据动作，并在后台完成整段验证。 */
public final class ModelBehaviorPlanner implements BehaviorPlanner {
    /** 解析前限制文本大小，避免空闲响应占用过多时间。 */
    private static final int MAX_RESPONSE_LENGTH = 4096;
    /** 模型输出只接受一个标准 JSON 对象及唯一字段名。 */
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(6).maxStringLength(128).maxNumberLength(10).build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
            .build();
    /** 本地时长及数量限制同时用于提示和校验。 */
    private static final String INSTRUCTIONS = LlmTexts.DESKTOP_BEHAVIOR_PLAN.formatted(
            BehaviorPlan.MAX_ACTIONS, BehaviorPlan.MAX_PLAN_DURATION_MILLIS);

    /** 通用的单次模型调用入口。 */
    private final AgentCaller caller;
    /** 由运行配置解析的模型引用。 */
    private final String modelRef;

    public ModelBehaviorPlanner(AgentCaller caller, String modelRef) {
        this.caller = Objects.requireNonNull(caller);
        if (modelRef == null || modelRef.isBlank()) {
            throw new IllegalArgumentException("短期行为的模型引用不能为空");
        }
        this.modelRef = modelRef;
    }

    @Override
    public BehaviorPlan plan(Context context, BooleanSupplier permit) {
        try {
            var request = new AgentCaller.Request(modelRef, List.of(
                    new SystemMessage(INSTRUCTIONS), new UserMessage(JSON.writeValueAsString(context))),
                    List.of(), AgentCaller.Mode.CALL_ONLY);
            var result = caller.callIfPermitted(request, AgentCaller.Purpose.DESKTOP_BEHAVIOR, permit);
            if (result.isEmpty()) {
                return null;
            }
            return parse(result.get().response().getResult().getOutput().getText(), context);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("短期行为 JSON 无效", failure);
        }
    }

    private static BehaviorPlan parse(String text, Context context) throws JsonProcessingException {
        if (text == null || text.length() > MAX_RESPONSE_LENGTH) {
            throw new IllegalArgumentException("短期行为响应缺失或过长");
        }
        JsonNode root = JSON.readTree(text);
        requireFields(root, Set.of("actions"), Set.of());
        JsonNode actions = root.get("actions");
        if (!actions.isArray() || actions.isEmpty() || actions.size() > BehaviorPlan.MAX_ACTIONS) {
            throw new IllegalArgumentException("短期行为动作列表无效");
        }
        List<Action> decoded = new ArrayList<>(actions.size());
        for (JsonNode action : actions) {
            decoded.add(parseAction(action));
        }
        BehaviorPlan plan = new BehaviorPlan(decoded);
        plan.validate(context);
        return plan;
    }

    private static Action parseAction(JsonNode node) {
        Type type = enumField(Type.class, node.get("type"));
        JsonNode duration = node.get("durationMs");
        if (duration == null || !duration.isIntegralNumber() || !duration.canConvertToInt()) {
            throw new IllegalArgumentException("短期行为动作时长须为整数");
        }
        int durationMs = duration.intValue();
        return switch (type) {
            case POSE -> {
                requireFields(node, Set.of("type", "pose", "durationMs"), Set.of());
                yield new Action(type, enumField(Pose.class, node.get("pose")), null, null, durationMs);
            }
            case CREATE_PROP -> {
                requireFields(node, Set.of("type", "slot", "style", "color", "durationMs"), Set.of("pattern"));
                Pattern pattern = node.has("pattern") ? enumField(Pattern.class, node.get("pattern")) : Pattern.PLAIN;
                Prop prop = new Prop(enumField(Style.class, node.get("style")), enumField(Color.class, node.get("color")),
                        pattern);
                yield new Action(type, null, enumField(Slot.class, node.get("slot")), prop, durationMs);
            }
            case MOVE_TO_ANCHOR, LOOK_AT_SLOT, SHOW_PROP, HIDE_PROP -> {
                requireFields(node, Set.of("type", "slot", "durationMs"), Set.of());
                yield new Action(type, null, enumField(Slot.class, node.get("slot")), null, durationMs);
            }
        };
    }

    private static void requireFields(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("短期行为 JSON 需要对象");
        }
        for (String field : required) {
            if (!node.hasNonNull(field)) {
                throw new IllegalArgumentException("短期行为 JSON 缺少必要字段");
            }
        }
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!required.contains(field) && !optional.contains(field)) {
                throw new IllegalArgumentException("短期行为 JSON 包含未允许的字段");
            }
        }
    }

    private static <E extends Enum<E>> E enumField(Class<E> type, JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException("短期行为枚举字段须为字符串");
        }
        return Enum.valueOf(type, node.textValue());
    }
}
