package com.example.myhelper.novel;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;

import java.util.List;
import java.util.Optional;

/**
 * 小说分卷大纲的图谱访问接口，按卷号排序以匹配自然写作顺序。
 */
public interface NovelVolumeRepository extends Neo4jRepository<NovelVolumeNode, Long> {

    @Query("MATCH (v:NovelVolume {novelName: $novelName}) RETURN v ORDER BY v.volumeNumber")
    List<NovelVolumeNode> findByNovelName(String novelName);

    @Query("MATCH (v:NovelVolume {novelName: $novelName, volumeNumber: $volumeNumber}) RETURN v")
    Optional<NovelVolumeNode> findByNovelNameAndVolumeNumber(String novelName, int volumeNumber);

    @Query("MATCH (v:NovelVolume {novelName: $novelName}) DETACH DELETE v")
    void deleteByNovelName(String novelName);
}
