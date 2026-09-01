package com.example.myhelper.novel;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;
import java.util.Optional;

/**
 * 小说人物与人物关系的图谱访问接口，以作品名作为数据分区键。
 */
public interface NovelCharacterRepository extends Neo4jRepository<NovelCharacterNode, Long> {

    @Query("MATCH (c:NovelCharacter {novelName: $novelName}) RETURN c")
    List<NovelCharacterNode> findByNovelName(String novelName);

    @Query("MATCH (c:NovelCharacter {novelName: $novelName, name: $name}) RETURN c")
    Optional<NovelCharacterNode> findByNovelNameAndName(String novelName, String name);

    @Query("MATCH (c:NovelCharacter {novelName: $novelName}) WHERE c.role = $role RETURN c")
    List<NovelCharacterNode> findByNovelNameAndRole(String novelName, String role);

    /** 连同 RELATED_TO 关系加载人物，供写作上下文一次性构建人物网络。 */
    @Query("MATCH (c:NovelCharacter {novelName: $novelName})-[r:RELATED_TO]->(t:NovelCharacter) RETURN c, r, t")
    List<NovelCharacterNode> findWithRelationships(String novelName);

    @Query("MATCH (c:NovelCharacter {novelName: $novelName}) DETACH DELETE c")
    void deleteByNovelName(String novelName);
}
