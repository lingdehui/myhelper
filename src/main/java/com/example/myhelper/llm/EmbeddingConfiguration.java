package com.example.myhelper.llm;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 为技能发布与向量查询装配同一个嵌入客户端。 */
@Configuration(proxyBeanMethods = false)
public class EmbeddingConfiguration {
    @Bean
    public OllamaTextEmbedding textEmbedding(Environment environment) {
        URI base = environment.getProperty("embedding.base-url", URI.class, URI.create("http://localhost:11434"));
        String model = environment.getRequiredProperty("embedding.model");
        String version = environment.getProperty("embedding.version");
        int dimensions = environment.getRequiredProperty("qdrant.vector-size", Integer.class);
        return new OllamaTextEmbedding(base, model, version, dimensions);
    }
}
