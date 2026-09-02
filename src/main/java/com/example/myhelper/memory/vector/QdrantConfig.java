package com.example.myhelper.memory.vector;

import com.example.myhelper.config.SystemEnvironmentService;
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
    private String baseCollectionName;

    @Value("${qdrant.vector-size:768}")
    private int vectorSize;

    @Bean
    public WebClient qdrantWebClient() {
        return WebClient.builder()
                .baseUrl("http://" + host + ":" + port)
                .build();
    }

    @Bean
    public String qdrantCollectionName(SystemEnvironmentService envService) {
        return envService.collectionName(baseCollectionName);
    }

    @Bean
    public int qdrantVectorSize() {
        return vectorSize;
    }
}