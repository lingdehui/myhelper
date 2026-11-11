package com.example.myhelper.world.model;

/** 意图与能力的类型，专用于 WorldEntity.unitType。 */
public enum UnitType {
    /** 抽象意图。 */
    INTENT,

    /** 原子工具。 */
    TOOL,

    /** MCP 原子工具。 */
    MCP_TOOL,

    /** 可复用的组合能力，不绑定旧执行器。 */
    PLAN_STEP,

    /** 长期目标或任务。 */
    LONG_TERM_TASK
}
