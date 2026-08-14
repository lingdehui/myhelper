package com.example.myhelper.memory.graph;

import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.HashSet;
import java.util.Set;

/**
 * 知识图谱节点：设备实体
 * 记录全屋所有物理设备（电脑、灯、空调、机械臂等）
 */
@Node("Device")
public class DeviceNode {

    @Id
    @GeneratedValue
    private Long id;

    /** 设备名称，如 "客厅灯"、"机械臂A" */
    private String name;

    /** 设备类型：computer / light / ac / curtain / arm / speaker */
    private String type;

    /** 设备所在房间 */
    private String room;

    /** 通信协议：mqtt / zigbee / modbus / rest */
    private String protocol;

    /** 设备状态（JSON），如 {"power":"on","brightness":80} */
    private String state;

    /** 最后在线时间 */
    private Long lastSeen;

    /** 设备关联关系 */
    @Relationship(type = "CONTROLS", direction = Relationship.Direction.OUTGOING)
    private Set<DeviceNode> controls = new HashSet<>();

    @Relationship(type = "LOCATED_IN", direction = Relationship.Direction.OUTGOING)
    private DeviceNode location;

    public DeviceNode() {}

    public DeviceNode(String name, String type, String room) {
        this.name = name;
        this.type = type;
        this.room = room;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getRoom() { return room; }
    public void setRoom(String room) { this.room = room; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Long getLastSeen() { return lastSeen; }
    public void setLastSeen(Long lastSeen) { this.lastSeen = lastSeen; }

    public Set<DeviceNode> getControls() { return controls; }
    public void setControls(Set<DeviceNode> controls) { this.controls = controls; }

    public DeviceNode getLocation() { return location; }
    public void setLocation(DeviceNode location) { this.location = location; }
}