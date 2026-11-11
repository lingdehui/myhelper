package com.example.myhelper.world.model;

import java.util.List;

/**
 * 某个统计范围内已记录的执行结果，供链路、Unit 及链路中的使用位置共同使用。
 * 只保存成功、失败和归因数据，不自动计数、不传播结果、不计算可靠度。
 * 未执行、尚未确认结果等情况不应被写成失败；具体记账规则由后续业务模块处理。
 */
public record ExecutionStatistics(
        /** 已确认成功的次数，不能为负数；0 表示尚未记录成功结果。 */
        long successCount,

        /** 已确认失败的次数，不能为负数；0 表示尚未记录失败结果。 */
        long failureCount,

        /**
         * 已记录的失败归因汇总；同一原因可通过 occurrenceCount 记录出现次数。
         * 空列表表示尚未记录归因，不表示没有失败。
         * 一次失败可以有多个原因，也可能尚未归因，因此归因次数之和不要求等于失败次数。
         */
        List<FailureCause> failureCauses) {
    public ExecutionStatistics {
        if (successCount < 0 || failureCount < 0) {
            throw new IllegalArgumentException("成功次数和失败次数不能为负数");
        }
        failureCauses = failureCauses == null ? List.of() : List.copyOf(failureCauses);
    }

    /** 创建尚无已记录结果的统计，不据此推定该能力从未执行或可靠度为零。 */
    public static ExecutionStatistics empty() {
        return new ExecutionStatistics(0, 0, List.of());
    }
}
