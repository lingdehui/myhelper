package com.example.myhelper.world.model;

import java.util.List;

/**
 * 一个实体在某个固定版本固化链路中的使用位置。
 * 同一个 Unit 被同一条链路多次使用时，分别保存对应的位置记录，不复制 Unit 定义。
 * 本结构保存定位与该位置的统计，不保存整条链路，也不自动记账、维护反向索引或展开节点。
 */
public record ChainPosition(
        /** 直接包含本次使用位置的固化链路编号，即对应组合 Unit 的稳定 id。 */
        String chainId,

        /** 该固化链路的精确修订号，从 1 开始，避免把不同版本的位置混在一起。 */
        long chainRevision,

        /**
         * 该链路版本中从外到内的分支编号路径，例如 ["branch-1", "branch-1-2"]。
         * 主干位置使用空列表；null 也按空列表保存。分支编号不能为 null 或空白。
         * 路径只记录分支归属，不据此推断执行依赖、并发或条件选择规则。
         */
        List<String> branchPath,

        /**
         * 当前实体在该链路中的一次出现编号，在对应链路版本内定位。
         * 例如 n7、n12；不是被调用 Unit 自身的 id，也不是可随排序变化的数组下标。
         * 同一 Unit 在同链多个位置出现时，分别填写不同 nodeId。
         */
        String nodeId,

        /**
         * 所属实体版本在 chainId/chainRevision/nodeId 这个使用位置的执行统计。
         * 整条链路失败时，这个位置仍可记录成功；未执行的位置保持原统计。
         * 与整链及 Unit 总体统计分别保存，不自动向上或向下传播结果。
         * null 保存为空统计；计数、归因和可靠度公式均由后续业务模块处理。
         */
        ExecutionStatistics statistics) {
    public ChainPosition {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        if (chainRevision < 1) {
            throw new IllegalArgumentException("chainRevision 必须从 1 开始");
        }
        branchPath = branchPath == null ? List.of() : List.copyOf(branchPath);
        for (String branchId : branchPath) {
            if (branchId.isBlank()) {
                throw new IllegalArgumentException("branchPath 中的分支编号不能为空白");
            }
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId 不能为空");
        }
        statistics = statistics == null ? ExecutionStatistics.empty() : statistics;
    }

    /** 兼容原四参数位置构造方式，尚无已记录执行结果。 */
    public ChainPosition(String chainId, long chainRevision, List<String> branchPath, String nodeId) {
        this(chainId, chainRevision, branchPath, nodeId, ExecutionStatistics.empty());
    }
}
