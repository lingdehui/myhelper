package com.example.desktopbrain.integration;

import java.util.List;
import java.util.Map;

/**
 * Home Assistant 数据 DTO。
 * <p>替代 HomeAssistantClient 中裸 {@code Map<String, Object>} 返回类型。</p>
 */
public record HaDtos() {

    /** 实体状态 */
    public record DeviceState(
            String entityId,
            String state,
            Map<String, Object> attributes
    ) {}

    /** 实体基础信息 */
    public record EntityInfo(
            String entityId,
            String domain,
            String name
    ) {}

    /** 设备发现结果 */
    public record DiscoveryResult(
            List<DeviceState> registered,
            List<DeviceState> failed,
            String summary
    ) {}
}
