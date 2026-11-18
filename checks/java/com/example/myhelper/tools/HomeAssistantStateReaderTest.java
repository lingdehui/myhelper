package com.example.myhelper.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class HomeAssistantStateReaderTest {
    /** 用于回环协议测试的替身实体编号。 */
    private static final String ENTITY_ID = "climate.test_room";
    /** 本地服务器专用的虚构凭据。 */
    private static final String TEST_TOKEN = "example-test-token";
    /** 正常状态读取的测试预算。 */
    private static final Duration NORMAL_TIMEOUT = Duration.ofSeconds(10);
    /** 明确晚于调用开始的任务截止时间。 */
    private static final Duration DEADLINE_WINDOW = Duration.ofSeconds(25);
    /** 同值重新上报时间晚于状态更新时间。 */
    private static final String REPORTED_AT = "2026-11-17T03:20:15.543210+00:00";
    /** 旧服务状态记录提供的实际更新时间。 */
    private static final String UPDATED_AT = "2026-11-17T02:18:25.123456+00:00";

    @Test
    void readsOnlyTheNativeEntityAndPrefersReportTime() throws Exception {
        try (StateServer server = new StateServer(200, state(ENTITY_ID, "cool", true));
                HomeAssistantStateReader reader = server.reader(NORMAL_TIMEOUT)) {
            HomeAssistantStateReader.Reading reading = reader.read(ENTITY_ID, deadline());

            assertEquals(new ObservedRequest("GET", "/bridge/api/states/" + ENTITY_ID, "Bearer " + TEST_TOKEN),
                    server.request.get());
            assertEquals(1, server.requests.get());
            assertEquals(new HomeAssistantStateReader.Reading(ENTITY_ID, "cool", Instant.parse(REPORTED_AT)), reading);
        }
    }

    @Test
    void usesServiceUpdateTimeWhenReportTimeIsMissing() throws Exception {
        try (StateServer server = new StateServer(200, state(ENTITY_ID, "off", false));
                HomeAssistantStateReader reader = server.reader(NORMAL_TIMEOUT)) {
            assertEquals(new HomeAssistantStateReader.Reading(ENTITY_ID, "off", Instant.parse(UPDATED_AT)),
                    reader.read(ENTITY_ID, deadline()));
        }
    }

    @Test
    void rejectsAnUnexpectedResponseEntity() throws Exception {
        try (StateServer server = new StateServer(200, state("climate.other_room", "cool", true));
                HomeAssistantStateReader reader = server.reader(NORMAL_TIMEOUT)) {
            assertThrows(IOException.class, new ReadAttempt(reader, ENTITY_ID));
            assertEquals(1, server.requests.get());
        }
    }

    @Test
    void rejectsNonSuccessAndDoesNotFollowRedirects() throws Exception {
        try (StateServer server = new StateServer(302, state(ENTITY_ID, "cool", true));
                HomeAssistantStateReader reader = server.reader(NORMAL_TIMEOUT)) {
            IOException failure = assertThrows(IOException.class, new ReadAttempt(reader, ENTITY_ID));

            assertTrue(failure.getMessage().contains("302"));
            assertEquals(1, server.requests.get());
        }
    }

    @Test
    void appliesReadTimeoutWhileTheTaskStillHasTime() throws Exception {
        try (StateServer server = new StateServer(200, state(ENTITY_ID, "cool", true));
                HomeAssistantStateReader reader = server.reader(Duration.ofSeconds(2))) {
            // 预热连接，第二次调用由受控替身停在响应体发送前。
            reader.read(ENTITY_ID, deadline());
            server.holdNextBody = true;
            Instant taskDeadline = deadline();

            assertThrows(HttpTimeoutException.class, new ReadAttempt(reader, ENTITY_ID, taskDeadline));
            assertTrue(server.bodyHeld.await(5, TimeUnit.SECONDS));
            assertTrue(Instant.now().isBefore(taskDeadline));
        }
    }

    private static Instant deadline() {
        return Instant.now().plus(DEADLINE_WINDOW);
    }

    private static String state(String entityId, String value, boolean includeReportTime) {
        String reportField = "";
        if (includeReportTime) {
            reportField = "\"last_reported\": \"" + REPORTED_AT + "\",";
        }
        return """
                {"entity_id": "%s", "state": "%s", %s "last_updated": "%s"}
                """.formatted(entityId, value, reportField, UPDATED_AT);
    }

    private record ReadAttempt(HomeAssistantStateReader reader, String entityId, Instant deadline) implements Executable {
        private ReadAttempt(HomeAssistantStateReader reader, String entityId) {
            this(reader, entityId, HomeAssistantStateReaderTest.deadline());
        }

        @Override
        public void execute() throws IOException, InterruptedException {
            reader.read(entityId, deadline);
        }
    }

    /** 记录到达本地服务器的实际 HTTP 请求。 */
    private record ObservedRequest(String method, String path, String authorization) {
    }

    private static final class StateServer implements HttpHandler, AutoCloseable {
        /** 仅绑定本地回环接口的测试服务。 */
        private final HttpServer server;
        /** 当前测试的 HTTP 状态码。 */
        private final int status;
        /** 当前测试的状态协议正文。 */
        private final byte[] response;
        /** 确认请求是否被重复派发或跟随重定向。 */
        private final AtomicInteger requests = new AtomicInteger();
        /** 保存实际请求的协议字段。 */
        private final AtomicReference<ObservedRequest> request = new AtomicReference<>();
        /** 阻止替身发送下一次响应正文。 */
        private volatile boolean holdNextBody;
        /** 服务完成响应头发送后报告已停在正文阶段。 */
        private final CountDownLatch bodyHeld = new CountDownLatch(1);
        /** 测试结束时释放已暂停的响应。 */
        private final CountDownLatch releaseBody = new CountDownLatch(1);

        private StateServer(int status, String response) throws IOException {
            this.status = status;
            this.response = response.getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this);
            server.start();
        }

        private HomeAssistantStateReader reader(Duration timeout) {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/bridge/");
            return new HomeAssistantStateReader(endpoint, TEST_TOKEN, timeout);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                requests.incrementAndGet();
                request.set(new ObservedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                        exchange.getRequestHeaders().getFirst("Authorization")));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                if (status == 302 && !exchange.getRequestURI().getPath().equals("/redirected")) {
                    exchange.getResponseHeaders().set("Location", "/redirected");
                }
                exchange.sendResponseHeaders(status, response.length);
                if (holdNextBody) {
                    bodyHeld.countDown();
                    awaitRelease();
                }
                exchange.getResponseBody().write(response);
            }
        }

        private void awaitRelease() throws IOException {
            try {
                if (!releaseBody.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("替身没有收到释放信号");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("替身等待被中断");
            }
        }

        @Override
        public void close() {
            releaseBody.countDown();
            server.stop(0);
        }
    }
}
