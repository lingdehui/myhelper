package com.example.myhelper.registry;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 工具注册表的持久化接口。ACTIVE 与 callable 两个条件共同决定工具是否允许进入规划目录。
 */
@Repository
public interface ToolRepository extends Neo4jRepository<ToolNode, String> {

    /** 返回可被实际调用的活跃工具，供执行前校验使用。 */
    @Query("MATCH (t:Tool {status: 'ACTIVE', callable: true}) RETURN t")
    List<ToolNode> findAllActive();

    @Query("MATCH (t:Tool) WHERE t.status = 'ACTIVE' AND t.callable = true RETURN t ORDER BY t.name")
    List<ToolNode> findAllActiveOrdered();

    @Query("MATCH (t:Tool {status: 'ACTIVE'}) RETURN t")
    List<ToolNode> findAllIncludingDisabled();
}
