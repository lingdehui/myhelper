package com.example.myhelper.registry;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.stereotype.Repository;

/**
 * 工具分类节点的基础仓储。复杂的分类同步和检索策略保留在服务层实现。
 */
@Repository
public interface ToolCategoryRepository extends Neo4jRepository<ToolCategoryNode, String> {
}
