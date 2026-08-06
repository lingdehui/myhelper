package com.example.desktopbrain.memory;

import com.example.desktopbrain.memory.graph.KnowledgeGraphService;
import com.example.desktopbrain.memory.vector.EmbeddingService;
import com.example.desktopbrain.memory.vector.VectorMemoryService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 统一记忆层入口
 * 整合 Neo4j 知识图谱 + Qdrant 向量数据库
 * 对应架构中的"记忆与人格层"
 */
@Service
public class MemoryService {

    private final KnowledgeGraphService knowledgeGraph;
    private final VectorMemoryService vectorMemory;
    private final EmbeddingService embeddingService;

    public MemoryService(KnowledgeGraphService knowledgeGraph,
                          VectorMemoryService vectorMemory,
                          EmbeddingService embeddingService) {
        this.knowledgeGraph = knowledgeGraph;
        this.vectorMemory = vectorMemory;
        this.embeddingService = embeddingService;
    }

    // ========== 知识图谱操作 ==========

    /** 注册设备 */
    public void registerDevice(String name, String type, String room) {
        knowledgeGraph.upsertDevice(name, type, room);
        System.out.println("📊 知识图谱: 已注册设备 " + name + " (" + type + ")");
    }

    /** 学习用户偏好 */
    public void learnPreference(String category, String key, String value) {
        knowledgeGraph.learnPreference(category, key, value);
        System.out.println("📊 知识图谱: 已学习偏好 " + category + " -> " + key + "=" + value);
    }

    /** 获取房间设备列表 */
    public List<String> getRoomDevices(String room) {
        return knowledgeGraph.getRoomDevices(room).stream()
                .map(d -> d.getName() + " (" + d.getType() + ")")
                .toList();
    }

    // ========== 向量记忆操作 ==========

    /**
     * 保存对话记忆到向量库
     * @param content 对话内容
     * @param type 类型：user_input / ai_response / tool_call
     */
    public void remember(String content, String type) {
        List<Float> vector = generateEmbedding(content);
        Map<String, String> metadata = Map.of(
                "type", type,
                "timestamp", String.valueOf(System.currentTimeMillis())
        );
        vectorMemory.store(content, vector, metadata);
        System.out.println("🧠 向量记忆: 已存储 (" + type + ") " + summarize(content));
    }

    /**
     * 语义搜索相关记忆
     * @param query 查询文本
     * @param limit 返回数量
     */
    public List<VectorMemoryService.SearchResult> recall(String query, int limit) {
        List<Float> vector = generateEmbedding(query);
        return vectorMemory.search(vector, limit);
    }

    /**
     * 获取上下文摘要：根据当前对话搜索相关历史记忆
     */
    public String getContextSummary(String currentQuery) {
        List<VectorMemoryService.SearchResult> results = recall(currentQuery, 3);
        if (results.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("相关历史记忆:\n");
        for (VectorMemoryService.SearchResult r : results) {
            sb.append("- ").append(r.text()).append("\n");
        }
        return sb.toString();
    }

    // ========== 内部方法 ==========

    /** 生成文本嵌入向量（Ollama nomic-embed-text，768维） */
    private List<Float> generateEmbedding(String text) {
        return embeddingService.embed(text);
    }

    private String summarize(String text) {
        if (text == null) return "";
        return text.length() > 50 ? text.substring(0, 50) + "..." : text;
    }
}