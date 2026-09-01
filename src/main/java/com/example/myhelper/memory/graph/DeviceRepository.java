package com.example.myhelper.memory.graph;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 设备图谱的 Neo4j 查询边界。派生查询服务于属性匹配，显式 Cypher 查询服务于房间和控制关系遍历。
 */
@Repository
public interface DeviceRepository extends Neo4jRepository<DeviceNode, Long> {

    DeviceNode findByName(String name);

    List<DeviceNode> findByType(String type);

    List<DeviceNode> findByRoom(String room);

    /** 返回通过 LOCATED_IN 关系归属到指定房间的设备。 */
    @Query("MATCH (d:Device)-[:LOCATED_IN]->(room:Device) WHERE room.name = $roomName RETURN d")
    List<DeviceNode> findDevicesInRoom(String roomName);

    /** 以状态字段存在作为设备在线/可观测的最低判断，不额外推断其真实连通性。 */
    @Query("MATCH (d:Device {type: $type}) WHERE d.state IS NOT NULL RETURN d")
    List<DeviceNode> findOnlineDevicesByType(String type);

    /** 校验指定控制器与目标设备之间是否存在显式控制关系。 */
    @Query("MATCH (a:Device)-[r:CONTROLS]->(b:Device) WHERE a.name = $controllerName AND b.name = $targetName RETURN b")
    DeviceNode findControlledDevice(String controllerName, String targetName);
}
