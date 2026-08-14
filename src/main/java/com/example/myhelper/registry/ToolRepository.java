package com.example.myhelper.registry;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ToolRepository extends Neo4jRepository<ToolNode, String> {

    @Query("MATCH (t:Tool {status: 'ACTIVE', callable: true}) RETURN t")
    List<ToolNode> findAllActive();

    @Query("MATCH (t:Tool) WHERE t.status = 'ACTIVE' AND t.callable = true RETURN t ORDER BY t.name")
    List<ToolNode> findAllActiveOrdered();

    @Query("MATCH (t:Tool {status: 'ACTIVE'}) RETURN t")
    List<ToolNode> findAllIncludingDisabled();
}
