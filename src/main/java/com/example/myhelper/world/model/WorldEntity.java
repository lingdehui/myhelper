package com.example.myhelper.world.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 世界模型的统一实体。
 *
 * <p>用户、设备、观测、事实、工具、意图和抽象语义都使用本类，
 * 分别使用 worldObjectType、contextUnitType、unitType、semanticType 四个类型字段。
 * 每个实体只填写其中一个，其余为 null。类型相关内容统一放在 data 中。</p>
 *
 * <p>任意语义字段都可以同时保留直接值和多个实体引用。
 * 只有抽象表达时，直接值可以为 null。引用通过稳定编号连接，
 * 支持任意层数、分支和循环。</p>
 */
public record WorldEntity(
        /** 实体稳定编号；内容修订时保持不变。 */
        String id,

        /** 世界对象类型，例如 USER、DEVICE；非世界对象时为 null。 */
        WorldObjectType worldObjectType,

        /** 世界陈述类型，例如 OBSERVATION、FACT；非世界陈述时为 null。 */
        ContextUnitType contextUnitType,

        /** 能力类型，例如 TOOL、PLAN_STEP、INTENT；非能力实体时为 null。 */
        UnitType unitType,

        /** 抽象语义类型；非语义实体时为 null。四个类型字段中必须且只能填写一个。 */
        SemanticType semanticType,

        /** 名称或标题；尚无确定名称时为 null。 */
        String name,

        /** 原始描述或语义原文；暂无原文时为 null，不自动改写。 */
        String text,

        /**
         * 类型相关字段，使用 JSON 兼容的键值数据：
         * 用户/设备保存属性、组成、位置等；
         * 观测/事实保存 subject、predicate、object、value、时间、置信度等；
         * 工具/能力保存输入输出、工具版本、条件、预期、验证范围等；
         * 抽象语义保存标签和补充描述等。
         *
         * 值支持字符串、布尔、数字、null、嵌套 Map 和 List。
         * 只有抽象引用时，可以保留字段并将直接值设为 null。
         * 构造时复制顶层映射，嵌套内容由写入方维护。
         */
        Map<String, Object> data,

        /**
         * 字段引用数组；同一 slot 可以连接多个目标，也可以保留原字段的直接值。
         * 所有类型均能持有引用并被其他实体引用。
         */
        List<FieldBinding> bindings,

        /**
         * 当前内容的向量表示；未生成时为 null。
         * 任意类型均可带有向量，具有向量不改变实体类型。
         */
        Embedding embedding,

        /** 当前内容修订号，从 1 开始；与工具版本和向量模型版本分别保存。 */
        long revision,

        /**
         * 原始来源的准确定位，例如消息编号、文档路径、URL 或调用记录编号。
         * 未知时为 null，不伪造来源。
         */
        String source,

        /**
         * 支持当前内容的证据引用；需要精确复现时固定目标 revision。
         * 空列表表示尚未记录证据，不自动认定内容已经成立。
         */
        List<EntityRef> evidence,

        /**
         * 内容或能力的明确适用范围。
         * 空映射表示范围未声明，不表示无限适用。
         * 构造时复制顶层映射，嵌套内容由写入方维护。
         */
        Map<String, Object> scope,

        /** 实体首次创建时间；导入数据未提供时为 null，不自动补造历史时间。 */
        Instant createdAt,

        /** 当前修订更新时间；未知时为 null，与实际观测时间分别保存。 */
        Instant updatedAt,

        /**
         * 当前实体被固化链路使用的位置数组，即“链路编号、链路版本、分支路径、位置编号”。
         * 同一实体被多条链路或同链多个位置使用时，分别保留记录。
         * null 按空列表保存；空列表表示尚未记录使用位置，不证明没有链路引用本实体。
         * 定位信息可由链路结构派生，位置内的执行统计需保留，不能因重建索引被清空。
         * 完整节点及连接仍由链路组合 Unit 的定义保存。
         * 构造器只保存并复制数组，不读取链路、不自动更新引用，也不保证历史版本可读取。
         */
        List<ChainPosition> chainPositions,

        /**
         * 当前实体 id/revision 的执行统计；组合 Unit 保存整条链路的结果，
         * 原子 Unit 可保存该版本能力跨使用位置的总体结果。
         * 每个具体链路位置的结果另存于对应 ChainPosition.statistics，避免混算。
         * null 保存为空统计；不自动累加、不计算可靠度，也不因记账自动修改 revision。
         */
        ExecutionStatistics statistics) {
    public WorldEntity {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id 不能为空");
        }
        int typeCount = (worldObjectType == null ? 0 : 1)
                + (contextUnitType == null ? 0 : 1)
                + (unitType == null ? 0 : 1)
                + (semanticType == null ? 0 : 1);
        if (typeCount != 1) {
            throw new IllegalArgumentException("四个类型字段必须且只能填写一个");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision 必须从 1 开始");
        }
        data = data == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        scope = scope == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(scope));
        chainPositions = chainPositions == null ? List.of() : List.copyOf(chainPositions);
        statistics = statistics == null ? ExecutionStatistics.empty() : statistics;
    }

    /**
     * 兼容未传入链路位置数组的原构造方式。
     * 原有参数含义与对应字段相同，chainPositions 默认为空列表，statistics 默认为空统计。
     */
    public WorldEntity(
            String id,
            WorldObjectType worldObjectType,
            ContextUnitType contextUnitType,
            UnitType unitType,
            SemanticType semanticType,
            String name,
            String text,
            Map<String, Object> data,
            List<FieldBinding> bindings,
            Embedding embedding,
            long revision,
            String source,
            List<EntityRef> evidence,
            Map<String, Object> scope,
            Instant createdAt,
            Instant updatedAt) {
        this(id, worldObjectType, contextUnitType, unitType, semanticType,
                name, text, data, bindings, embedding, revision, source,
                evidence, scope, createdAt, updatedAt, List.of(), ExecutionStatistics.empty());
    }

    /** 兼容仅传入链路位置数组的原 17 参数构造方式，statistics 默认为空统计。 */
    public WorldEntity(
            String id,
            WorldObjectType worldObjectType,
            ContextUnitType contextUnitType,
            UnitType unitType,
            SemanticType semanticType,
            String name,
            String text,
            Map<String, Object> data,
            List<FieldBinding> bindings,
            Embedding embedding,
            long revision,
            String source,
            List<EntityRef> evidence,
            Map<String, Object> scope,
            Instant createdAt,
            Instant updatedAt,
            List<ChainPosition> chainPositions) {
        this(id, worldObjectType, contextUnitType, unitType, semanticType,
                name, text, data, bindings, embedding, revision, source,
                evidence, scope, createdAt, updatedAt, chainPositions, ExecutionStatistics.empty());
    }
}

