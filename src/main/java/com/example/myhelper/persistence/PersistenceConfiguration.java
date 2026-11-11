package com.example.myhelper.persistence;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.neo4j.autoconfigure.Neo4jAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/**
 * 将 application.yml 中现有的数据库配置装配到读写模块。
 * Spring 扫描或上层 @Import 本类后即可注入 DualWriteStore，不需要模块启动类。
 * 这里只创建客户端和适配器；集合、约束由调用方按实际使用的库显式初始化。
 */
// 所有 Bean 通过方法参数注入，无需代理配置类的方法调用。
@Configuration(proxyBeanMethods = false)
// 复用 Boot 读取 spring.neo4j.uri 和 authentication 等配置并管理 Driver 生命周期。
@Import(Neo4jAutoConfiguration.class)
public class PersistenceConfiguration {
    /** 为两个存储适配器提供独立的完整实体 JSON 编解码器。 */
    @Bean
    public EntityJson persistenceEntityJson() {
        // 不修改应用其他模块使用的 ObjectMapper。
        return new EntityJson();
    }

    /** 使用 Boot 已配置的 Driver 和可选的目标数据库名。 */
    @Bean
    public Neo4jEntityStore neo4jEntityStore(Driver driver, Environment environment, EntityJson json) {
        // 未设置时使用 Neo4j 账号默认数据库，不在代码内写死名称。
        String database = environment.getProperty("spring.data.neo4j.database");
        // 地址、账号、密码都已由 Boot 根据 spring.neo4j 配置绑定到 Driver。
        return new Neo4jEntityStore(driver, database, json);
    }

    /** 创建本模块专用 HTTP 客户端，避免改变其他模块的连接设置。 */
    @Bean
    public HttpClient persistenceHttpClient(Environment environment) {
        // 同时限制连接等待时间，复用 Qdrant 请求超时配置。
        Duration timeout = requestTimeout(environment);
        // Java 25 下容器关闭时会按 Bean 的默认销毁规则关闭该客户端。
        return HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /** 读取现有 qdrant 配置，绑定连接、认证、集合及向量维数。 */
    @Bean
    public QdrantEntityStore qdrantEntityStore(@Qualifier("persistenceHttpClient") HttpClient client, Environment environment, EntityJson json) throws URISyntaxException {
        // 协议默认 HTTP，远程服务可在配置中改为 HTTPS。
        String scheme = environment.getProperty("qdrant.scheme", "http");
        // 防止配置了 HTTP 客户端不支持的协议。
        if (!Set.of("http", "https").contains(scheme)) {
            // 配置错误在任何网络访问前报告。
            throw new IllegalArgumentException("qdrant.scheme 必须是 http 或 https");
        }
        // 复用已有主机地址，不内置 localhost。
        String host = environment.getRequiredProperty("qdrant.host");
        // 复用已有 REST 端口。
        int port = environment.getRequiredProperty("qdrant.port", Integer.class);
        // 拒绝空主机和不可用的端口范围。
        if (host.isBlank() || port < 1 || port > 65535) {
            // 不把无效端口交给远端请求阶段才处理。
            throw new IllegalArgumentException("Qdrant 主机或端口配置无效");
        }
        // 分组件构造 URI，正确处理主机与端口的边界。
        URI base = new URI(scheme, null, host, port, null, null, null);
        // 使用配置里当前的集合，不另建一套默认集合名。
        String collection = environment.getRequiredProperty("qdrant.collection");
        // 本地无认证时允许空值，部署环境可通过配置提供密钥。
        String apiKey = environment.getProperty("qdrant.api-key");
        // 复用现有向量维数，供 adapter.initialize() 创建或检查集合。
        int dimensions = environment.getRequiredProperty("qdrant.vector-size", Integer.class);
        // 距离度量默认延续当前适配器约定的 Cosine。
        String distance = environment.getProperty("qdrant.distance", "Cosine");
        // 解析请求超时，支持 30s 等 Boot 配置格式。
        Duration timeout = requestTimeout(environment);
        // 只绑定参数，不在容器装配时访问 Qdrant。
        return new QdrantEntityStore(client, base, collection, apiKey, timeout, json, dimensions, distance);
    }

    /** 暴露单例协调器，让调用方直接注入并明确指定来源和目标。 */
    @Bean
    public DualWriteStore dualWriteStore(Neo4jEntityStore graph, QdrantEntityStore vector) {
        // 复用容器中的两个适配器，不增加业务路由或引用展开。
        return new DualWriteStore(graph, vector);
    }

    /** 统一解析 Qdrant 连接与请求使用的正数超时。 */
    private Duration requestTimeout(Environment environment) {
        // 未提供可选配置时使用原实现的 30 秒默认值。
        String configured = environment.getProperty("qdrant.request-timeout", "30s");
        // 交给 Boot 的 Duration 解析器处理单位及 ISO-8601 格式。
        Duration timeout = DurationStyle.detectAndParse(configured);
        // HTTP 客户端要求超时为正数。
        if (timeout.isNegative() || timeout.isZero()) {
            // 在构造客户端之前给出明确配置错误。
            throw new IllegalArgumentException("qdrant.request-timeout 必须大于零");
        }
        // 两个 Bean 使用同一解析约定。
        return timeout;
    }
}
