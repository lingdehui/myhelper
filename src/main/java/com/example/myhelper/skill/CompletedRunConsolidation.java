package com.example.myhelper.skill;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** 处理已封闭的用户运行。仓储保存待处理项，线程提交只负责唤醒。 */
public final class CompletedRunConsolidation {
    /** 独立的固化执行器。 */
    private final Executor executor;
    /** 权威来源及固化写入端口。 */
    private final Store store;
    /** 新技能候选生成服务。 */
    private final SkillConsolidationService service;

    public CompletedRunConsolidation(Executor executor, Store store, SkillConsolidationService service) {
        this.executor = Objects.requireNonNull(executor);
        this.store = Objects.requireNonNull(store);
        this.service = Objects.requireNonNull(service);
    }

    /** 由记录模块在最终提交成功后调用。内部验证运行不进入固化队列。 */
    public void afterCommit(String rootRunId, boolean internalValidation) {
        if (internalValidation) {
            return;
        }
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    process(rootRunId);
                }
            });
        } catch (RejectedExecutionException failure) {
            store.retryRun(rootRunId, failure);
        }
    }

    /** 由后台执行器调用，只有整批接受并暂存后才允许确认归档。 */
    public boolean process(String rootRunId) {
        boolean complete = true;
        try {
            for (Item item : store.pendingItems(rootRunId)) {
                if (!processItem(item)) {
                    complete = false;
                }
            }
        } catch (RuntimeException failure) {
            store.retryRun(rootRunId, failure);
            return false;
        }
        return complete;
    }

    private boolean processItem(Item item) {
        try {
            switch (item.match()) {
                case NEW_CONTRACT -> service.generate(item.evidence());
                case SAME_ARGUMENTS -> store.ensureVectorPointer(item);
                case DIFFERENT_ARGUMENTS -> store.stageParameterReview(item);
            }
            store.markProcessed(item);
            return true;
        } catch (Exception failure) {
            store.retryItem(item, failure);
            return false;
        }
    }

    public enum Match {
        NEW_CONTRACT, SAME_ARGUMENTS, DIFFERENT_ARGUMENTS
    }

    public record Item(
            /** 精确来源与已验收计划。 */
            SkillConsolidationService.Evidence evidence,
            /** 按执行合同及实际参数得到的匹配结果。 */
            Match match) {
        public Item {
            Objects.requireNonNull(evidence);
            Objects.requireNonNull(match);
        }
    }

    public interface Store {
        /** 读取未处理项。根成功取整链，根失败取可独立验收的成功节点及子链。 */
        List<Item> pendingItems(String rootRunId);

        /** 幂等补齐向量入口，写入前核验所指的已发布修订。 */
        void ensureVectorPointer(Item item);

        /**
         * 幂等保存本次实际成功参数，并由大模型判定适用范围。
         * 模型无法调用时直接保留精确参数及来源，不阻塞保存，不伪造扩大后的范围。
         * 保存失败仍须报告给重试流程，候选审核和发布继续遵守各自合同。
         */
        void stageParameterReview(Item item);

        /** 记录提取项已转入下一阶段，候选审核和发布各自记录状态。 */
        void markProcessed(Item item);

        void retryItem(Item item, Exception failure);

        void retryRun(String rootRunId, RuntimeException failure);
    }
}
