package com.example.desktopbrain.memory.graph;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 失败模式 Neo4j Repository。
 */
@Repository
public interface FailurePatternRepository extends Neo4jRepository<FailurePatternNode, Long> {

    List<FailurePatternNode> findByType(String type);

    List<FailurePatternNode> findByCountGreaterThan(int minCount);
}
