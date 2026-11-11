package com.example.myhelper.world.model;

/** 失败归因类型，沿用旧项目区分计划问题与环境问题的方式；不在此定义惩罚规则。 */
public enum FailureCategory {
    /** 计划、参数绑定、步骤依赖或能力实现方式存在问题。 */
    PLAN,

    /** 网络、设备、权限、外部服务等执行环境问题。 */
    ENVIRONMENT,

    /** 原因尚未查明或现有证据不足以分类。 */
    UNKNOWN
}
