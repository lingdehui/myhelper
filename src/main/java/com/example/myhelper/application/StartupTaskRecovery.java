package com.example.myhelper.application;

import com.example.myhelper.interaction.TaskEntry;
import com.example.myhelper.persistence.Neo4jEntityStore;
import com.example.myhelper.records.StoredTaskRecords;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

/** 取得同一安装目录独占锁后，先核对旧领取，再分批受理持久恢复候选。 */
public final class StartupTaskRecovery implements ApplicationRunner, Ordered, AutoCloseable {
    /** 启动扫描报告受理情况；实际事件仍由 records 持久保存。 */
    private static final Logger LOG = LoggerFactory.getLogger(StartupTaskRecovery.class);
    /** 与记录仓储的有界扫描合同保持一致。 */
    private static final int MAX_BATCH_SIZE = 1000;
    /** 本机安装身份和进程独占凭据。 */
    private final TaskProcessLock owner;
    /** 权威记录仓储。 */
    private final StoredTaskRecords records;
    /** 只用现成恢复入口，不在应用再次领取任务。 */
    private final TaskRecoveryService recovery;
    /** 恢复句柄纳入与新任务相同的收尾管理。 */
    private final TaskEntry entry;
    /** 仅初始化本次实际使用的 Neo4j 唯一约束。 */
    private final Neo4jEntityStore graph;
    /** 每次查询和派发的批量上限。 */
    private final int batchSize;
    /** 同一启动对象不可重复核对已经新启动的领取。 */
    private boolean started;
    /** 从正式装配开始保留到启动扫描实际结束；独立根运行另外登记。 */
    private final TaskActivity.Occupation scanOccupation;
    /** 后台恢复只占专用线程，不占工具或模型工作线程。 */
    private final ExecutorService driver = Executors.newSingleThreadExecutor(new RecoveryThread());
    /** 停机后不再受理下一批恢复任务。 */
    private volatile boolean stopped;

    public StartupTaskRecovery(TaskProcessLock owner, StoredTaskRecords records,
                               TaskRecoveryService recovery, TaskEntry entry,
                               Neo4jEntityStore graph, int batchSize) {
        this(owner, records, recovery, entry, graph, batchSize, new TaskActivity());
    }

    public StartupTaskRecovery(TaskProcessLock owner, StoredTaskRecords records,
                               TaskRecoveryService recovery, TaskEntry entry,
                               Neo4jEntityStore graph, int batchSize, TaskActivity activity) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("恢复批次须为 1 至 1000");
        }
        this.owner = owner;
        this.records = records;
        this.recovery = recovery;
        this.entry = entry;
        this.graph = graph;
        this.batchSize = batchSize;
        this.scanOccupation = activity.occupy();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        beginScan();
        try {
            graph.initialize();
            reconcileOwnedRuns();
            driver.execute(new RestorePendingTasks());
        } catch (RuntimeException | Error failure) {
            scanOccupation.close();
            throw failure;
        }
    }

    private synchronized void beginScan() {
        if (started) {
            throw new IllegalStateException("启动恢复已运行");
        }
        if (stopped) {
            throw new IllegalStateException("启动恢复已关闭");
        }
        started = true;
    }

    private void reconcileOwnedRuns() {
        // 只有同一安装目录的旧本地运行可以核对；其他宿主的领取保持不变。
        while (true) {
            var active = records.activeRuns(owner.ownerId(), batchSize);
            if (active.isEmpty()) {
                return;
            }
            for (var old : active) {
                records.reconcileStoppedRun(old.claim().taskId(), old.claim().rootRunId(),
                        Map.of("localDispatcherStopped", true, "ownerId", owner.ownerId(),
                                "proof", "EXCLUSIVE_PROCESS_FILE_LOCK", "observedAt", Instant.now().toString()));
            }
        }
    }

    private void resumePages() throws InterruptedException {
        // 保留游标向后扫描，模型失败的早期任务不会反复占满第一批。
        String cursor = null;
        do {
            if (stopped) {
                return;
            }
            List<CompletableFuture<?>> settling = new ArrayList<>();
            var page = records.recoverablePage(batchSize, cursor);
            for (var attempt : recovery.resumeCandidates(page.candidates(), batchSize)) {
                if (attempt.handle() != null) {
                    try {
                        var managed = entry.adopt(attempt.handle());
                        settling.add(managed.settled());
                        LOG.info("已受理恢复任务 {}，运行 {}", attempt.taskId(), attempt.handle().rootRunId());
                    } catch (RuntimeException closing) {
                        // adopt 在关闭后会停止该句柄；继续逐个接纳，让本批其余已领取运行也得到停止。
                        LOG.warn("恢复任务 {} 未能进入已关闭的入口，已请求本地停止", attempt.taskId(), closing);
                    }
                } else {
                    LOG.warn("恢复任务 {} 未受理：{}", attempt.taskId(), attempt.error());
                }
            }
            // 只等待本批真实收尾，避免一次启动将整个仓库塞进有限工作队列。
            try {
                CompletableFuture.allOf(settling.toArray(CompletableFuture<?>[]::new)).get();
            } catch (ExecutionException failedSettlement) {
                LOG.warn("恢复批次有任务收尾失败，事实仍由记录模块保留");
            }
            cursor = page.nextCursor();
        } while (cursor != null);
    }

    @Override
    public void close() {
        boolean unstarted = stopScan();
        List<Runnable> pending = driver.shutdownNow();
        if (unstarted || !pending.isEmpty()) {
            scanOccupation.close();
        }
    }

    private synchronized boolean stopScan() {
        stopped = true;
        return !started;
    }

    /** 专用恢复线程的入口；中断只终止本次扫描，未受理任务继续留在仓储。 */
    private final class RestorePendingTasks implements Runnable {
        @Override
        public void run() {
            try {
                resumePages();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException failure) {
                LOG.error("恢复扫描中断，持久候选保留供下次接续", failure);
            } finally {
                scanOccupation.close();
            }
        }
    }

    private static final class RecoveryThread implements ThreadFactory {
        @Override
        public Thread newThread(Runnable action) {
            Thread thread = new Thread(action, "myhelper-startup-recovery");
            thread.setDaemon(true);
            return thread;
        }
    }
}
