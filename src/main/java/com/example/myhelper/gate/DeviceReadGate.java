package com.example.myhelper.gate;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import com.example.myhelper.world.WorldDeviceStatus;
import com.example.myhelper.world.WorldState;
import com.example.myhelper.world.model.EntityRef;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 按宿主授予的范围读取唯一设备的电源状态，并核对实际读数与回答。 */
public final class DeviceReadGate implements RuntimeGate {
    /** 现有普通任务和固化资格的业务策略。 */
    private final RuntimeGate ordinary;
    /** 设备定位、状态裁决及观测复查。 */
    private final WorldDeviceStatus world;
    /** 宿主登记为受本策略保护的感知调用名，包括当前不允许调用的名称。 */
    private final Set<String> sensorNames;
    /** 按可信任务身份查找授权，独立于模型的目标、参数和返回。 */
    private final Host host;

    public DeviceReadGate(RuntimeGate ordinary, WorldDeviceStatus world, Set<String> sensorNames, Host host) {
        this.ordinary = Objects.requireNonNull(ordinary);
        this.world = Objects.requireNonNull(world);
        this.sensorNames = Set.copyOf(sensorNames);
        this.host = Objects.requireNonNull(host);
    }

    /** 应用装配时确认登记的回调会经过设备读取策略。 */
    public boolean protects(String toolName) {
        return sensorNames.contains(toolName);
    }

    /** 能力初查使用原有世界结果，区分已知、歧义、待刷新及未知。 */
    public Optional<WorldDeviceStatus.Result> read(String taskId, String rootRunId) {
        Authorization grant = authorization(taskId, rootRunId);
        if (grant == null) {
            return Optional.empty();
        }
        return Optional.of(readAuthorized(grant));
    }

    @Override
    public boolean allow(String goal, Map<String, Object> args, CapabilityLookup.Ref ref) {
        return ordinary.allow(goal, args, ref);
    }

    @Override
    public boolean verify(String goal, Map<String, Object> args, Object output) {
        return ordinary.verify(goal, args, output);
    }

    @Override
    public boolean verify(String goal, Map<String, Object> args, Object output, Map<String, Object> instructions) {
        return ordinary.verify(goal, args, output, instructions);
    }

    @Override
    public boolean allowTool(ToolCall call) {
        Authorization grant = authorization(call.taskId(), call.rootRunId());
        if (ordinaryTool(call, grant)) {
            return ordinary.allowTool(call);
        }
        return pendingRefresh(call, grant) != null;
    }

    @Override
    public boolean beforeToolDispatch(ToolCall call) {
        Authorization grant = authorization(call.taskId(), call.rootRunId());
        if (ordinaryTool(call, grant)) {
            return ordinary.beforeToolDispatch(call);
        }
        WorldDeviceStatus.Refresh refresh = pendingRefresh(call, grant);
        return refresh != null && grant.dispatch.compareAndSet(null, new Dispatch(call, refresh));
    }

    @Override
    public boolean verifyTool(ToolCall call, Object output, Map<String, Object> instructions) {
        Authorization grant = authorization(call.taskId(), call.rootRunId());
        if (ordinaryTool(call, grant)) {
            return ordinary.verifyTool(call, output, instructions);
        }
        if (grant == null) {
            return false;
        }
        Dispatch dispatch = grant.dispatch.get();
        if (dispatch == null || !dispatch.call.equals(call) || !dispatch.receiptReceived.compareAndSet(false, true)) {
            return false;
        }
        WorldState.Observation observation = grant.sensor.reading().decode(call, dispatch.refresh, output);
        if (observation == null || !(observation.value() instanceof Boolean)) {
            return false;
        }
        WorldDeviceStatus.Result result = world.complete(dispatch.refresh, grant.sensor.sensor().unit(),
                observation, grant.access);
        return result.status() == WorldDeviceStatus.Status.KNOWN
                && observation.value().equals(result.state().current().value());
    }

    @Override
    public boolean verifyCompletion(CompletionContext context, Object output, Map<String, Object> instructions) {
        Authorization grant = authorization(context.taskId(), context.rootRunId());
        if (grant == null) {
            return !host.isDeviceReadTask(context.taskId(), context.rootRunId())
                    && ordinary.verifyCompletion(context, output, instructions);
        }
        if (!(output instanceof Map<?, ?> answer) || answer.size() != 4
                || !(answer.get("value") instanceof Boolean value)) {
            return false;
        }
        WorldDeviceStatus.Result result = readAuthorized(grant);
        if (result.status() != WorldDeviceStatus.Status.KNOWN) {
            return false;
        }
        WorldState.Evidence evidence = result.state().current();
        return result.candidates().getFirst().id().equals(answer.get("subjectId"))
                && grant.request.predicate().equals(answer.get("predicate"))
                && value.equals(evidence.value())
                && evidence.observation().id().equals(answer.get("observationId"));
    }

    private Authorization authorization(String taskId, String rootRunId) {
        Authorization grant = host.authorization(taskId, rootRunId);
        if (grant == null || !grant.taskId.equals(taskId) || !grant.rootRunId.equals(rootRunId)) {
            return null;
        }
        return grant;
    }

    private boolean ordinaryTool(ToolCall call, Authorization grant) {
        return grant == null && !sensorNames.contains(call.toolName())
                && (call.toolRef() == null || !sensorNames.contains(call.toolRef().toolName()))
                && !host.isDeviceReadTask(call.taskId(), call.rootRunId());
    }

    private WorldDeviceStatus.Refresh pendingRefresh(ToolCall call, Authorization grant) {
        if (grant == null || grant.sensor == null || grant.dispatch.get() != null
                || !sensorNames.contains(call.toolName()) || !grant.sensor.ref().equals(call.toolRef())
                || !grant.sensor.ref().toolName().equals(call.toolName())) {
            return null;
        }
        WorldDeviceStatus.Result result = readAuthorized(grant);
        WorldDeviceStatus.Refresh refresh = result.refresh();
        if (result.status() != WorldDeviceStatus.Status.NEEDS_REFRESH || !grant.sensor.sensor().equals(refresh.sensor())
                || !grant.sensor.arguments().matches(call.args(), refresh.query().key(), refresh.query().scope())) {
            return null;
        }
        return refresh;
    }

    private WorldDeviceStatus.Result readAuthorized(Authorization grant) {
        WorldDeviceStatus.Result result = world.read(grant.request, grant.access);
        if (result.status() == WorldDeviceStatus.Status.KNOWN
                && !(result.state().current().value() instanceof Boolean)) {
            return new WorldDeviceStatus.Result(WorldDeviceStatus.Status.UNKNOWN,
                    WorldDeviceStatus.Reason.UNVERIFIED_READING, List.of(), null, null);
        }
        if (result.status() == WorldDeviceStatus.Status.KNOWN || result.status() == WorldDeviceStatus.Status.NEEDS_REFRESH) {
            EntityRef candidate = result.candidates().getFirst();
            grant.device.compareAndSet(null, candidate);
            if (!candidate.equals(grant.device.get())) {
                return new WorldDeviceStatus.Result(WorldDeviceStatus.Status.UNKNOWN,
                        WorldDeviceStatus.Reason.DEVICE_CHANGED, List.of(), null, null);
            }
        }
        return result;
    }

    public interface Host {
        /** 同一根运行返回同一授权对象。撤销时返回 null，授权状态由宿主随运行回收。 */
        Authorization authorization(String taskId, String rootRunId);

        /** 已知为设备读任务但未取得授权时，用于阻断工具和完成验收。 */
        boolean isDeviceReadTask(String taskId, String rootRunId);
    }

    public static final class Authorization {
        /** 宿主认可的稳定任务编号。 */
        private final String taskId;
        /** 授权适用的根运行编号。 */
        private final String rootRunId;
        /** 本次允许的设备线索、精确范围、power 属性及证据时效策略。 */
        private final WorldDeviceStatus.Request request;
        /** 本次目录、对象、属性与感知工具的权限。 */
        private final WorldDeviceStatus.Access access;
        /** 可选的、针对本次设备属性的已登记感知工具。 */
        private final SensorBinding sensor;
        /** 从首次明确结果固定的本次设备修订。 */
        private final AtomicReference<EntityRef> device = new AtomicReference<>();
        /** 在实际派发前原子占用的一次刷新；回执只能交回该动作。 */
        private final AtomicReference<Dispatch> dispatch = new AtomicReference<>();

        public Authorization(String taskId, String rootRunId, WorldDeviceStatus.Request request,
                             WorldDeviceStatus.Access access, SensorBinding sensor) {
            this.taskId = Objects.requireNonNull(taskId);
            this.rootRunId = Objects.requireNonNull(rootRunId);
            this.request = Objects.requireNonNull(request);
            this.access = Objects.requireNonNull(access);
            this.sensor = sensor;
            if (taskId.isBlank() || rootRunId.isBlank()) {
                throw new IllegalArgumentException("设备读取授权须明确任务及根运行编号");
            }
            if (!"power".equals(request.predicate()) || request.policy().trustedSources().isEmpty()
                    || request.policy().maximumAge() == null) {
                throw new IllegalArgumentException("电源读取授权须明确 power、可信来源及最长观测年龄");
            }
        }
    }

    public record SensorBinding(
            /** 与实际派发共用的宿主工具身份。 */
            ToolRef ref,
            /** 对应的世界工具固定修订及可信观测来源。 */
            WorldDeviceStatus.Sensor sensor,
            /** 由登记合同核对最终参数是否只读取本次目标。 */
            SensorArguments arguments,
            /** 宿主按实际回调和登记合同，将合法回包转换为观测。 */
            SensorReading reading) {
        public SensorBinding {
            Objects.requireNonNull(ref);
            Objects.requireNonNull(sensor);
            Objects.requireNonNull(arguments);
            Objects.requireNonNull(reading);
        }
    }

    @FunctionalInterface
    public interface SensorArguments {
        boolean matches(Map<String, Object> args, WorldState.Key key, Map<String, Object> scope);
    }

    @FunctionalInterface
    public interface SensorReading {
        /** 回包无法根据该调用及登记合同验证时返回 null。 */
        WorldState.Observation decode(ToolCall call, WorldDeviceStatus.Refresh refresh, Object output);
    }

    private static final class Dispatch {
        /** 已获许可的实际工具调用。 */
        private final ToolCall call;
        /** 在实际派发前复查得到的目标、授权工具和测量窗口。 */
        private final WorldDeviceStatus.Refresh refresh;
        /** 一个派发只接收一个实际回执。 */
        private final AtomicBoolean receiptReceived = new AtomicBoolean();

        private Dispatch(ToolCall call, WorldDeviceStatus.Refresh refresh) {
            this.call = call;
            this.refresh = refresh;
        }
    }
}
