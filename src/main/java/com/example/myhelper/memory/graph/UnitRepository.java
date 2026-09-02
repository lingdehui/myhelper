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

    /**
     * 一次性拉取所有 PLAN_STEP 的直接 CONTAINS 子节点（父 unitId + 子节点概要），
     * 替代 {@link #findChildUnitIdsOrdered} + 逐个 {@link #findByUnitId} 的 N+1 查询。
     * <p>单列拼接返回（parentId|childId|toolName|goal），规避 SDN 多列投影映射限制，
     * toolName/goal 为 null 时用空串占位。</p>
     */
    @Query("MATCH (u:Unit {unitKind: 'PLAN_STEP', status: 'ACTIVE'})-[r:CONTAINS]->(c:Unit) " +
            "RETURN u.unitId + '|' + c.unitId + '|' + coalesce(c.toolName, '') + '|' + coalesce(c.goal, '') AS row " +
            "ORDER BY u.unitId, r.order")
    List<String> findPlanStepChildRows();

    /** parentId|relationshipElementId|order|childId|toolName|argumentsBase64，供离线公共路径归纳。 */
    @Query("MATCH (p:Unit {unitKind: 'PLAN_STEP', status: 'ACTIVE'})-[r:CONTAINS]->(c:Unit {status: 'ACTIVE'}) " +
            "RETURN p.unitId + '|' + elementId(r) + '|' + toString(r.order) + '|' + c.unitId + '|' + " +
            "coalesce(c.toolName, '') + '|' + coalesce(r.argumentsBase64, '') AS row " +
            "ORDER BY p.unitId, r.order")
    List<String> findDirectInvocationRowsForCompaction();

    /** 原子地用一个公共 PLAN_STEP 替换父 Unit 中的一段旧 CONTAINS 关系。 */
    @Query("MATCH (p:Unit {unitId: $parentId}), (f:Unit {unitId: $fragmentId}) " +
            "MATCH (p)-[old:CONTAINS]->() WHERE elementId(old) IN $relationshipIds " +
            "WITH p, f, collect(old) AS olds " +
            "FOREACH (r IN olds | DELETE r) " +
            "CREATE (p)-[:CONTAINS {order: $order, argumentsBase64: $argumentsBase64}]->(f) " +
            "RETURN size(olds)")
    int replaceInvocationSpan(String parentId, List<String> relationshipIds, String fragmentId,
                              int order, String argumentsBase64);

    /** 直接子单元 unitId，按 CONTAINS.order 升序返回（供递归展开使用）。 */
    @Query("MATCH (u:Unit {unitId: $unitId})-[r:CONTAINS]->(c:Unit) " +
            "RETURN c.unitId AS childId ORDER BY r.order")
    List<String> findChildUnitIdsOrdered(String unitId);

    /** parentId|childId|argumentsBase64，供唯一的 Unit 树执行器加载步骤和实参。 */
    @Query("MATCH (u:Unit {unitId: $unitId})-[r:CONTAINS]->(c:Unit) " +
            "RETURN u.unitId + '|' + c.unitId + '|' + coalesce(r.argumentsBase64, '') AS row ORDER BY r.order")
    List<String> findChildInvocationRows(String unitId);

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
