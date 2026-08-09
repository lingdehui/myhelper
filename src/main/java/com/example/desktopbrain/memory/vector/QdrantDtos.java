package com.example.desktopbrain.memory.vector;

import java.util.List;
import java.util.Map;

/**
 * Qdrant REST API 响应 DTO。
 * <p>替代裸 {@code Map<String, Object>} 反序列化，消除 {@code @SuppressWarnings("unchecked")}。</p>
 */
public record QdrantDtos() {

    /** Qdrant search / get-points 响应：{"result": [...], "status": "ok", "time": 0.001} */
    public record SearchResponse(List<ScoredPoint> result) {}

    /** Qdrant scroll 响应：{"result": {"points": [...], "next_page_offset": null}, ...} */
    public record ScrollResponse(ScrollResultData result) {}

    public record ScrollResultData(List<ScoredPoint> points, String next_page_offset) {}

    /** Qdrant delete 响应：{"result": {"status": "completed"}, ...} */
    public record DeleteResponse(OperationStatus result) {}

    public record OperationStatus(String status) {}

    /** 单个向量点（兼容 search/scroll/get-points 三种返回） */
    public record ScoredPoint(
            String id,
            Double score,    // 包装类型：scroll 不返回 score（为 null），search 才返回数值
            Map<String, Object> payload,
            List<Float> vector
    ) {}
}
