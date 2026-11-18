package com.example.myhelper.orchestration;

import java.util.Map;

/** 为当前任务的一轮规划提供世界上下文。 */
@FunctionalInterface
public interface WorldContextPort {
    /**
     * 按本轮任务身份、参数和授权范围组装 JSON 兼容的证据。实现要适应并发子任务，
     * 并在自己的查询预算内完成。本轮请求上的 worldContext 为空，返回值由编排器深复制。
     */
    Map<String, Object> snapshot(AgentOrchestrator.PlanningContext context);

    /** 宿主未装配世界上下文提供方时使用空快照。 */
    static WorldContextPort disabled() {
        return WorldContextPort::emptySnapshot;
    }

    private static Map<String, Object> emptySnapshot(AgentOrchestrator.PlanningContext context) {
        return Map.of();
    }
}
