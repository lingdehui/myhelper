package com.example.myhelper.novel;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.Optional;

/**
 * 小说总纲的单文档图谱存储接口；每部作品至多维护一份主大纲。
 */
public interface NovelOutlineRepository extends Neo4jRepository<NovelOutlineNode, Long> {

    @Query("MATCH (o:NovelOutline {novelName: $novelName}) RETURN o")
    Optional<NovelOutlineNode> findByNovelName(String novelName);

    @Query("MATCH (o:NovelOutline {novelName: $novelName}) DETACH DELETE o")
    void deleteByNovelName(String novelName);
}
