package com.example.myhelper.tools;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/** 读取 Home Assistant 服务中指定实体记录的原始状态。 */
public final class HomeAssistantStateReader implements DeviceStateTool.Reader, AutoCloseable {
    /** 实体 ID 由域和对象 ID 组成，可作为单一路径段。 */
    private static final Pattern ENTITY_ID = Pattern.compile("[a-z0-9_]+\\.[a-z0-9_]+");
    /** 状态 JSON 可包含设备属性，但单次响应不能无限增长。 */
    private static final long MAX_RESPONSE_BYTES = 1024L * 1024;
    /** 解析完整响应并拒绝重复字段，防止同一状态记录出现多个解释。 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);

    /** 宿主配置的 HA 状态接口前缀，保留反向代理路径。 */
    private final String statesEndpoint;
    /** 只用于请求标头的宿主访问凭据。 */
    private final String authorization;
    /** 连接和单次读取允许的最长时间。 */
    private final Duration timeout;
    /** 此读取器复用的 HTTP 客户端。 */
    private final HttpClient client;

    public HomeAssistantStateReader(URI baseUri, String accessToken, Duration timeout) {
        Objects.requireNonNull(baseUri, "Home Assistant 地址不能为空");
        if (!("http".equalsIgnoreCase(baseUri.getScheme()) || "https".equalsIgnoreCase(baseUri.getScheme()))
                || baseUri.getHost() == null || baseUri.getRawUserInfo() != null
                || baseUri.getRawQuery() != null || baseUri.getRawFragment() != null) {
            throw new IllegalArgumentException("Home Assistant 地址须为不含凭据、查询或片段的 HTTP(S) 地址");
        }
        if (accessToken == null || accessToken.isEmpty()) {
            throw new IllegalArgumentException("Home Assistant 凭据不能为空");
        }
        for (int index = 0; index < accessToken.length(); index++) {
            char character = accessToken.charAt(index);
            if (character <= ' ' || character > '~') {
                throw new IllegalArgumentException("Home Assistant 凭据格式无效");
            }
        }
        this.timeout = Objects.requireNonNull(timeout, "Home Assistant 超时不能为空");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Home Assistant 超时须大于零");
        }
        this.statesEndpoint = baseUri.toString().replaceAll("/+$", "") + "/api/states/";
        this.authorization = "Bearer " + accessToken;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Reading read(String entityId, Instant deadline) throws IOException, InterruptedException {
        if (!validEntityId(entityId)) {
            throw new IOException("Home Assistant 实体 ID 无效");
        }
        Objects.requireNonNull(deadline, "Home Assistant 调用截止时间不能为空");
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.isZero() || remaining.isNegative()) {
            throw timeoutFailure();
        }
        Duration requestTimeout = remaining;
        if (timeout.compareTo(remaining) < 0) {
            requestTimeout = timeout;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(statesEndpoint + entityId))
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .timeout(requestTimeout)
                .GET()
                .build();
        HttpResponse<byte[]> response = receive(request, requestTimeout);
        if (response.statusCode() != 200) {
            throw new IOException("Home Assistant 状态接口返回 HTTP " + response.statusCode());
        }
        Reading reading = parse(entityId, response.body());
        if (!Instant.now().isBefore(deadline)) {
            throw timeoutFailure();
        }
        return reading;
    }

    /** 在同一预算内等待响应体读完，超时或中断时取消本次请求。 */
    private HttpResponse<byte[]> receive(HttpRequest request, Duration remaining)
            throws IOException, InterruptedException {
        CompletableFuture<HttpResponse<byte[]>> response = client.sendAsync(request, new StateBodyHandler());
        try {
            return response.get(TimeUnit.NANOSECONDS.convert(remaining), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            response.cancel(true);
            throw interrupted;
        } catch (TimeoutException timedOut) {
            response.cancel(true);
            throw timeoutFailure();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof HttpTimeoutException) {
                throw timeoutFailure();
            }
            throw new IOException("Home Assistant 状态请求失败");
        }
    }

    private static Reading parse(String requestedEntityId, byte[] body) throws IOException {
        JsonNode record;
        try {
            record = JSON.readTree(body);
        } catch (IOException invalidJson) {
            // 解析器的异常可能包含响应原文，仅报告协议错误。
            throw new IOException("Home Assistant 状态响应不是有效的 JSON");
        }
        if (record == null || !record.isObject()) {
            throw new IOException("Home Assistant 状态响应缺少记录对象");
        }
        String entityId = record.path("entity_id").textValue();
        String state = record.path("state").textValue();
        if (!requestedEntityId.equals(entityId) || state == null) {
            throw new IOException("Home Assistant 状态响应的实体 ID 或状态不符");
        }
        JsonNode reportedAt = record.path("last_reported");
        if (reportedAt.isMissingNode() || reportedAt.isNull()) {
            // 旧版本未返回 last_reported 时，保留 HA 已有的记录时间。
            reportedAt = record.path("last_updated");
        }
        if (!reportedAt.isTextual()) {
            throw new IOException("Home Assistant 状态响应缺少有效的观测时间");
        }
        try {
            return new Reading(entityId, state, Instant.parse(reportedAt.textValue()));
        } catch (DateTimeParseException invalidTime) {
            throw new IOException("Home Assistant 状态响应的观测时间无效");
        }
    }

    private static boolean validEntityId(String entityId) {
        return entityId != null && ENTITY_ID.matcher(entityId).matches();
    }

    private static HttpTimeoutException timeoutFailure() {
        return new HttpTimeoutException("Home Assistant 状态读取超时");
    }

    @Override
    public void close() {
        client.close();
    }

    public record Reading(
            // HA 响应中的原生实体 ID。
            String entityId,
            // HA 返回的原始字符串状态。
            String state,
            // 服务记录的上报时间，缺失时使用服务记录的更新时间。
            Instant observedAt) {
    }

    private static final class StateBodyHandler implements HttpResponse.BodyHandler<byte[]> {
        @Override
        public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo response) {
            return HttpResponse.BodySubscribers.limiting(HttpResponse.BodySubscribers.ofByteArray(), MAX_RESPONSE_BYTES);
        }
    }
}
