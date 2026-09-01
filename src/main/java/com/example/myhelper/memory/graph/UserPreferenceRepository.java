package com.example.myhelper.memory.graph;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 用户偏好图谱的查询接口。调用方应依据置信度过滤推断型偏好，避免将一次性行为固化为长期偏好。
 */
@Repository
public interface UserPreferenceRepository extends Neo4jRepository<UserPreferenceNode, Long> {

    List<UserPreferenceNode> findByCategory(String category);

    List<UserPreferenceNode> findByKey(String key);

    /** 获取满足触发条件且可信度不低于阈值的可执行偏好。 */
    @Query("MATCH (p:UserPreference {category: $category}) WHERE p.trigger IS NOT NULL AND p.confidence >= $minConfidence RETURN p")
    List<UserPreferenceNode> findActivePreferences(String category, double minConfidence);

    @Query("MATCH (p:UserPreference) WHERE p.trigger CONTAINS $condition RETURN p")
    List<UserPreferenceNode> findByTriggerCondition(String condition);
}
