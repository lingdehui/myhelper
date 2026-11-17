package com.example.myhelper.records;

import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.world.model.ContextUnitType;
import com.example.myhelper.world.model.WorldEntity;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单任务聚合编解码；无数据库访问，也不决定任务的恢复资格。 */
final class RecordCodec {
    /** 此模块私有映射器，不修改应用全局配置。 */
    private final ObjectMapper json;

    RecordCodec() {
        SimpleModule times = new SimpleModule();
        times.addSerializer(Instant.class, new JsonSerializer<Instant>() {
            @Override
            public void serialize(Instant value, JsonGenerator output, SerializerProvider provider) throws IOException {
                output.writeString(value.toString());
            }
        });
        times.addDeserializer(Instant.class, new JsonDeserializer<Instant>() {
            @Override
            public Instant deserialize(JsonParser input, DeserializationContext context) throws IOException {
                return Instant.parse(input.getValueAsString());
            }
        });
        json = new ObjectMapper().registerModule(times);
    }

    RecordState decode(WorldEntity entity) {
        Object schema = entity.data().get("schema");
        if (!RecordState.ENTITY_KIND.equals(entity.data().get("kind")) || !(schema instanceof Number number)
                || number.doubleValue() != RecordState.SCHEMA) {
            throw new IllegalStateException(LlmTexts.RECORDS_SCHEMA_INCOMPATIBLE);
        }
        RecordState state = json.convertValue(entity.data().get("state"), RecordState.class);
        if (!entity.id().equals(state.taskId) || state.task == null) {
            throw new IllegalStateException(LlmTexts.RECORDS_IDENTITY_CORRUPT);
        }
        // 兼容早期 schema 1；首次后续写入时会补上明确的事件顺序数组。
        if (state.eventOrder.isEmpty() && !state.events.isEmpty()) {
            state.eventOrder.addAll(state.events.keySet());
        }
        Map<String, ExecutionEvent> ordered = new LinkedHashMap<>();
        for (String eventId : state.eventOrder) {
            ExecutionEvent event = state.events.get(eventId);
            if (event == null || ordered.put(eventId, event) != null) {
                throw new IllegalStateException(LlmTexts.RECORDS_EVENT_ORDER_CORRUPT);
            }
        }
        if (ordered.size() != state.events.size()) {
            throw new IllegalStateException(LlmTexts.RECORDS_EVENT_ORDER_INCOMPLETE);
        }
        state.events = ordered;
        return state;
    }

    @SuppressWarnings("unchecked")
    WorldEntity entity(RecordState state, WorldEntity old, boolean recoverable) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", RecordState.ENTITY_KIND);
        body.put("schema", RecordState.SCHEMA);
        body.put("state", json.convertValue(state, Map.class));
        body.put("recoverable", recoverable);
        body.put("hasPendingConsolidation", !state.pendingArchives.isEmpty());
        body.put("hasActiveRun", state.active != null);
        body.put("activeOwner", state.activeOwner);
        Instant now = Instant.now();
        return new WorldEntity(state.taskId, null, ContextUnitType.OBSERVATION, null, null,
                state.task.goal(), null, TaskPlan.immutableMap(body), List.of(), null,
                old == null ? 1 : Math.incrementExact(old.revision()), "records",
                List.of(), Map.of("module", RecordState.ENTITY_KIND), old == null ? now : old.createdAt(), now);
    }

    boolean same(Object left, Object right) {
        try {
            // JSON 归一化 Integer/Long；对象键顺序不参与相等性判断。
            return json.readTree(encode(left)).equals(json.readTree(encode(right)));
        } catch (IOException error) {
            throw new IllegalArgumentException(LlmTexts.RECORDS_COMPARE_FAILED, error);
        }
    }

    String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException error) {
            throw new IllegalArgumentException(LlmTexts.RECORDS_ENCODE_FAILED, error);
        }
    }
}
