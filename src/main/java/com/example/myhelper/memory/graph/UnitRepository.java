package com.example.myhelper.memory.graph;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 统一 Unit Neo4j Repository（文档 15 v1.7 §4 / §8）。
 *
 * <p>正向有序引用（CONTAINS）、负向禁用（DISABLES）、降级替代（FALLBACK）
 * 均通过关系属性类表达。递归展开由 Service/Executor 层基于
 * {@link #findChildUnitIdsOrdered} 逐层加载并做深度与防环控制。</p>
 */
@Repository
public interface UnitRepository extends Neo4jRepository<UnitNode, String> {

    @Query("MATCH (u:Unit {unitId: $unitId}) RETURN u")
    Optional<UnitNode> findByUnitId(String unitId);

    @Query("MATCH (u:Unit {unitKind: $unitKind, status: 'ACTIVE'}) RETURN u")
    List<UnitNode> findActiveByUnitKind(String unitKind);

    @Query("MATCH (u:Unit {status: 'ACTIVE'}) WHERE u.unitKind IN $kinds RETURN u")
    List<UnitNode> findActiveByUnitKinds(List<String> kinds);

    /** 直接子单元 unitId，按 CONTAINS.order 升序返回（供递归展开使用）。 */
    @Query("MATCH (u:Unit {unitId: $unitId})-[r:CONTAINS]->(c:Unit) " +
            "RETURN c.unitId AS childId ORDER BY r.order")
    List<String> findChildUnitIdsOrdered(String unitId);

    /** 所有后代（变量长度 1..10），用于防环校验与结构校验。 */
    @Query("MATCH (u:Unit {unitId: $unitId})-[:CONTAINS*1..10]->(d:Unit) " +
            "RETURN DISTINCT d.unitId")
    List<String> findDescendantUnitIds(String unitId);

    /** 被本 Unit 禁用的目标 unitId（无序）。 */
    @Query("MATCH (u:Unit {unitId: $unitId})-[r:DISABLES]->(c:Unit) RETURN c.unitId")
    List<String> findDisabledUnitIds(String unitId);

    /** 本 Unit 的降级替代目标 unitId，按 FALLBACK.priority 升序。 */
    @Query("MATCH (u:Unit {unitId: $unitId})-[r:FALLBACK]->(c:Unit) " +
            "RETURN c.unitId AS targetId ORDER BY r.priority")
    List<String> findFallbackUnitIds(String unitId);

    /** 反向查询：谁禁用了指定 Unit（用于复用计划时判断是否应跳过）。 */
    @Query("MATCH (u:Unit)-[r:DISABLES]->(c:Unit {unitId: $unitId}) RETURN u.unitId")
    List<String> findDisablersOf(String unitId);

    /** 按 toolName 复用已注册的 TOOL Unit（叶子工具节点）。 */
    @Query("MATCH (u:Unit {unitKind: 'TOOL', toolName: $toolName, status: 'ACTIVE'}) RETURN u LIMIT 1")
    Optional<UnitNode> findByToolName(String toolName);

    /** 反向查询：哪些 Unit CONTAINS 指定 Unit（失败达阈值时给父级建 DISABLES 边）。 */
    @Query("MATCH (p:Unit)-[:CONTAINS]->(c:Unit {unitId: $unitId}) RETURN p.unitId")
    List<String> findParentsOf(String unitId);
}
