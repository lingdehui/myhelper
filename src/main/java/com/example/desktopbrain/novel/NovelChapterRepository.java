package com.example.desktopbrain.novel;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;
import java.util.Optional;

public interface NovelChapterRepository extends Neo4jRepository<NovelChapterNode, Long> {

    @Query("MATCH (ch:NovelChapter {novelName: $novelName}) RETURN ch ORDER BY ch.chapterNumber")
    List<NovelChapterNode> findByNovelName(String novelName);

    @Query("MATCH (ch:NovelChapter {novelName: $novelName, chapterNumber: $chapterNumber}) RETURN ch")
    Optional<NovelChapterNode> findByNovelNameAndNumber(String novelName, int chapterNumber);

    @Query("MATCH (ch:NovelChapter {novelName: $novelName}) RETURN ch ORDER BY ch.chapterNumber DESC LIMIT 1")
    Optional<NovelChapterNode> findLatestChapter(String novelName);

    @Query("MATCH (ch:NovelChapter {novelName: $novelName}) RETURN count(ch)")
    long countByNovelName(String novelName);
}
