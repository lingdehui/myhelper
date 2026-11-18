package com.example.myhelper.world;

import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.DualWriteStore.Database;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.WorldEntity;
import com.example.myhelper.world.model.WorldObjectType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

/** 从指定的权威库按业务登记的房间、名称、别名和稳定身份查找设备。 */
public final class WorldDeviceDirectory {
    /** 世界对象类型的标量投影。 */
    private static final String OBJECT_TYPE = "/worldObjectType";
    /** 名称和别名通过两组独立条件查询，逐项核对后合并。 */
    private static final String NAME = "/name";
    /** 房间和设备的别名使用字符串数组。 */
    private static final String ALIASES = "/data/aliases";
    /** 设备的直接房间归属使用房间的稳定实体编号。 */
    private static final String ROOM_ID = "roomId";
    /** 数据库用于筛选房间归属的字段路径。 */
    private static final String ROOM_ID_PATH = "/data/" + ROOM_ID;
    /** 世界业务使用统一存储查询。 */
    private final DualWriteStore store;
    /** 由装配方选定的设备及房间目录权威库。 */
    private final Database source;
    /** 目录预算使用单调时钟。 */
    private final LongSupplier nanoTime;

    public WorldDeviceDirectory(DualWriteStore store, Database source) {
        this(store, source, System::nanoTime);
    }

    WorldDeviceDirectory(DualWriteStore store, Database source, LongSupplier nanoTime) {
        this.store = Objects.requireNonNull(store);
        this.source = Objects.requireNonNull(source);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public interface Access {
        /** 读取任何目录数据之前，核对本次请求范围是否获得授权。 */
        boolean allowsScope(Map<String, Object> scope);

        /** 核对与本次范围精确匹配的 ROOM 或 DEVICE 是否可以读取。 */
        boolean allows(WorldEntity entity, Map<String, Object> scope);
    }

    public record Query(
            /** 已确定的设备稳定编号，可和名称及房间条件一起核对。 */
            String subjectId,
            /** 设备的确切名称或一个已登记的别名。 */
            String name,
            /** 房间的稳定编号、确切名称或已登记的别名。 */
            String room,
            /** 调用方明确给出的读取范围。 */
            Map<String, Object> scope,
            /** 房间与设备名称查询共享的分页和耗时预算。 */
            Budget budget) {
        public Query {
            if (subjectId == null && name == null && room == null) {
                throw new IllegalArgumentException("设备查询必须给出设备编号、名称或房间");
            }
            requireClue(subjectId, "subjectId");
            requireClue(name, "name");
            requireClue(room, "room");
            scope = WorldValues.object(scope);
            Objects.requireNonNull(budget);
        }
    }

    public record Budget(
            /** 每页允许从数据库取得的实体数。 */
            int pageSize,
            /** 目录查询合计允许访问的页数。 */
            int maxPages,
            /** 本次目录查找预算，底层同步读请求使用存储自己的超时。 */
            Duration timeBudget) {
        public Budget {
            Objects.requireNonNull(timeBudget);
            if (pageSize < 1 || pageSize > StorageQuery.MAX_RESULTS || maxPages < 1
                    || timeBudget.isZero() || timeBudget.isNegative()
                    || timeBudget.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalArgumentException("设备目录分页或耗时预算无效");
            }
        }
    }

    public enum Status {
        FOUND, NOT_FOUND, AMBIGUOUS, DENIED, INCOMPLETE
    }

    public record Result(
            /** 查找结论。只有 FOUND 可直接确定设备。 */
            Status status,
            /** 唯一目标或歧义时可展示的授权设备身份，使用确切修订。 */
            List<EntityRef> candidates) {
        public Result {
            Objects.requireNonNull(status);
            candidates = List.copyOf(candidates);
        }
    }

    /** 完整核对目录后返回候选。任何匹配实体被拒绝访问时，本次不返回身份。 */
    public Result find(Query query, Access access) {
        Objects.requireNonNull(query);
        Objects.requireNonNull(access);
        Lookup lookup = new Lookup(query, access);
        if (!lookup.authorizesScope()) {
            return result(Status.DENIED);
        }
        if (lookup.expired()) {
            return result(Status.INCOMPLETE);
        }
        String roomId = null;
        if (query.room() != null) {
            Map<String, WorldEntity> rooms = lookup.findRooms();
            if (lookup.problem != null) {
                return result(lookup.problem);
            }
            if (rooms.isEmpty()) {
                return result(Status.NOT_FOUND);
            }
            if (rooms.size() > 1) {
                return result(Status.AMBIGUOUS);
            }
            roomId = rooms.keySet().iterator().next();
        }
        Map<String, WorldEntity> devices = lookup.findDevices(roomId);
        if (lookup.problem != null) {
            return result(lookup.problem);
        }
        if (lookup.expired()) {
            return result(Status.INCOMPLETE);
        }
        if (devices.isEmpty()) {
            return result(Status.NOT_FOUND);
        }
        List<EntityRef> refs = new ArrayList<>();
        for (WorldEntity device : devices.values()) {
            refs.add(new EntityRef(device.id(), device.revision(), WorldObjectType.DEVICE, null, null, null));
        }
        return new Result(refs.size() == 1 ? Status.FOUND : Status.AMBIGUOUS, refs);
    }

    private static void requireClue(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空白");
        }
    }

    private static Result result(Status status) {
        return new Result(status, List.of());
    }

    private static boolean hasAlias(WorldEntity entity, String alias) {
        Object stored = entity.data().get("aliases");
        return stored instanceof List<?> aliases && aliases.contains(alias);
    }

    /** 一次查找的来源、范围、排序结果和停止条件。 */
    private final class Lookup {
        /** 查询请求中的精确线索及共同预算。 */
        private final Query query;
        /** 当前调用方的授权规则。 */
        private final Access access;
        /** 从授权检查开始计算耗时。 */
        private final long started = nanoTime.getAsLong();
        /** 已发出的分页请求计数。 */
        private int pages;
        /** 无法完成本轮查找时的明确结论。 */
        private Status problem;

        private Lookup(Query query, Access access) {
            this.query = query;
            this.access = access;
        }

        private boolean expired() {
            return nanoTime.getAsLong() - started >= query.budget().timeBudget().toNanos();
        }

        private boolean authorizesScope() {
            try {
                return access.allowsScope(query.scope());
            } catch (RuntimeException failure) {
                return false;
            }
        }

        private Map<String, WorldEntity> findRooms() {
            String clue = query.room();
            Map<String, WorldEntity> found = new LinkedHashMap<>();
            Optional<WorldEntity> exact = read(clue);
            if (exact.isPresent() && exact.get().worldObjectType() == WorldObjectType.ROOM) {
                add(found, exact.get());
            }
            scan(found, WorldObjectType.ROOM, null, NAME, clue);
            scan(found, WorldObjectType.ROOM, null, ALIASES, clue);
            return found;
        }

        private Map<String, WorldEntity> findDevices(String roomId) {
            String name = query.name();
            Map<String, WorldEntity> found = new LinkedHashMap<>();
            if (query.subjectId() != null) {
                Optional<WorldEntity> exact = read(query.subjectId());
                if (exact.isPresent()) {
                    WorldEntity device = exact.get();
                    if (matches(device, WorldObjectType.DEVICE, roomId)
                            && (name == null || name.equals(device.name()) || hasAlias(device, name))) {
                        add(found, device);
                    }
                }
                return found;
            }
            if (name == null) {
                scan(found, WorldObjectType.DEVICE, roomId, null, null);
            } else {
                scan(found, WorldObjectType.DEVICE, roomId, NAME, name);
                scan(found, WorldObjectType.DEVICE, roomId, ALIASES, name);
            }
            return found;
        }

        private Optional<WorldEntity> read(String id) {
            if (expired()) {
                problem = Status.INCOMPLETE;
                return Optional.empty();
            }
            try {
                Optional<WorldEntity> value = store.read(source, id);
                if (expired() || (value.isPresent() && !id.equals(value.get().id()))) {
                    problem = Status.INCOMPLETE;
                    return Optional.empty();
                }
                return value;
            } catch (RuntimeException failure) {
                problem = Status.INCOMPLETE;
                return Optional.empty();
            }
        }

        private void scan(Map<String, WorldEntity> found, WorldObjectType type, String roomId,
                          String exactField, String clue) {
            if (problem != null) {
                return;
            }
            List<StorageQuery.Match> conditions = new ArrayList<>();
            conditions.add(new StorageQuery.Match(OBJECT_TYPE, List.of(type.name())));
            if (roomId != null) {
                conditions.add(new StorageQuery.Match(ROOM_ID_PATH, List.of(roomId)));
            }
            if (exactField != null) {
                conditions.add(new StorageQuery.Match(exactField, List.of(clue)));
            }
            StorageQuery.Filter filter = new StorageQuery.Filter(conditions);
            Set<StorageQuery.Cursor> visited = new HashSet<>();
            StorageQuery.Cursor cursor = null;
            do {
                StorageQuery.Page page = readPage(filter, cursor);
                if (page == null) {
                    return;
                }
                for (WorldEntity entity : page.entities()) {
                    if (expired()) {
                        problem = Status.INCOMPLETE;
                        return;
                    }
                    if (matches(entity, type, roomId) && matchesField(entity, exactField, clue)) {
                        add(found, entity);
                        if (problem != null) {
                            return;
                        }
                    }
                }
                cursor = page.nextCursor();
                if (cursor != null && !visited.add(cursor)) {
                    problem = Status.INCOMPLETE;
                    return;
                }
            } while (cursor != null);
        }

        private StorageQuery.Page readPage(StorageQuery.Filter filter, StorageQuery.Cursor cursor) {
            if (expired() || pages >= query.budget().maxPages()) {
                problem = Status.INCOMPLETE;
                return null;
            }
            pages++;
            try {
                StorageQuery.Page page = store.query(source,
                        new StorageQuery.PageRequest(filter, query.budget().pageSize(), cursor));
                if (expired() || page.entities().size() > query.budget().pageSize()) {
                    problem = Status.INCOMPLETE;
                    return null;
                }
                return page;
            } catch (RuntimeException failure) {
                problem = Status.INCOMPLETE;
                return null;
            }
        }

        private void add(Map<String, WorldEntity> found, WorldEntity entity) {
            Map<String, Object> recordedScope;
            try {
                recordedScope = WorldValues.object(entity.scope());
            } catch (IllegalArgumentException failure) {
                problem = Status.INCOMPLETE;
                return;
            }
            if (!recordedScope.equals(query.scope())) {
                return;
            }
            try {
                if (!access.allows(entity, query.scope())) {
                    problem = Status.DENIED;
                    return;
                }
            } catch (RuntimeException failure) {
                problem = Status.DENIED;
                return;
            }
            if (expired()) {
                problem = Status.INCOMPLETE;
                return;
            }
            WorldEntity previous = found.putIfAbsent(entity.id(), entity);
            if (previous != null && previous.revision() != entity.revision()) {
                problem = Status.INCOMPLETE;
            }
        }
    }

    private static boolean matches(WorldEntity entity, WorldObjectType type, String roomId) {
        return entity.worldObjectType() == type && (roomId == null || roomId.equals(entity.data().get(ROOM_ID)));
    }

    private static boolean matchesField(WorldEntity entity, String exactField, String clue) {
        if (exactField == null) {
            return true;
        }
        return switch (exactField) {
            case NAME -> clue.equals(entity.name());
            case ALIASES -> hasAlias(entity, clue);
            default -> throw new IllegalArgumentException("未知的设备目录匹配字段");
        };
    }
}
