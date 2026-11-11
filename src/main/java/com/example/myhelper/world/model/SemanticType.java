package com.example.myhelper.world.model;

/**
 * 抽象语义的类型，专用于 WorldEntity.semanticType。
 * 当前只定义通用语义内容，后续按需要扩展分类，不预设语义层数或关系拓扑。
 */
public enum SemanticType {
    /** 可独立引用的抽象语义内容，原文、来源和向量保存在统一实体中。 */
    CONTENT
}
