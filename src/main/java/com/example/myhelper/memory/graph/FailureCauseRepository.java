package com.example.myhelper.memory.graph;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 失败原因 Neo4j Repository（文档 15 v1.7 §5 / §6）。
 *
 * <p>FailureCause 复用策略：先查库，有则引用并加计数，无则新建。</p>
 */
@Repository
public interface FailureCauseRepository extends Neo4jRepository<FailureCauseNode, String> {

    @Query("MATCH (f:FailureCause {causeId: $causeId}) RETURN f")
    Optional<FailureCauseNode> findByCauseId(String causeId);

    @Query("MATCH (f:FailureCause {reason: $reason}) RETURN f")
    Optional<FailureCauseNode> findByReason(String reason);

    @Query("MATCH (f:FailureCause {category: $category}) RETURN f ORDER BY f.timestamp DESC")
    List<FailureCauseNode> findByCategoryOrderByTimestampDesc(String category);

    @Query("MATCH (f:FailureCause) WHERE f.suggestedUnitIdsJson CONTAINS $unitId RETURN f")
    List<FailureCauseNode> findSuggestedByUnitId(String unitId);
}
