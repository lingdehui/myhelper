package com.example.myhelper.memory.unit;

/**
 * Unit 统一对象的类型标识（计划/步骤/工具三者统一抽象）。
 *
 * <p>对应文档 15 v1.7 §3：所有计划/步骤/工具都用一个统一对象表示，
 * 靠 {@code unitKind} 区分，格式完全一致。</p>
 *
 * <ul>
 *   <li>{@link #TOOL}：真正的原子工具，动态层，可删。</li>
 *   <li>{@link #PLAN_STEP}：计划或步骤，组合单元，动态层，可删。</li>
 *   <li>{@link #MCP_TOOL}：MCP 稳定工具，稳定基础设施，不可删。</li>
 * </ul>
 */
public enum UnitKind {
    /** 真正的原子工具，可删 */
    TOOL,
    /** 计划或步骤（组合单元），可删 */
    PLAN_STEP,
    /** MCP 稳定工具，不可删 */
    MCP_TOOL
}
