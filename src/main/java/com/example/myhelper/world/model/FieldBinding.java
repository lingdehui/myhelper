package com.example.myhelper.world.model;

import java.util.Objects;

/**
 * 把统一实体中的一个语义字段连接到其他实体。
 * 同一字段可连接多个目标，关系网允许任意深度、分支和循环。
 * 多个引用的组合含义由该字段定义。
 */
public record FieldBinding(
        /**
         * 从整个 WorldEntity 根部定位的 JSON Pointer 字段路径。
         * 例如 /text、/data/value、/data/attributes/room。
         * 字段名中的 ~ 写作 ~0，/ 写作 ~1，数组元素使用下标。
         *
         * 仅用于语义位置。精确编号、版本、证据、时间、来源和范围保留确定字段。
         */
        String slot,

        /** 被引用的目标实体，可以是世界对象、世界陈述、能力或抽象语义。 */
        EntityRef target) {
    public FieldBinding {
        if (slot == null || !slot.startsWith("/")) {
            throw new IllegalArgumentException("slot 必须是以 / 开头的字段路径");
        }
        Objects.requireNonNull(target, "target");
    }
}

