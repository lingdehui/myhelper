package com.example.myhelper.world;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 世界模型中的统一对象；对象可以递归包含对象，也可以归属于父对象。 */
public record WorldObject(
        String id,
        String type,
        String name,
        String description,
        String parentId,
        List<String> componentIds,
        Map<String, Object> attributes,
        Instant createdAt,
        Instant updatedAt) {

    public WorldObject {
        componentIds = componentIds == null ? List.of() : List.copyOf(componentIds);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
