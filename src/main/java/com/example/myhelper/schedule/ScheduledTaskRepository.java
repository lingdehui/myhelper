package com.example.myhelper.schedule;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 定时任务的 Neo4j 存储接口。调度器只拉取已启用的任务，禁用任务仍保留以便恢复或审计。
 */
@Repository
public interface ScheduledTaskRepository extends Neo4jRepository<ScheduledTaskNode, String> {

    List<ScheduledTaskNode> findByEnabledTrue();

    Optional<ScheduledTaskNode> findByTaskId(String taskId);
}
