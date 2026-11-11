package com.example.myhelper.persistence;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.neo4j.driver.Driver;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.neo4j.autoconfigure.Neo4jProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.FileSystemResource;
import static com.example.myhelper.persistence.DualWriteStore.Database.QDRANT;

/** 使用真实 application.yml 装配容器，网络仅指向测试服务器，不访问真实数据库。 */
public final class PersistenceConfigurationCheck {
    /** 验证配置绑定、懒连接、配置初始化和显式来源读取。 */
    public static void main(String[] args) throws Exception {
        // 加载项目实际配置，不复制地址或认证信息到测试代码。
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        // 这里只读取工作区文件，不修改 IDE 或全局环境。
        loader.setResources(new FileSystemResource("src/main/resources/application.yml"));
        // YAML 工厂会将层级键转成 Spring 属性键。
        Properties properties = Objects.requireNonNull(loader.getObject());
        // 使用实际集合名验证适配器没有硬编码另一套集合。
        String collection = properties.getProperty("qdrant.collection");
        // 使用实际维数构造测试服务的集合配置。
        int dimensions = Integer.parseInt(properties.getProperty("qdrant.vector-size"));
        // 统计请求，验证容器装配时不主动初始化数据库。
        AtomicInteger requests = new AtomicInteger();
        // 本地随机端口避免占用用户数据库端口。
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 只为配置中的集合提供模拟响应。
        server.createContext("/collections/" + collection, new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                // 记录一次实际 HTTP 请求。
                requests.incrementAndGet();
                // 本地服务只支持初始化查询和指定点读取。
                String result = exchange.getRequestMethod().equals("GET")
                        // 集合信息用于验证无参数 initialize 使用了配置维数。
                        ? "{\"config\":{\"params\":{\"vectors\":{\"size\":" + dimensions + ",\"distance\":\"Cosine\"}}}}"
                        // 读取不存在的点返回空数组。
                        : "[]";
                // 生成符合 Qdrant 协议的成功响应。
                byte[] body = ("{\"status\":\"ok\",\"result\":" + result + "}").getBytes(StandardCharsets.UTF_8);
                // 保证每次请求后关闭交换对象。
                try {
                    // 返回精确的响应长度。
                    exchange.sendResponseHeaders(200, body.length);
                    // 写入协议响应。
                    exchange.getResponseBody().write(body);
                // 即使写响应失败也要释放连接。
                } finally {
                    // 结束本次交换。
                    exchange.close();
                }
            }
        });
        // 启动本地测试服务，不启动真实数据库。
        server.start();
        // 容器关闭时释放模块客户端。
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            // 添加项目文件配置，低于显式测试覆盖项。
            context.getEnvironment().getPropertySources().addLast(new PropertiesPropertySource("applicationYaml", properties));
            // 仅覆盖网络地址与密钥，集合和维数继续来自真实文件。
            Map<String, Object> overrides = Map.of("qdrant.host", "127.0.0.1", "qdrant.port", server.getAddress().getPort(), "qdrant.scheme", "http", "qdrant.api-key", "test-only");
            // 验证 Spring 的高优先级属性可以覆盖文件中的连接设置。
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testServer", overrides));
            // 提供不联网的 Driver，任何意外数据库访问都使检查失败。
            Driver driver = (Driver) Proxy.newProxyInstance(Driver.class.getClassLoader(), new Class<?>[]{Driver.class}, new InvocationHandler() {
                @Override
                public Object invoke(Object object, Method method, Object[] arguments) throws Throwable {
                    // 容器销毁时允许关闭测试对象。
                    if (method.getName().equals("close")) {
                        // 不连接或关闭任何真实数据库。
                        return null;
                    }
                    // 其他方法意味着错误地访问了 Neo4j。
                    throw new AssertionError("配置装配及 Qdrant 单库读取不应访问 Neo4j: " + method.getName());
                }
            });
            // 已有 Driver 时，Boot 应复用它而不另建驱动。
            context.registerBean(Driver.class, new Supplier<Driver>() {
                @Override
                public Driver get() {
                    return driver;
                }
            });
            // 仅注册本模块配置，不启动 Web、LLM 或其他模块。
            context.register(PersistenceConfiguration.class);
            // 执行真实的 Spring Bean 装配。
            context.refresh();
            // 未显式初始化前，不应向 Qdrant 发请求。
            require(requests.get() == 0, "装配不应连接数据库");
            // Neo4j 参数仍由实际 Boot 配置绑定器处理。
            Neo4jProperties neo4j = context.getBean(Neo4jProperties.class);
            // 检查地址来自当前配置，而非硬编码默认值。
            require(neo4j.getUri().toString().equals(context.getEnvironment().getProperty("spring.neo4j.uri")), "Neo4j URI 未绑定");
            // 比较认证字段但不打印实际值。
            require(Objects.equals(neo4j.getAuthentication().getUsername(), context.getEnvironment().getProperty("spring.neo4j.authentication.username")), "Neo4j 用户名未绑定");
            // 密码仅做等值校验，错误消息也不输出其内容。
            require(Objects.equals(neo4j.getAuthentication().getPassword(), context.getEnvironment().getProperty("spring.neo4j.authentication.password")), "Neo4j 密码未绑定");
            // 无参数初始化应使用 qdrant.vector-size 和 distance。
            context.getBean(QdrantEntityStore.class).initialize();
            // 从容器中直接获得可用的统一协调器。
            DualWriteStore store = context.getBean(DualWriteStore.class);
            // 指定 Qdrant 读取，不触发 Neo4j 会话。
            require(store.read(QDRANT, "configuration-check").isEmpty(), "空点读取结果错误");
            // 初始化和读取应各发一个请求。
            require(requests.get() == 2, "配置装配产生了额外数据库请求");
            // 报告验证范围，不输出认证数据。
            System.out.println("Persistence configuration checks passed: application.yml, Neo4j credentials binding, Qdrant endpoint/collection/dimensions, no eager connections");
        // 容器或验证失败时也必须停止测试服务。
        } finally {
            // 立即释放随机端口。
            server.stop(0);
        }
    }

    /** 使用不含配置值的错误消息执行断言。 */
    private static void require(boolean value, String message) {
        // 条件不成立就让测试进程失败。
        if (!value) {
            // 消息不包含密码或 API key。
            throw new AssertionError(message);
        }
    }
}
