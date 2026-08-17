package com.example.myhelper.schedule;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScheduledTaskRepository extends Neo4jRepository<ScheduledTaskNode, String> {

    List<ScheduledTaskNode> findByEnabledTrue();

    Optional<ScheduledTaskNode> findByTaskId(String taskId);
}
