package com.example.desktopbrain.memory.vector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Qdrant 向量数据库配置（REST API）
 */
@Configuration
public class QdrantConfig {

    @Value("${qdrant.host:localhost}")
    private String host;

    @Value("${qdrant.port:6333}")
    private int port;

    @Value("${qdrant.collection:desktop-memory}")
    private String collectionName;

    @Value("${qdrant.vector-size:768}")
    private int vectorSize;

    @Bean
    public WebClient qdrantWebClient() {
        return WebClient.builder()
                .baseUrl("http://" + host + ":" + port)
                .build();
    }

    @Bean
    public String qdrantCollectionName() {
        return collectionName;
    }

    @Bean
    public int qdrantVectorSize() {
        return vectorSize;
    }

    /** 初始化集合（如果不存在则创建） */
    @Bean
    public boolean qdrantInitCollection(WebClient qdrantWebClient) {
        try {
            // 检查集合是否存在
            var exists = qdrantWebClient.get()
                    .uri("/collections/" + collectionName)
                    .retrieve()
                    .toBodilessEntity()
                    .map(r -> true)
                    .onErrorReturn(false)
                    .block();

            if (Boolean.FALSE.equals(exists)) {
                var body = String.format("""
                        {"vectors": {"size": %d, "distance": "Cosine"}}
                        """, vectorSize);
                qdrantWebClient.put()
                        .uri("/collections/" + collectionName)
                        .header("Content-Type", "application/json")
                        .bodyValue(body)
                        .retrieve()
                        .toBodilessEntity()
                        .block();
                System.out.println("📦 Qdrant 集合 '" + collectionName + "' 已创建（向量维度: " + vectorSize + "）");
            } else {
                System.out.println("📦 Qdrant 集合 '" + collectionName + "' 已存在");
            }
            return true;
        } catch (Exception e) {
            System.err.println("⚠️ Qdrant 初始化失败: " + e.getMessage());
            return false;
        }
    }
}