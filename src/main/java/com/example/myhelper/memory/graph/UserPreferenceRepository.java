package com.example.myhelper.memory.graph;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserPreferenceRepository extends Neo4jRepository<UserPreferenceNode, Long> {

    List<UserPreferenceNode> findByCategory(String category);

    List<UserPreferenceNode> findByKey(String key);

    @Query("MATCH (p:UserPreference {category: $category}) WHERE p.trigger IS NOT NULL AND p.confidence >= $minConfidence RETURN p")
    List<UserPreferenceNode> findActivePreferences(String category, double minConfidence);

    @Query("MATCH (p:UserPreference) WHERE p.trigger CONTAINS $condition RETURN p")
    List<UserPreferenceNode> findByTriggerCondition(String condition);
}