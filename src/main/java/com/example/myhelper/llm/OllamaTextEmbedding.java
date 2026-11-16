package com.example.myhelper.llm;

import com.example.myhelper.world.model.Embedding;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** 通过 Ollama 生成文本向量，并使用服务端返回的模型摘要标识向量版本。 */
public final class OllamaTextEmbedding implements Function<String, Embedding>, AutoCloseable {
    /** Ollama 服务连接超时。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** 每次目录或向量请求的超时。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    /** 共享的协议编解码器，配置完成后不再修改。 */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 装配方提供的模型名，保存在向量元数据中。 */
    private final String model;
    /** 补齐默认标签后的模型名，供请求和目录匹配使用。 */
    private final String modelTag;
    /** 装配方可选的 SHA-256 摘要，用于校验当前模型。 */
    private final String expectedVersion;
    /** 可写入同一索引、用于相似检索的向量维数。 */
    private final int dimensions;
    /** 查询本地模型名与摘要的接口。 */
    private final URI tagsEndpoint;
    /** 生成向量的接口。 */
    private final URI embedEndpoint;
    /** 复用的 HTTP 客户端，默认不跟随重定向。 */
    private final HttpClient client;

    /** 仅校验配置；构造时不连接 Ollama。 */
    public OllamaTextEmbedding(URI endpointBase, String model, String version, int dimensions) {
        Objects.requireNonNull(endpointBase, "Ollama 地址不能为空");
        if (!("http".equalsIgnoreCase(endpointBase.getScheme()) || "https".equalsIgnoreCase(endpointBase.getScheme()))
                || endpointBase.getHost() == null || endpointBase.getUserInfo() != null
                || endpointBase.getRawQuery() != null || endpointBase.getRawFragment() != null) {
            throw new IllegalArgumentException("Ollama 地址须为不含凭据、查询或片段的 HTTP(S) 地址");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Ollama 嵌入模型不能为空");
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Ollama 嵌入维数必须大于零");
        }
        this.model = model.strip();
        this.modelTag = withDefaultTag(this.model);
        this.expectedVersion = configuredDigest(version);
        this.dimensions = dimensions;
        String base = endpointBase.toString().replaceAll("/+$", "");
        tagsEndpoint = URI.create(base + "/api/tags");
        embedEndpoint = URI.create(base + "/api/embed");
        client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    public String model() {
        return model;
    }

    public int dimensions() {
        return dimensions;
    }

    /** 查询当前标签的服务端摘要，若配置了摘要，同时校验其一致性。 */
    public String version() {
        JsonNode result = send(HttpRequest.newBuilder(tagsEndpoint).GET(), "/api/tags");
        JsonNode models = result.path("models");
        if (!models.isArray()) {
            throw new IllegalStateException("Ollama /api/tags 未返回模型列表");
        }

        String version = null;
        for (JsonNode entry : models) {
            String candidate = entry.path("model").textValue();
            if (candidate == null || candidate.isBlank()) {
                candidate = entry.path("name").textValue();
            }
            if (!modelTag.equals(withDefaultTag(candidate))) {
                continue;
            }
            String digest = normalizeDigest(entry.path("digest").textValue());
            if (digest == null || (version != null && !version.equals(digest))) {
                throw new IllegalStateException("Ollama /api/tags 未提供唯一有效的模型摘要");
            }
            version = digest;
        }
        if (version == null) {
            throw new IllegalStateException("Ollama /api/tags 未找到所配置的嵌入模型");
        }
        if (expectedVersion != null && !expectedVersion.equals(version)) {
            throw new IllegalStateException("Ollama 当前模型摘要与配置不符");
        }
        return version;
    }

    @Override
    public Embedding apply(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("嵌入文本不能为空");
        }
        String before = version();
        String body;
        try {
            // 过长输入由服务端报告错误，避免把已截断的输入当成全文嵌入。
            body = JSON.writeValueAsString(Map.of("model", modelTag, "input", text, "truncate", false));
        } catch (IOException failure) {
            throw new IllegalStateException("无法编码 Ollama 嵌入请求");
        }
        JsonNode result = send(HttpRequest.newBuilder(embedEndpoint)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), "/api/embed");
        if (!modelTag.equals(withDefaultTag(result.path("model").textValue()))) {
            throw new IllegalStateException("Ollama /api/embed 返回的模型标签与请求不符");
        }
        List<Double> values = vector(result.path("embeddings"));
        // API 响应不附摘要，前后核对只能发现可观察到的标签变更。
        if (!before.equals(version())) {
            throw new IllegalStateException("Ollama 嵌入期间模型摘要发生变化");
        }
        return new Embedding(model, before, values);
    }

    @Override
    public void close() {
        client.close();
    }

    private List<Double> vector(JsonNode embeddings) {
        if (!embeddings.isArray() || embeddings.size() != 1
                || !embeddings.get(0).isArray() || embeddings.get(0).size() != dimensions) {
            throw new IllegalStateException("Ollama /api/embed 未返回一个符合配置维数的向量");
        }
        List<Double> values = new ArrayList<>(dimensions);
        for (JsonNode component : embeddings.get(0)) {
            if (!component.isNumber() || !Double.isFinite(component.doubleValue())) {
                throw new IllegalStateException("Ollama /api/embed 向量分量必须是有限数");
            }
            values.add(component.doubleValue());
        }
        return values;
    }

    private JsonNode send(HttpRequest.Builder request, String operation) {
        HttpResponse<String> response;
        try {
            response = client.send(request.header("Accept", "application/json").timeout(REQUEST_TIMEOUT).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ollama " + operation + " 请求被中断");
        } catch (IOException failure) {
            throw new IllegalStateException("Ollama " + operation + " 请求失败");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // 服务端错误正文可能带回输入，错误只报告接口和状态码。
            throw new IllegalStateException("Ollama " + operation + " 返回 HTTP " + response.statusCode());
        }
        try {
            JsonNode result = JSON.readTree(response.body());
            if (result != null && result.isObject() && !result.has("error")) {
                return result;
            }
        } catch (IOException failure) {
            // 解析异常可能带回服务端响应片段，统一返回不包含响应内容的协议错误。
        }
        throw new IllegalStateException("Ollama " + operation + " 返回无效的 JSON 响应");
    }

    private static String withDefaultTag(String modelName) {
        if (modelName == null || modelName.isBlank()
                || modelName.lastIndexOf(':') > modelName.lastIndexOf('/')) {
            return modelName;
        }
        return modelName + ":latest";
    }

    private static String configuredDigest(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String digest = normalizeDigest(version.strip());
        if (digest == null) {
            throw new IllegalArgumentException("Ollama 模型版本必须是完整的 SHA-256 摘要");
        }
        return digest;
    }

    private static String normalizeDigest(String value) {
        if (value == null) {
            return null;
        }
        String digest = value.toLowerCase(Locale.ROOT);
        if (digest.startsWith("sha256:")) {
            digest = digest.substring("sha256:".length());
        }
        return digest.matches("[0-9a-f]{64}") ? "sha256:" + digest : null;
    }
}
