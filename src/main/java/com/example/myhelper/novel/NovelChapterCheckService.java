package com.example.myhelper.novel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 章节自动检查协调器：挂在 add_chapter 保存之后，按「批次」攒批合并检查，减少 LLM 调用次数。
 *
 * <p>批次判定：同一小说两次保存间隔不超过 {@code batch-gap-minutes} 视为同一批。</p>
 * <ul>
 *   <li>批内保存到 {@code batch-threshold} 的整倍数章（第 6、12、18…章）时：合并检查最近一组</li>
 *   <li>批次结束（间隔超时，在下次保存时发现）：批内不足一组的残余章节合并检查一次</li>
 * </ul>
 * 即「单次生成少 → 全部章节一次性检查；单次生成多 → 每组一次合并检查」。
 */
@Service
public class NovelChapterCheckService {

    private static final Logger log = LoggerFactory.getLogger(NovelChapterCheckService.class);

    @Value("${novel.verify.enabled:true}")
    private boolean enabled;

    @Value("${novel.verify.batch-threshold:6}")
    private int batchThreshold;

    @Value("${novel.verify.batch-gap-minutes:15}")
    private long batchGapMinutes;

    private final ConcurrentMap<String, BatchState> batches = new ConcurrentHashMap<>();
    private final NovelQualityChecker checker;

    public NovelChapterCheckService(NovelQualityChecker checker) {
        this.checker = checker;
    }

    /** add_chapter 保存后调用。返回检查报告（无需检查时返回空串，不打扰保存流程）。 */
    public String onChapterSaved(String novelName, int chapterNumber, String content) {
        if (!enabled || batchThreshold <= 0) return "";
        BatchState s = batches.computeIfAbsent(novelName, k -> new BatchState());
        long now = System.currentTimeMillis();
        StringBuilder result = new StringBuilder();

        if (s.lastWrite > 0 && now - s.lastWrite > batchGapMinutes * 60_000L) {
            // 上一批已结束：批内残余（未组成整组的尾数）合并检查一次
            int groups = (s.count / batchThreshold) * batchThreshold;
            int tail = s.count - groups;
            if (tail > 0) {
                int start = s.batchStart + groups;
                result.append(checkBatch(novelName, range(start, start + tail)));
            }
            s.count = 0;
        }

        if (s.count == 0) s.batchStart = chapterNumber;
        s.lastWrite = now;
        s.count++;

        // 到整组边界：合并检查最近一组
        if (s.count % batchThreshold == 0) {
            result.append(checkBatch(novelName, range(chapterNumber - batchThreshold + 1, chapterNumber + 1)));
        }
        return result.toString();
    }

    private List<Integer> range(int startInclusive, int endExclusive) {
        List<Integer> list = new ArrayList<>();
        for (int i = startInclusive; i < endExclusive; i++) list.add(i);
        return list;
    }

    private String checkBatch(String novelName, List<Integer> chapters) {
        try {
            return "\n" + checker.verifyBatch(novelName, chapters);
        } catch (Exception e) {
            log.warn("批量检查失败 novel={} chapters={}: {}", novelName, chapters, e.getMessage());
            return "\n⚠️ 自动检查失败（" + e.getMessage() + "），本次跳过检查";
        }
    }

    private static class BatchState {
        long lastWrite;
        int count;
        int batchStart;
    }
}
