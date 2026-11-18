package com.example.myhelper.tools;

import com.example.myhelper.gate.DeviceReadGate;
import com.example.myhelper.gate.RuntimeGate.ToolCall;
import com.example.myhelper.llm.LlmTexts;
import com.example.myhelper.orchestration.AgentOrchestrator.CallContext;
import com.example.myhelper.tools.ToolDirectory.Binding;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import com.example.myhelper.world.WorldDeviceStatus;
import com.example.myhelper.world.WorldState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 按宿主登记读取空调模式，并把真实回执交给世界状态复查。 */
public final class DeviceStateTool implements ToolCallback {
    /** 首版支持的世界状态属性。 */
    private static final String POWER = "power";
    /** 回执包含可用于复查的观测。 */
    private static final String OBSERVED = "OBSERVED";
    /** 回执没有可确认的观测。 */
    private static final String UNAVAILABLE = "UNAVAILABLE";
    /** JSON 工具协议。 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    /** 宿主给实际回调绑定的固定版本。 */
    private final ToolRef ref;
    /** 同一来源在世界模型里的固定感知工具修订。 */
    private final WorldDeviceStatus.Sensor sensor;
    /** 世界设备 ID 到此接入实例内的原生实体映射。 */
    private final Map<String, String> entities;
    /** 已配置来源的只读客户端。 */
    private final Reader reader;
    /** 模型看到的回调合同。 */
    private final ToolDefinition definition;

    public DeviceStateTool(ToolRef ref, WorldDeviceStatus.Sensor sensor, Collection<Mapping> mappings, Reader reader) {
        this.ref = Objects.requireNonNull(ref, "ref");
        this.sensor = Objects.requireNonNull(sensor, "sensor");
        this.reader = Objects.requireNonNull(reader, "reader");
        Map<String, String> configured = new LinkedHashMap<>();
        for (Mapping mapping : Objects.requireNonNull(mappings, "mappings")) {
            if (configured.putIfAbsent(mapping.deviceId(), mapping.entityId()) != null) {
                throw new IllegalArgumentException("设备 ID 重复绑定");
            }
        }
        if (configured.isEmpty()) {
            throw new IllegalArgumentException("设备状态工具需要至少一个设备映射");
        }
        this.entities = Map.copyOf(configured);
        this.definition = ToolDefinition.builder().name(ref.toolName())
                .description(LlmTexts.DEVICE_STATE_TOOL_DESCRIPTION).inputSchema(LlmTexts.DEVICE_STATE_TOOL_SCHEMA).build();
    }

    public Binding binding() {
        return new Binding(ref, this);
    }

    /** 宿主把同一个工具及其回执合同登记到本次设备读取授权。 */
    public DeviceReadGate.SensorBinding sensorBinding() {
        return new DeviceReadGate.SensorBinding(ref, sensor, new BoundArguments(), new ObservedReading());
    }

    /** 由门禁在确认权限时调用，用于签出世界侧的待感知请求。 */
    public Optional<WorldDeviceStatus.Sensor> sensorFor(WorldState.Key key) {
        if (POWER.equals(key.predicate()) && entities.containsKey(key.subjectId())) {
            return Optional.of(sensor);
        }
        return Optional.empty();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String input) {
        return unavailable(LlmTexts.DEVICE_STATE_RUNTIME_ONLY);
    }

    @Override
    public String call(String input, ToolContext toolContext) {
        if (toolContext == null || !(toolContext.getContext().get("executionContext") instanceof CallContext context)) {
            return unavailable(LlmTexts.DEVICE_STATE_RUNTIME_ONLY);
        }
        JsonNode args;
        try {
            args = JSON.readTree(input);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            return unavailable(LlmTexts.DEVICE_STATE_INVALID_ARGUMENTS);
        }
        if (args == null || !args.isObject() || args.size() != 2 || !args.path("deviceId").isTextual()
                || !args.path("predicate").isTextual() || !POWER.equals(args.path("predicate").textValue())) {
            return unavailable(LlmTexts.DEVICE_STATE_INVALID_ARGUMENTS);
        }
        String deviceId = args.path("deviceId").textValue();
        String entityId = entities.get(deviceId);
        if (entityId == null) {
            return unavailable(LlmTexts.DEVICE_STATE_NOT_CONFIGURED);
        }

        context.check();
        HomeAssistantStateReader.Reading reading;
        try {
            reading = reader.read(entityId, context.deadline());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return unavailable(LlmTexts.TASK_STOPPED);
        } catch (IOException error) {
            return unavailable(LlmTexts.DEVICE_STATE_SOURCE_UNAVAILABLE);
        }
        context.check();
        if (reading == null || !entityId.equals(reading.entityId()) || reading.observedAt() == null || reading.state() == null) {
            return unavailable(LlmTexts.DEVICE_STATE_UNVERIFIED);
        }
        Boolean value = switch (reading.state()) {
            case "off" -> false;
            case "heat", "cool", "heat_cool", "auto", "dry", "fan_only" -> true;
            default -> null;
        };
        if (value == null) {
            return unavailable(LlmTexts.DEVICE_STATE_UNVERIFIED);
        }
        return toJson(Map.of("status", OBSERVED, "deviceId", deviceId, "predicate", POWER, "entityId", entityId,
                "value", value, "observedAt", reading.observedAt().toString(), "source", sensor.source()));
    }

    /** 仅接受宿主取得的实际派发身份和回执，使用原刷新请求中的设备范围。 */
    public Optional<WorldState.Observation> observation(ToolRef executedTool, WorldDeviceStatus.Refresh refresh,
                                                        String observationId, Object output) {
        if (!ref.equals(executedTool) || !sensor.equals(refresh.sensor()) || !(output instanceof Map<?, ?> receipt)) {
            return Optional.empty();
        }
        WorldState.Key key = refresh.query().key();
        String entityId = entities.get(key.subjectId());
        if (entityId == null || !POWER.equals(key.predicate()) || !OBSERVED.equals(receipt.get("status"))
                || !key.subjectId().equals(receipt.get("deviceId")) || !key.predicate().equals(receipt.get("predicate"))
                || !entityId.equals(receipt.get("entityId")) || !sensor.source().equals(receipt.get("source"))
                || !(receipt.get("value") instanceof Boolean value) || !(receipt.get("observedAt") instanceof String timestamp)) {
            return Optional.empty();
        }
        Instant observedAt;
        try {
            observedAt = Instant.parse(timestamp);
        } catch (DateTimeParseException invalid) {
            return Optional.empty();
        }
        if (observedAt.isBefore(refresh.query().asOf())) {
            return Optional.empty();
        }
        return Optional.of(new WorldState.Observation(observationId, key, value, observedAt, null,
                sensor.source(), refresh.query().scope(), null, List.of()));
    }

    private static String unavailable(String message) {
        return toJson(Map.of("status", UNAVAILABLE, "message", message));
    }

    private static String toJson(Map<String, Object> output) {
        try {
            return JSON.writeValueAsString(output);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(LlmTexts.DEVICE_STATE_UNVERIFIED, failure);
        }
    }

    public record Mapping(
            /** 已登记的世界设备 ID。 */
            String deviceId,
            /** 指定 HA 实例内的空调实体 ID。 */
            String entityId) {
        public Mapping {
            if (deviceId == null || deviceId.isBlank() || entityId == null || !entityId.matches("climate\\.[a-z0-9_]+")) {
                throw new IllegalArgumentException("设备状态映射需要世界设备 ID 和有效的 HA climate 实体 ID");
            }
        }
    }

    public interface Reader {
        HomeAssistantStateReader.Reading read(String entityId, Instant deadline) throws IOException, InterruptedException;
    }

    private final class BoundArguments implements DeviceReadGate.SensorArguments {
        @Override
        public boolean matches(Map<String, Object> args, WorldState.Key key, Map<String, Object> scope) {
            return sensorFor(key).isPresent() && args.size() == 2
                    && key.subjectId().equals(args.get("deviceId")) && key.predicate().equals(args.get("predicate"));
        }
    }

    private final class ObservedReading implements DeviceReadGate.SensorReading {
        @Override
        public WorldState.Observation decode(ToolCall call, WorldDeviceStatus.Refresh refresh, Object output) {
            String observationId = "device-state:" + call.rootRunId() + ":" + call.operationId();
            return observation(call.toolRef(), refresh, observationId, output).orElse(null);
        }
    }
}
