package com.example.myhelper.records;

import com.example.myhelper.orchestration.TaskPlan;

/** 宿主为本次记录写入提供的所有者身份；锁的取得和生命周期由宿主负责。 */
public record TaskRecordOwner(
        /** 同一宿主跨启动保持的稳定编号，不表示已取得分布式租约。 */
        String id) {
    public TaskRecordOwner {
        TaskPlan.requireText(id, "ownerId");
    }
}
