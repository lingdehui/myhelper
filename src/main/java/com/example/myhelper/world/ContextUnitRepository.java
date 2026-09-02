package com.example.myhelper.world;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;
import java.util.Optional;

public interface ContextUnitRepository extends Neo4jRepository<ContextUnitNode, String> {
    @Query("MATCH (c:ContextUnit) WHERE c.active = true " +
           "AND (c.validUntil IS NULL OR c.validUntil > $now) " +
           "RETURN c ORDER BY c.updatedAt DESC LIMIT $limit")
    List<ContextUnitNode> findCurrent(long now, long limit);

    @Query("MATCH (c:ContextUnit {subjectId: $subjectId, predicate: $predicate, active: true}) " +
           "WHERE c.contextRole IN ['OBSERVATION', 'FACT', 'INFERENCE', 'PERSONALITY'] " +
           "AND c.stateId IS NOT NULL AND (c.validUntil IS NULL OR c.validUntil > $now) " +
           "RETURN c ORDER BY coalesce(c.observedAt, 0) DESC, c.updatedAt DESC LIMIT 1")
    Optional<ContextUnitNode> findLatestEvidence(String subjectId, String predicate, long now);

    @Query("MATCH (c:ContextUnit {contextRole: 'OBSERVATION', active: true}) " +
           "WHERE c.validUntil IS NOT NULL AND c.validUntil <= $now " +
           "SET c.active = false, c.updatedAt = $now RETURN count(c)")
    long archiveExpiredObservations(long now);

    @Query("MATCH (c:ContextUnit {contextRole: 'OBSERVATION', active: false}) " +
           "WHERE c.validUntil IS NOT NULL AND c.validUntil < $cutoff " +
           "WITH collect(c) AS expired FOREACH (c IN expired | DETACH DELETE c) RETURN size(expired)")
    long deleteArchivedObservationsBefore(long cutoff);

    @Query("MATCH (c:ContextUnit {contextRole: $role, active: true}) RETURN c")
    List<ContextUnitNode> findActiveByRole(String role);
}
