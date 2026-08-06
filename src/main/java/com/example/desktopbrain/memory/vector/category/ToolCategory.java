package com.example.desktopbrain.memory.vector.category;

import java.util.List;

/**
 * 工具分类（Qdrant 向量化存储）。
 *
 * <p>每个分类作为一个 point 存入 Qdrant {@code tool-categories} collection，
 * 向量 = 分类描述的 embedding。AI 规划时 embed 用户意图 → 向量搜索最匹配的分类 → 返回工具列表。</p>
 *
 * <p>支持交叉分类：一个工具可出现在多个 Category 中。</p>
 *
 * @param id          Qdrant point id（UUID 字符串）
 * @param name        分类名称（如"窗口管理"、"屏幕OCR"）
 * @param description 分类描述（用于 AI 规划和 embedding）
 * @param tools       属于此分类的工具名列表
 * @param toolCount   工具数量（冗余，用于过滤和排序）
 * @param version     分类版本（时间戳，用于增量更新检测）
 */
public record ToolCategory(
        String id,
        String name,
        String description,
        List<String> tools,
        int toolCount,
        long version
) {}
