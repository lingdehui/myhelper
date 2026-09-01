package com.example.myhelper.novel;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;
import java.util.Optional;

/**
 * 小说章节的图谱持久化接口。所有自定义查询都以 {@code novelName} 作为隔离键，避免不同作品串数据。
 */
public interface NovelChapterRepository extends Neo4jRepository<NovelChapterNode, Long> {

    @Query("MATCH (ch:NovelChapter {novelName: $novelName}) RETURN ch ORDER BY ch.chapterNumber")
    List<NovelChapterNode> findByNovelName(String novelName);

    @Query("MATCH (ch:NovelChapter {novelName: $novelName, chapterNumber: $chapterNumber}) RETURN ch ORDER BY ch.updatedAt DESC")
    List<NovelChapterNode> findByNovelNameAndNumber(String novelName, int chapterNumber);

    @Query("MATCH (ch:NovelChapter {novelName: $novelName}) RETURN ch ORDER BY ch.chapterNumber DESC LIMIT 1")
    Optional<NovelChapterNode> findLatestChapter(String novelName);

    @Query("MATCH (ch:NovelChapter {novelName: $novelName}) RETURN count(ch)")
    long countByNovelName(String novelName);

    /** 删除一部小说全部章节及其关系；调用前必须由服务层完成删除确认。 */
    @Query("MATCH (ch:NovelChapter {novelName: $novelName}) DETACH DELETE ch")
    void deleteByNovelName(String novelName);
}
