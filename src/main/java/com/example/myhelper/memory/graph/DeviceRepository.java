package com.example.myhelper.memory.graph;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviceRepository extends Neo4jRepository<DeviceNode, Long> {

    DeviceNode findByName(String name);

    List<DeviceNode> findByType(String type);

    List<DeviceNode> findByRoom(String room);

    @Query("MATCH (d:Device)-[:LOCATED_IN]->(room:Device) WHERE room.name = $roomName RETURN d")
    List<DeviceNode> findDevicesInRoom(String roomName);

    @Query("MATCH (d:Device {type: $type}) WHERE d.state IS NOT NULL RETURN d")
    List<DeviceNode> findOnlineDevicesByType(String type);

    @Query("MATCH (a:Device)-[r:CONTROLS]->(b:Device) WHERE a.name = $controllerName AND b.name = $targetName RETURN b")
    DeviceNode findControlledDevice(String controllerName, String targetName);
}