package com.example.myhelper.novel;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;

/**
 * 情节线与伏笔的图谱访问接口。状态查询用于在生成新章节前取回尚未解决的叙事约束。
 */
public interface NovelPlotThreadRepository extends Neo4jRepository<NovelPlotThreadNode, Long> {

    @Query("MATCH (pt:NovelPlotThread {novelName: $novelName}) RETURN pt")
    List<NovelPlotThreadNode> findByNovelName(String novelName);

    @Query("MATCH (pt:NovelPlotThread {novelName: $novelName}) WHERE pt.status = $status RETURN pt")
    List<NovelPlotThreadNode> findByNovelNameAndStatus(String novelName, String status);

    /** 仅返回尚未回收的情节线，避免已解决伏笔干扰当前写作上下文。 */
    @Query("MATCH (pt:NovelPlotThread {novelName: $novelName}) WHERE pt.status = 'PLANTED' OR pt.status = 'DEVELOPING' RETURN pt")
    List<NovelPlotThreadNode> findUnresolvedThreads(String novelName);

    @Query("MATCH (pt:NovelPlotThread {novelName: $novelName, threadName: $threadName}) RETURN pt")
    NovelPlotThreadNode findByName(String novelName, String threadName);

    @Query("MATCH (pt:NovelPlotThread {novelName: $novelName}) DETACH DELETE pt")
    void deleteByNovelName(String novelName);
}
