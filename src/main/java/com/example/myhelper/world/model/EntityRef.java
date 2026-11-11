package com.example.myhelper.world.model;

/**
 * 对统一实体的引用。
 * 只保存目标编号、可选修订号及类型，不嵌入目标实体，允许分支和循环连接。
 * 四个类型字段与目标实体对应，必须且只能填写一个。
 */
public record EntityRef(
        /** 目标实体的稳定编号。 */
        String id,

        /**
         * 要固定的目标修订号；null 表示只引用身份。
         * 证据或需要复现的内容应填写准确修订号。
         */
        Long revision,

        /** 目标的世界对象类型；目标属于其他类别时为 null。 */
        WorldObjectType worldObjectType,

        /** 目标的世界陈述类型；目标属于其他类别时为 null。 */
        ContextUnitType contextUnitType,

        /** 目标的意图或能力类型；目标属于其他类别时为 null。 */
        UnitType unitType,

        /** 目标的抽象语义类型；目标属于其他类别时为 null。 */
        SemanticType semanticType) {
    public EntityRef {
        int typeCount = (worldObjectType == null ? 0 : 1)
                + (contextUnitType == null ? 0 : 1)
                + (unitType == null ? 0 : 1)
                + (semanticType == null ? 0 : 1);
        if (typeCount != 1) {
            throw new IllegalArgumentException("目标的四个类型字段必须且只能填写一个");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("目标 id 不能为空");
        }
        if (revision != null && revision < 1) {
            throw new IllegalArgumentException("目标 revision 必须从 1 开始");
        }
    }
}

