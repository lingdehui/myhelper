package com.example.myhelper.service;

import com.example.myhelper.config.MyHelperProperties;
import com.example.myhelper.memory.vector.category.ToolCategoryService;

/**
 * 工具分类快速路由的纯判定策略。
 *
 * <p>该类不访问模型、数据库或网络。它只回答一个问题：当前索引召回是否足够可靠，
 * 可以安全地跳过模型逐层浏览，直接进入一次最终决策。任何一个条件不满足，调用方
 * 都必须保留原多轮目录浏览链路。</p>
 */
final class ToolRoutingPolicy {

    private ToolRoutingPolicy() {}

    /** 分类召回必须存在，且最高分达到配置下限。 */
    static boolean hasConfidentCategory(ToolCategoryService.CategoryCandidateRecall recall,
                                        MyHelperProperties.ToolPlanner.Routing routing) {
        return routing != null
                && recall != null
                && !recall.matchedCategories().isEmpty()
                && recall.bestScore() >= routing.categoryMinScore();
    }

    /** 候选集必须完整落在配置预算内，避免静默截断导致工具发现能力下降。 */
    static boolean hasBoundedCandidateSet(int candidateCount,
                                          MyHelperProperties.ToolPlanner.Routing routing) {
        return routing != null
                && candidateCount >= routing.minCandidateTools()
                && candidateCount <= routing.maxCandidateTools();
    }
}
