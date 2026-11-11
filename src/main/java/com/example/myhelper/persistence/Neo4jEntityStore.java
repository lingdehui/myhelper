package com.example.myhelper.persistence;

import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.FieldBinding;
import com.example.myhelper.world.model.WorldEntity;
import java.util.HashMap;
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

    /** 在一个 Neo4j 本地事务内保存本分支的整批实体及引用投影。 */
    @Override
    protected void save(List<WorldEntity> entities) {
        // 会话只属于当前分支写入。
        try (Session session = driver.session(config)) {
            // Neo4j 本地事务覆盖当前批次的实体和出边变化。
            session.executeWrite(new TransactionCallback<Void>() {
                @Override
                public Void execute(TransactionContext tx) {
                    // 所有实体使用同一个结构，不按类型选择数据库。
                    for (WorldEntity entity : entities) {
                        // 参数化传入数据，避免将实体内容拼接到 Cypher 中。
                        Map<String, Object> values = Map.of("id", entity.id(), "json", json.encode(entity), "revision", entity.revision());
                        // 保存完整实体 JSON，同时保留便于查询的修订号。
                        tx.run("MERGE (e:WorldEntity {id:$id}) SET e.json=$json, e.revision=$revision", values).consume();
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
                    // 当前事务不需要返回业务值。
                    return null;
                }
            });
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
                    tx.run("MATCH (e:WorldEntity {id:$id}) OPTIONAL MATCH (e)-[r:WORLD_REF]->() DELETE r REMOVE e.json,e.revision", Map.of("id", id)).consume();
                    // 补偿方法无需返回实体内容。
                    return null;
                }
            });
        }
    }
}
