package com.example.myhelper.novel;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;

public interface NovelPlotThreadRepository extends Neo4jRepository<NovelPlotThreadNode, Long> {

    @Query("MATCH (pt:NovelPlotThread {novelName: $novelName}) RETURN pt")
    List<NovelPlotThreadNode> findByNovelName(String novelName);

    @Query("MATCH (pt:NovelPlotThread {novelName: $novelName}) WHERE pt.status = $status RETURN pt")
    List<NovelPlotThreadNode> findByNovelNameAndStatus(String novelName, String status);

    @Query("MATCH (pt:NovelPlotThread {novelName: $novelName}) WHERE pt.status = 'PLANTED' OR pt.status = 'DEVELOPING' RETURN pt")
    List<NovelPlotThreadNode> findUnresolvedThreads(String novelName);

    @Query("MATCH (pt:NovelPlotThread {novelName: $novelName, threadName: $threadName}) RETURN pt")
    NovelPlotThreadNode findByName(String novelName, String threadName);

    @Query("MATCH (pt:NovelPlotThread {novelName: $novelName}) DETACH DELETE pt")
    void deleteByNovelName(String novelName);
}
