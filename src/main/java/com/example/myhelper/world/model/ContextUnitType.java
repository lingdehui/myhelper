package com.example.myhelper.world.model;

/** 世界陈述的类型，专用于 WorldEntity.contextUnitType。 */
public enum ContextUnitType {
    /** 标准状态定义；定义存在不表示该状态当前成立。 */
    STATE,

    /** 带时间和来源的观测。 */
    OBSERVATION,

    /** 事实陈述；可信性仍取决于证据、时效和适用范围。 */
    FACT,

    /** 执行前需要满足的条件。 */
    REQUIREMENT,

    /** 执行后需要验收的结果。 */
    EXPECTATION,

    /** 根据证据形成的推断。 */
    INFERENCE,

    /** 人格或行为倾向描述。 */
    PERSONALITY
}
