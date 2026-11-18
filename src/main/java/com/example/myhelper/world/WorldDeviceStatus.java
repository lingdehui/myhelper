package com.example.myhelper.world;

import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.UnitType;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 供查询设备状态的能力调用。编排器负责派发感知工具并交回实际观测。 */
public final class WorldDeviceStatus {
    /** 负责按授权范围定位设备并核对设备类型。 */
    private final WorldDeviceDirectory directory;
    /** 按事件编号追加实际感知。 */
    private final WorldRepository repository;
    /** 对当前可信证据做时效和一致性判断。 */
    private final ContextService context;
    /** 初查和复查各自的取证时点。 */
    private final Clock clock;

    public WorldDeviceStatus(WorldDeviceDirectory directory, WorldRepository repository,
                             ContextService context, Clock clock) {
        this.directory = Objects.requireNonNull(directory);
        this.repository = Objects.requireNonNull(repository);
        this.context = Objects.requireNonNull(context);
        this.clock = Objects.requireNonNull(clock);
    }

    /** 使用已绑定 ID 或具体设备线索查询。只有新鲜的可信证据才返回已知状态。 */
    public Result read(Request request, Access access) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(access);
        var device = directory.find(request.device(), access);
        switch (device.status()) {
            case DENIED -> { return unknown(Reason.ACCESS_DENIED, null); }
            case NOT_FOUND -> { return unknown(Reason.DEVICE_NOT_FOUND, null); }
            case INCOMPLETE -> { return unknown(Reason.DEVICE_LOOKUP_INCOMPLETE, null); }
            case AMBIGUOUS -> { return new Result(Status.AMBIGUOUS, Reason.MULTIPLE_DEVICES, device.candidates(), null, null); }
            case FOUND -> { }
        }

        EntityRef ref = device.candidates().getFirst();
        var key = new WorldState.Key(ref.id(), request.predicate());
        var scope = request.device().scope();
        if (!access.allowsRead(key, scope)) {
            return unknown(Reason.ACCESS_DENIED, null);
        }
        var query = new WorldState.Query(key, scope, clock.instant(), request.policy());
        var state = context.current(query);
        if (state.status() == WorldState.Status.UNKNOWN || state.status() == WorldState.Status.EXPIRED) {
            var sensor = approvedSensor(query, access).orElse(null);
            if (sensor == null) {
                return unknown(Reason.SENSOR_UNAVAILABLE, state);
            }
            var refresh = new PendingRefresh(ref, query, sensor, request.device().budget());
            return new Result(Status.NEEDS_REFRESH, Reason.EVIDENCE_UNAVAILABLE, List.of(ref), state, refresh);
        }
        return fromState(ref, state);
    }

    /** 校验本次实际测量后追加观测，再裁决同一设备、属性和范围。复查不下发第二次感知。 */
    public Result complete(Refresh refresh, EntityRef executedSensor, WorldState.Observation reading, Access access) {
        Objects.requireNonNull(refresh);
        Objects.requireNonNull(access);
        var query = refresh.query();
        var search = new WorldDeviceDirectory.Query(refresh.device().id(), null, null, query.scope(), refresh.budget());
        var device = directory.find(search, access);
        if (device.status() == WorldDeviceDirectory.Status.DENIED) {
            return unknown(Reason.ACCESS_DENIED, null);
        }
        if (device.status() == WorldDeviceDirectory.Status.INCOMPLETE) {
            return unknown(Reason.DEVICE_LOOKUP_INCOMPLETE, null);
        }
        if (device.status() != WorldDeviceDirectory.Status.FOUND || !refresh.device().equals(device.candidates().getFirst())) {
            return unknown(Reason.DEVICE_CHANGED, null);
        }
        if (!access.allowsRead(query.key(), query.scope()) || !approvedSensor(query, access).equals(Optional.of(refresh.sensor()))) {
            return unknown(Reason.ACCESS_DENIED, null);
        }

        var asOf = clock.instant();
        if (!refresh.sensor().unit().equals(executedSensor) || reading == null || !query.key().equals(reading.key())
                || !query.scope().equals(reading.scope()) || !refresh.sensor().source().equals(reading.source())
                || reading.value() == null || reading.observedAt() == null || reading.observedAt().isBefore(query.asOf())
                || reading.observedAt().isAfter(asOf)) {
            return unknown(Reason.UNVERIFIED_READING, null);
        }
        repository.append(reading);
        var state = context.current(new WorldState.Query(query.key(), query.scope(), asOf, query.policy()));
        return fromState(refresh.device(), state);
    }

    private static Optional<Sensor> approvedSensor(WorldState.Query query, Access access) {
        Optional<Sensor> registered = access.sensorFor(query.key(), query.scope());
        var trusted = query.policy().trustedSources();
        if (registered.isPresent() && !trusted.isEmpty() && !trusted.contains(registered.get().source())) {
            return Optional.empty();
        }
        return registered;
    }

    private static Result fromState(EntityRef device, WorldState.Result state) {
        if (state.status() == WorldState.Status.KNOWN) {
            return new Result(Status.KNOWN, Reason.NONE, List.of(device), state, null);
        }
        return unknown(Reason.EVIDENCE_UNAVAILABLE, state);
    }

    private static Result unknown(Reason reason, WorldState.Result state) {
        return new Result(Status.UNKNOWN, reason, List.of(), state, null);
    }

    /** 由宿主按本次任务身份提供权限、登记的感知工具和实际观测来源。 */
    public interface Access extends WorldDeviceDirectory.Access {
        /** 授权读取属性及其在同设备同范围内参与时效裁决的证据。 */
        boolean allowsRead(WorldState.Key key, Map<String, Object> scope);

        /** 返回本次已登记且允许调用的感知工具。空值用 Optional.empty()。 */
        Optional<Sensor> sensorFor(WorldState.Key key, Map<String, Object> scope);
    }

    public record Request(
            /** 设备线索或精确 ID，以及本次使用的范围和定位预算。 */
            WorldDeviceDirectory.Query device,
            /** 能力已绑定的属性名，例如 power。 */
            String predicate,
            /** 由宿主确定的来源、置信、时效和查询预算。 */
            WorldState.Policy policy) {
        public Request {
            Objects.requireNonNull(device);
            WorldState.requireText(predicate, "predicate");
            Objects.requireNonNull(policy);
        }
    }

    public record Sensor(
            /** 实际派发时需对应的工具身份和修订。 */
            EntityRef unit,
            /** 该工具获准提供的观测来源。 */
            String source) {
        public Sensor {
            Objects.requireNonNull(unit);
            if (unit.revision() == null || (unit.unitType() != UnitType.TOOL && unit.unitType() != UnitType.MCP_TOOL)) {
                throw new IllegalArgumentException("感知工具必须有明确的工具类型和修订");
            }
            WorldState.requireText(source, "source");
        }
    }

    /** 由初查产生的待感知请求。工具的派发、次数限制和结果身份由编排器管理。 */
    public sealed interface Refresh permits PendingRefresh {
        EntityRef device();
        WorldState.Query query();
        Sensor sensor();
        WorldDeviceDirectory.Budget budget();
    }

    private record PendingRefresh(
            /** 初查确认的设备修订。 */
            EntityRef device,
            /** 固定要复查的属性、范围、时效策略及首次查询时间。 */
            WorldState.Query query,
            /** 初查时允许调用的工具和来源。 */
            Sensor sensor,
            /** 复查设备是否仍可读取时的定位预算。 */
            WorldDeviceDirectory.Budget budget) implements Refresh {
    }

    public enum Status {
        KNOWN, UNKNOWN, AMBIGUOUS, NEEDS_REFRESH
    }

    public enum Reason {
        NONE, ACCESS_DENIED, DEVICE_NOT_FOUND, DEVICE_LOOKUP_INCOMPLETE, MULTIPLE_DEVICES,
        SENSOR_UNAVAILABLE, EVIDENCE_UNAVAILABLE, DEVICE_CHANGED, UNVERIFIED_READING
    }

    public record Result(
            /** 能力可据此返回确切状态、请求补充信息或交编排器感知。 */
            Status status,
            /** 业务结论的具体原因。 */
            Reason reason,
            /** 可公开的已确认目标或歧义候选。 */
            List<EntityRef> candidates,
            /** 世界状态证据及裁决，访问被拒或对象不明确时为空。 */
            WorldState.Result state,
            /** 初查有权感知时可取得的请求。 */
            Refresh refresh) {
        public Result {
            candidates = List.copyOf(candidates);
        }
    }
}
