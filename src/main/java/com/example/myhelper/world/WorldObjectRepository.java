package com.example.myhelper.world;

import org.springframework.data.neo4j.repository.Neo4jRepository;

public interface WorldObjectRepository extends Neo4jRepository<WorldObjectNode, String> {}
