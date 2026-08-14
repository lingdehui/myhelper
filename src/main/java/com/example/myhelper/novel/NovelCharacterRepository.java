package com.example.myhelper.novel;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;
import java.util.Optional;

public interface NovelCharacterRepository extends Neo4jRepository<NovelCharacterNode, Long> {

    @Query("MATCH (c:NovelCharacter {novelName: $novelName}) RETURN c")
    List<NovelCharacterNode> findByNovelName(String novelName);

    @Query("MATCH (c:NovelCharacter {novelName: $novelName, name: $name}) RETURN c")
    Optional<NovelCharacterNode> findByNovelNameAndName(String novelName, String name);

    @Query("MATCH (c:NovelCharacter {novelName: $novelName}) WHERE c.role = $role RETURN c")
    List<NovelCharacterNode> findByNovelNameAndRole(String novelName, String role);

    @Query("MATCH (c:NovelCharacter {novelName: $novelName})-[r:RELATED_TO]->(t:NovelCharacter) RETURN c, r, t")
    List<NovelCharacterNode> findWithRelationships(String novelName);
}
