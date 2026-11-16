package com.example.myhelper.llm;

import com.example.myhelper.world.model.Embedding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaTextEmbeddingTest {
    /** 模拟服务端报告的模型摘要。 */
    private static final String DIGEST = "a".repeat(64);
    /** 模拟模型标签指向更新后的摘要。 */
    private static final String UPDATED_DIGEST = "b".repeat(64);
    /** 默认响应只含一个二元向量。 */
    private static final String VALID_EMBEDDING =
            "{\"model\":\"text-check:latest\",\"embeddings\":[[0.25,-0.5]]}";

    @Test
    void springSharesConfiguredEmbeddingWithoutContactingOllamaAtStartup() throws IOException {
        try (OllamaServer server = new OllamaServer();
             AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("local-check", Map.of(
                    "embedding.base-url", server.uri().toString(),
                    "embedding.model", "text-check",
                    "embedding.version", DIGEST,
                    "qdrant.vector-size", 2)));
            context.register(EmbeddingConfiguration.class);
            context.refresh();
            OllamaTextEmbedding client = context.getBean("textEmbedding", OllamaTextEmbedding.class);
            assertEquals(0, server.tagRequests.get());
            assertEquals(0, server.embedRequests.get());
            assertEquals(2, client.dimensions());
            assertEquals(new Embedding("text-check", "sha256:" + DIGEST, List.of(0.25, -0.5)),
                    client.apply("从配置装配"));
        }
    }

    @Test
    void readsDigestLazilyAndChecksItOnBothSidesOfEmbeddingRequest() throws IOException {
        try (OllamaServer server = new OllamaServer();
             OllamaTextEmbedding client = new OllamaTextEmbedding(server.uri(), "text-check", null, 2)) {
            assertEquals(0, server.tagRequests.get());
            assertEquals(0, server.embedRequests.get());
            assertEquals("sha256:" + DIGEST, client.version());
            assertEquals(new Embedding("text-check", "sha256:" + DIGEST, List.of(0.25, -0.5)),
                    client.apply("向量 \"文本\""));
            assertEquals(3, server.tagRequests.get());
            assertEquals(1, server.embedRequests.get());
            JsonNode request = server.request.get();
            assertEquals("text-check:latest", request.path("model").asText());
            assertEquals("向量 \"文本\"", request.path("input").asText());
            assertFalse(request.path("truncate").asBoolean(true));
        }
    }

    @Test
    void rejectsDigestPinMismatchBeforeSendingInput() throws IOException {
        try (OllamaServer server = new OllamaServer();
             OllamaTextEmbedding client = new OllamaTextEmbedding(server.uri(), "text-check", UPDATED_DIGEST, 2)) {
            assertThrows(IllegalStateException.class, new EmbedText(client));
            assertEquals(1, server.tagRequests.get());
            assertEquals(0, server.embedRequests.get());
        }
    }

    @Test
    void rejectsModelTagChangeDuringRequest() throws IOException {
        try (OllamaServer server = new OllamaServer(UPDATED_DIGEST, 200, VALID_EMBEDDING);
             OllamaTextEmbedding client = new OllamaTextEmbedding(server.uri(), "text-check", "", 2)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, new EmbedText(client));
            assertTrue(failure.getMessage().contains("摘要发生变化"));
            assertEquals(2, server.tagRequests.get());
            assertEquals(1, server.embedRequests.get());
        }
    }

    @Test
    void rejectsWrongModelDimensionAndNonFiniteVector() throws IOException {
        for (String response : List.of(
                "{\"model\":\"other:latest\",\"embeddings\":[[0,1]]}",
                "{\"model\":\"text-check:latest\",\"embeddings\":[[0]]}",
                "{\"model\":\"text-check:latest\",\"embeddings\":[[1e999,0]]}",
                "{\"model\":\"text-check:latest\",\"embeddings\":[[0,\"1\"]]}")) {
            try (OllamaServer server = new OllamaServer(DIGEST, 200, response);
                 OllamaTextEmbedding client = new OllamaTextEmbedding(server.uri(), "text-check", DIGEST, 2)) {
                assertThrows(IllegalStateException.class, new EmbedText(client));
                assertEquals(1, server.embedRequests.get());
            }
        }
    }

    @Test
    void reportsHttpStatusWithoutEchoingServiceErrorBody() throws IOException {
        String sensitiveBody = "敏感响应文本";
        try (OllamaServer server = new OllamaServer(DIGEST, 503, sensitiveBody);
             OllamaTextEmbedding client = new OllamaTextEmbedding(server.uri(), "text-check", null, 2)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, new EmbedText(client));
            assertTrue(failure.getMessage().contains("/api/embed 返回 HTTP 503"));
            assertFalse(failure.toString().contains(sensitiveBody));
        }
    }

    private record EmbedText(
            /** 该检查使用的嵌入客户端。 */
            OllamaTextEmbedding client) implements Executable {
        @Override
        public void execute() {
            client.apply("待生成向量的文本");
        }
    }

    private static final class OllamaServer implements HttpHandler, AutoCloseable {
        /** 只监听本机随机端口。 */
        private final HttpServer server;
        /** 生成向量后目录返回的模型摘要。 */
        private final String digestAfterEmbed;
        /** 生成向量接口返回的 HTTP 状态。 */
        private final int embedStatus;
        /** 生成向量接口返回的协议正文。 */
        private final String embedBody;
        /** 记录目录读取次数。 */
        private final AtomicInteger tagRequests = new AtomicInteger();
        /** 记录生成向量调用次数。 */
        private final AtomicInteger embedRequests = new AtomicInteger();
        /** 保存服务端实际收到的生成请求。 */
        private final AtomicReference<JsonNode> request = new AtomicReference<>();

        private OllamaServer() throws IOException {
            this(DIGEST, 200, VALID_EMBEDDING);
        }

        private OllamaServer(String digestAfterEmbed, int embedStatus, String embedBody) throws IOException {
            this.digestAfterEmbed = digestAfterEmbed;
            this.embedStatus = embedStatus;
            this.embedBody = embedBody;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/tags", this);
            server.createContext("/api/embed", this);
            server.start();
        }

        private URI uri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                switch (exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()) {
                    case "GET /api/tags" -> {
                        tagRequests.incrementAndGet();
                        String digest = embedRequests.get() == 0 ? DIGEST : digestAfterEmbed;
                        send(exchange, 200, """
                                {"models":[
                                    {"model":"text-check:other","digest":"%s"},
                                    {"model":"text-check:latest","digest":"%s"}
                                ]}
                                """.formatted(UPDATED_DIGEST, digest));
                    }
                    case "POST /api/embed" -> {
                        request.set(new ObjectMapper().readTree(exchange.getRequestBody()));
                        embedRequests.incrementAndGet();
                        send(exchange, embedStatus, embedBody);
                    }
                    default -> send(exchange, 405, "{}");
                }
            }
        }

        private static void send(HttpExchange exchange, int status, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
