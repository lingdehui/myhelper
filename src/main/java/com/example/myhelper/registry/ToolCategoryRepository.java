package com.example.myhelper.registry;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ToolCategoryRepository extends Neo4jRepository<ToolCategoryNode, String> {
}
