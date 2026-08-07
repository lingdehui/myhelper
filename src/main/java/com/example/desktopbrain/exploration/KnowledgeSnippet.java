package com.example.desktopbrain.exploration;

/**
 * 探索学习的知识片段。存入 Qdrant knowledge-snippets 集合 + Neo4j KnowledgeSnippet 节点。
 */
public record KnowledgeSnippet(
        String id,
        String title,
        String content,
        String sourceType,       // autonomous_exploration | user_task | web_research
        String sourceEpisodeId,  // 来源 Episode ID
        int usageCount,
        long createdAt,
        long lastUsedAt
) {
    public static KnowledgeSnippet create(String id, String title, String content,
                                           String sourceType, String sourceEpisodeId) {
        return new KnowledgeSnippet(id, title, content, sourceType, sourceEpisodeId,
                0, System.currentTimeMillis(), 0);
    }
}
