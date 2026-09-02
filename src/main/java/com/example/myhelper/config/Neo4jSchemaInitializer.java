package com.example.myhelper.config;

import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Neo4j schema 初始化（B4 修复）。
 *
 * <p>Neo4j 是 schema-optional 数据库，label / 属性 / 关系类型都是数据驱动的。
 * 当 Cypher 查询引用数据库里不存在的 label 或 property 时，会触发
 * UnknownLabelWarning / UnknownPropertyWarning 持续刷屏。这里在启动时幂等创建
 * 唯一约束（让 label 显式存在）并补全历史节点缺失属性。</p>
 *
 * <p>注：DISABLES / FALLBACK 关系类型无法通过 DDL 创建（Neo4j 不支持对关系类型建约束），
 * 它们是数据驱动的，只有当 Unit 真正建立禁用/降级边时才存在。</p>
 */
@Component
public class Neo4jSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Neo4jSchemaInitializer.class);

    private final Driver driver;

    public Neo4jSchemaInitializer(Driver driver) {
        this.driver = driver;
    }

    @Override
    public void run(ApplicationArguments args) {
        try (var session = driver.session()) {
            // 1. 唯一约束（幂等）：让 FailureCause / Unit label 显式存在
            session.run("CREATE CONSTRAINT failure_cause_id IF NOT EXISTS "
                    + "FOR (f:FailureCause) REQUIRE f.causeId IS UNIQUE");
            session.run("CREATE CONSTRAINT unit_id IF NOT EXISTS "
                    + "FOR (u:Unit) REQUIRE u.unitId IS UNIQUE");
            session.run("CREATE CONSTRAINT world_object_id IF NOT EXISTS "
                    + "FOR (w:WorldObject) REQUIRE w.objectId IS UNIQUE");
            session.run("CREATE CONSTRAINT context_unit_id IF NOT EXISTS "
                    + "FOR (c:ContextUnit) REQUIRE c.contextId IS UNIQUE");
            session.run("CREATE INDEX context_unit_state IF NOT EXISTS "
                    + "FOR (c:ContextUnit) ON (c.stateId)");
            session.run("CREATE INDEX context_unit_current IF NOT EXISTS "
                    + "FOR (c:ContextUnit) ON (c.subjectId, c.predicate, c.active)");

            // v2 世界模型不兼容旧的“固定 ID 覆盖快照”；用户已允许清空旧 ContextUnit。
            session.run("MATCH (c:ContextUnit) WHERE c.stateId IS NULL DETACH DELETE c");

            // 2. 补全历史 FailureCause 节点缺失属性（旧版本沉淀缺 inputArgs / suggestedUnitIdsJson）
            session.run("MATCH (f:FailureCause) WHERE f.inputArgs IS NULL SET f.inputArgs = ''");
            session.run("MATCH (f:FailureCause) WHERE f.suggestedUnitIdsJson IS NULL SET f.suggestedUnitIdsJson = '[]'");

            // 3. Rule 唯一约束（B4 补充）：让 Rule label + summary 属性显式存在
            // 注：Neo4j Community 版不支持 property existence 约束（IS NOT NULL 需 Enterprise），
            // 改用 uniqueness 约束；其余属性（confidence/source/relatedTools/created/enabled）
            // 为数据驱动，会随 Rule 数据产生而自动存在。
            session.run("CREATE CONSTRAINT rule_summary IF NOT EXISTS "
                    + "FOR (r:Rule) REQUIRE r.summary IS UNIQUE");

            log.info("✅ Neo4j schema 初始化完成（约束 + FailureCause/Rule 字段补全）");
        } catch (Exception e) {
            log.warn("⚠️ Neo4j schema 初始化失败: {}", e.getMessage());
        }
    }
}
