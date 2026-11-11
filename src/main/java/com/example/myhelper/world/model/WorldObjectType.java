package com.example.myhelper.world.model;

/** 世界对象的类型，专用于 WorldEntity.worldObjectType。 */
public enum WorldObjectType {
    /** 用户或其他人。 */
    USER,

    /** 房间或空间。 */
    ROOM,

    /** 设备。 */
    DEVICE,

    /** 应用程序。 */
    APPLICATION,

    /** 文件。 */
    FILE,

    /** 系统。 */
    SYSTEM,

    /** MyHelper 自身。 */
    MYHELPER
}
