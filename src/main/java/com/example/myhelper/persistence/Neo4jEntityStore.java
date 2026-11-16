package com.example.myhelper.persistence;

import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.FieldBinding;
import com.example.myhelper.world.model.WorldEntity;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.TransactionCallback;

/** Neo4j 实际适配器：完整 JSON 用于还原实体，WORLD_REF 边用于保存引用投影。 */
public final class Neo4jEntityStore extends SnapshotParticipant {
    /** 已从 spring.neo4j 配置认证的共享驱动，其生命周期由 Spring 或调用方管理。 */
    private final Driver driver;
    /** 目标数据库会话配置；未指定数据库时使用驱动的默认数据库。 */
    private final SessionConfig config;
    /** 与 Qdrant 共用的完整实体编解码规则。 */
    private final EntityJson json;

    /** 构造适配器，不建立会话或发起查询。 */
    public Neo4jEntityStore(Driver driver, String database, EntityJson json) {
        // 复用已经配置认证信息的驱动。
        this.driver = Objects.requireNonNull(driver);
        // 不配置数据库名时，让 Neo4j 使用账号的默认数据库。
        this.config = database == null ? SessionConfig.defaultConfig() : SessionConfig.forDatabase(database);
        // 两库使用相同的实体编码合同。
        this.json = Objects.requireNonNull(json);
    }

    /** 显式创建实体 ID 唯一约束，不在 Spring 装配时主动连接数据库。 */
    public void initialize() {
        // 当前操作结束后关闭会话，不关闭共享 Driver。
        try (Session session = driver.session(config)) {
            // 创建约束是幂等操作；consume 等待执行结束。
            session.run("CREATE CONSTRAINT world_entity_id IF NOT EXISTS FOR (e:WorldEntity) REQUIRE e.id IS UNIQUE").consume();
        }
    }

    /** 按编号读取完整实体，身份占位节点不视为已经保存的实体。 */
    @Override
    public Optional<WorldEntity> read(String id) {
        // 每次读取使用独立的短会话。
        try (Session session = driver.session(config)) {
            // 读取放入驱动管理的本地只读事务。
            return session.executeRead(new TransactionCallback<Optional<WorldEntity>>() {
                @Override
                public Optional<WorldEntity> execute(TransactionContext tx) {
                    // 只读取该实体自身的 JSON，不查询任何引用目标。
                    var records = tx.run("MATCH (e:WorldEntity {id:$id}) WHERE e.json IS NOT NULL RETURN e.json AS json", Map.of("id", id)).list();
                    // 只有身份节点或完全不存在时都返回空。
                    if (records.isEmpty()) {
                        // 由上层决定如何处理缺失数据。
                        return Optional.empty();
                    }
                    // 从完整 JSON 恢复类型、引用、证据、时间及向量字段。
                    return Optional.of(json.entity(records.get(0).get("json").asString()));
                }
            });
        }
    }

    /** 一次 IN 查询取得整个批次，不读取任何引用目标。 */
    @Override
    public Map<String, WorldEntity> readBatch(List<String> ids) {
        // 为本次查询创建独立会话，允许并发读取。
        try (Session session = driver.session(config)) {
            // 使用驱动管理的只读事务。
            return session.executeRead(new TransactionCallback<Map<String, WorldEntity>>() {
                @Override
                public Map<String, WorldEntity> execute(TransactionContext tx) {
                    // 所有 ID 作为参数传递，不拼接用户数据。
                    var rows = tx.run("MATCH (e:WorldEntity) WHERE e.id IN $ids AND e.json IS NOT NULL RETURN e.json AS json", Map.of("ids", ids)).list();
                    // 后端顺序由公共入口按请求重新排列。
                    Map<String, WorldEntity> result = new LinkedHashMap<>();
                    // 每条记录从完整 JSON 还原。
                    for (var row : rows) {
                        // 不从投影字段拼出不完整的实体。
                        WorldEntity entity = json.entity(row.get("json").asString());
                        // 重复或非请求实体代表存储/协议异常。
                        if (!ids.contains(entity.id()) || result.put(entity.id(), entity) != null) {
                            // 不静默覆盖错误数据。
                            throw new IllegalStateException("Neo4j 批量读取返回异常实体");
                        }
                    }
                    // 返回本次读取的固定结果。
                    return Collections.unmodifiableMap(result);
                }
            });
        }
    }

    /** 数据库侧执行字段匹配和 ID 游标分页，不依赖 APOC 或解析客户端全部记录。 */
    @Override
    public StorageQuery.Page query(StorageQuery.PageRequest request) {
        // 首次查询不传位置，后续使用上一页最后的实体 ID。
        String after = request.cursor() == null ? null : request.cursor().position();
        // 参数映射允许 null，语句本身始终固定。
        Map<String, Object> params = new HashMap<>();
        // 当前页起点不参与业务路由。
        params.put("after", after);
        // 外层 AND、每组内 OR 均通过数据库列表谓词实现。
        params.put("groups", QueryTerms.groups(json, request.filter()));
        // 多取一条用于确认是否还有下一页。
        params.put("limit", request.limit() + 1);
        // 每次查询创建单独会话。
        try (Session session = driver.session(config)) {
            // 查询与写入协调锁无关，不串行化其他分支读取。
            return session.executeRead(new TransactionCallback<StorageQuery.Page>() {
                @Override
                public StorageQuery.Page execute(TransactionContext tx) {
                    // 占位节点没有 JSON，不返回为真实实体；字段值只作为参数。
                    var rows = tx.run("MATCH (e:WorldEntity) WHERE e.json IS NOT NULL AND ($after IS NULL OR e.id > $after) "
                            + "AND all(group IN $groups WHERE any(term IN group WHERE term IN coalesce(e.queryTerms, []))) "
                            + "RETURN e.json AS json ORDER BY e.id LIMIT $limit", params).list();
                    // 固定本页最多返回请求数量。
                    List<WorldEntity> entities = new ArrayList<>();
                    // 额外一条只用于判断下一页，不泄漏到本页结果。
                    for (int i = 0; i < Math.min(rows.size(), request.limit()); i++) {
                        // 还原包括引用和统计的全部字段。
                        entities.add(json.entity(rows.get(i).get("json").asString()));
                    }
                    // 仅在确认存在更多实体时生成续页游标。
                    StorageQuery.Cursor next = rows.size() > request.limit()
                            ? new StorageQuery.Cursor(DualWriteStore.Database.NEO4J, entities.getLast().id()) : null;
                    // 不自动跟随游标读取整个库。
                    return new StorageQuery.Page(entities, next);
                }
            });
        }
    }

    /** 在一个 Neo4j 本地事务内保存本分支的整批实体及引用投影。 */
    @Override
    protected void save(List<WorldEntity> entities) {
        // 会话只属于当前分支写入。
        try (Session session = driver.session(config)) {
            // Neo4j 本地事务覆盖当前批次的实体和出边变化。
            session.executeWrite(new TransactionCallback<Void>() {
                @Override
                public Void execute(TransactionContext tx) {
                    // 复用同一事务内的完整实体、字段和引用投影写入。
                    saveInTransaction(tx, entities);
                    // 当前事务不需要返回业务值。
                    return null;
                }
            });
        }
    }

    /**
     * 单实体数据库条件写。唯一约束须已通过 initialize 建立，本方法不隐式修改 schema。
     * 传输异常仍可能结果未知，调用方应读回核对，不能把异常当成未提交。
     */
    @Override
    public boolean writeConditionally(WorldEntity expected, WorldEntity replacement) {
        // 固定待写内容，避免驱动重试时读取到上层已修改的嵌套 Map。
        WorldEntity fixed = json.entity(json.encode(replacement));
        // 完整 JSON 结构比较忽略对象字段顺序，但保留内容、引用及统计变化。
        var expectedJson = expected == null ? null : json.parse(json.encode(expected));
        // 一个会话内先核对 schema，再执行数据库本地事务。
        try (Session session = driver.session(config)) {
            // 没有唯一约束时并发 MERGE 不能作为可靠的首次创建。
            var constraints = session.run("SHOW CONSTRAINTS YIELD type, entityType, labelsOrTypes, properties "
                    + "WHERE type = 'UNIQUENESS' AND entityType = 'NODE' "
                    + "AND labelsOrTypes = ['WorldEntity'] AND properties = ['id'] RETURN properties").list();
            // 不悄悄降级成缺少并发保护的普通写入。
            if (constraints.isEmpty()) {
                // 部署方需要先显式初始化对应数据库。
                throw new IllegalStateException("条件写入要求先建立 WorldEntity.id 唯一约束");
            }
            // 比较和所有投影变更必须使用同一个写事务。
            return session.executeWrite(new TransactionCallback<Boolean>() {
                @Override
                public Boolean execute(TransactionContext tx) {
                    // 临时属性只用于取得数据库节点写锁，立即移除但锁保留到事务结束。
                    var rows = tx.run("MERGE (e:WorldEntity {id:$id}) SET e._persistenceLock=true "
                            + "REMOVE e._persistenceLock RETURN e.json AS json", Map.of("id", fixed.id())).list();
                    // 唯一身份必须只返回一个节点。
                    if (rows.size() != 1) {
                        // 异常会令整个本地事务回滚。
                        throw new IllegalStateException("条件写入返回异常实体数量");
                    }
                    // 在已经持锁后读取原实体，身份占位节点按无内容处理。
                    var value = rows.getFirst().get("json");
                    // 旧结构缺省字段按现有 WorldEntity 规则还原，和 read 的结果保持一致。
                    var currentJson = value.isNull() ? null : json.parse(json.encode(json.entity(value.asString())));
                    // 对比全部字段，不能漏掉 revision 未变的统计更新。
                    if (!Objects.equals(expectedJson, currentJson)) {
                        // 不改变正文、字段投影或引用；不自动重试业务决策。
                        return false;
                    }
                    // 同一事务保存完整实体、字段查询投影和引用关系。
                    saveInTransaction(tx, List.of(fixed));
                    // 驱动会在本地事务提交确认后才向公共入口返回。
                    return true;
                }
            });
        }
    }

    /** 在调用方已经打开的本地事务里写入，避免条件检查和保存之间释放锁。 */
    private void saveInTransaction(TransactionContext tx, List<WorldEntity> entities) {
        // 所有实体使用同一个结构，不按类型选择数据库。
        for (WorldEntity entity : entities) {
            // 参数化传入数据，避免将实体内容拼接到 Cypher 中。
            String encoded = json.encode(entity);
            // 完整快照和字段等值投影在同一个本地事务内更新。
            Map<String, Object> values = Map.of("id", entity.id(), "json", encoded, "revision", entity.revision(), "terms", QueryTerms.project(json, encoded));
            // 保存完整实体 JSON，同时保留便于查询的修订号。
            tx.run("MERGE (e:WorldEntity {id:$id}) SET e.json=$json, e.revision=$revision, e.queryTerms=$terms", values).consume();
            // 只替换本实体的出边，其他实体的入边保持原样。
            tx.run("MATCH (e:WorldEntity {id:$id})-[r:WORLD_REF]->() DELETE r", Map.of("id", entity.id())).consume();
            // 保留 bindings 数组中的引用顺序。
            int index = 0;
            // 将字段引用逐个投影成普通引用边。
            for (FieldBinding binding : entity.bindings()) {
                // slot 和顺序被保存，但引用的强弱含义不在此处解释。
                reference(tx, entity.id(), binding.target(), "binding", binding.slot(), index++);
            }
            // 证据数组使用自己的顺序编号。
            index = 0;
            // 原样保留证据引用，不据此宣告事实已经成立。
            for (EntityRef evidence : entity.evidence()) {
                // evidence 没有字段 slot，以空字符串记录其投影位置。
                reference(tx, entity.id(), evidence, "evidence", "", index++);
            }
        }
    }

    /** 仅保存引用投影，不读取或展开目标；身份节点不表示目标归属或事实成立。 */
    private void reference(TransactionContext tx, String from, EntityRef ref, String kind, String slot, int index) {
        // 允许 revision 为 null，因此使用支持空值的参数映射。
        Map<String, Object> params = new HashMap<>();
        // 保存引用来源实体编号。
        params.put("from", from);
        // 保存引用目标的稳定编号。
        params.put("to", ref.id());
        // 区分字段引用和证据引用，不解释业务强弱。
        params.put("kind", kind);
        // 保留字段引用的 JSON Pointer 位置。
        params.put("slot", slot);
        // 保留引用在原数组中的顺序。
        params.put("index", index);
        // 保留指定修订，null 表示引用身份而非固定修订。
        params.put("revision", ref.revision());
        // 目标只建立身份端点；完整目标类型始终保留在来源实体 JSON 中。
        tx.run("MATCH (e:WorldEntity {id:$from}) MERGE (t:WorldEntity {id:$to}) CREATE (e)-[:WORLD_REF {kind:$kind,slot:$slot,position:$index,revision:$revision}]->(t)", params).consume();
    }

    /** 补偿新增实体时清除内容与出边，保留身份及其他实体指向它的入边。 */
    @Override
    protected void remove(String id) {
        // 当前补偿使用独立会话。
        try (Session session = driver.session(config)) {
            // 删除内容和出边作为一个本地事务执行。
            session.executeWrite(new TransactionCallback<Void>() {
                @Override
                public Void execute(TransactionContext tx) {
                    // 不使用 DETACH DELETE，避免破坏其他实体的引用。
                    tx.run("MATCH (e:WorldEntity {id:$id}) OPTIONAL MATCH (e)-[r:WORLD_REF]->() DELETE r REMOVE e.json,e.revision,e.queryTerms", Map.of("id", id)).consume();
                    // 补偿方法无需返回实体内容。
                    return null;
                }
            });
        }
    }
}
