package com.example.myhelper.memory.graph;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 通用规则 Neo4j Repository。
 */
@Repository
public interface RuleRepository extends Neo4jRepository<RuleNode, Long> {

    /** 查询所有启用的规则，按置信度降序 */
    List<RuleNode> findByEnabledTrueOrderByConfidenceDesc();

    /** 查询所有规则 */
    List<RuleNode> findAllByOrderByCreatedDesc();
}
