package com.example.myhelper.persistence;

import com.example.myhelper.world.model.WorldEntity;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Qdrant REST 适配器；无名向量用于检索，payload 保存完整实体与引用。 */
public final class QdrantEntityStore extends SnapshotParticipant {
    /** Spring 或调用方管理的共享 HTTP 客户端。 */
    private final HttpClient client;
    /** 根据配置中的地址和集合名形成的 REST 路径。 */
    private final String collectionUrl;
    /** 可选认证密钥，不写入日志或异常消息。 */
    private final String apiKey;
    /** 每次请求的最大等待时间。 */
    private final Duration timeout;
    /** 完整实体编码器。 */
    private final EntityJson json;
    /** 来自 qdrant.vector-size 的维数；手动构造时可不绑定。 */
    private final Integer configuredDimensions;
    /** 来自 qdrant.distance 的距离度量。 */
    private final String configuredDistance;

    /** 保留手动注入入口；集合初始化需显式传入维数与距离。 */
    public QdrantEntityStore(HttpClient client, URI base, String collection, String apiKey, Duration timeout, EntityJson json) {
        // 未绑定初始化参数时，不猜测向量维数。
        this(client, base, collection, apiKey, timeout, json, null, null);
    }

    /** 接收配置装配后的连接与集合参数；构造时不发出网络请求。 */
    public QdrantEntityStore(HttpClient client, URI base, String collection, String apiKey, Duration timeout, EntityJson json, Integer dimensions, String distance) {
        // 客户端由容器或调用方复用。
        this.client = Objects.requireNonNull(client);
        // 地址不可缺失，避免默默连到默认服务。
        Objects.requireNonNull(base, "Qdrant 地址不能为空");
        // 必须明确当前适配器使用哪个集合。
        if (collection == null || collection.isBlank()) {
            // 配置错误在发送请求前报告。
            throw new IllegalArgumentException("collection 不能为空");
        }
        // 将集合名编码为单一路径段，避免空格或斜杠改变请求地址。
        String encodedCollection = URLEncoder.encode(collection, StandardCharsets.UTF_8).replace("+", "%20");
        // 去掉地址尾部的斜杠后追加集合路径。
        this.collectionUrl = base.toString().replaceAll("/+$", "") + "/collections/" + encodedCollection;
        // 保存可选认证信息，不输出其值。
        this.apiKey = apiKey;
        // 请求超时必须存在。
        this.timeout = Objects.requireNonNull(timeout);
        // HTTP 请求只接受正数超时。
        if (timeout.isNegative() || timeout.isZero()) {
            // 在装配阶段指出错误配置。
            throw new IllegalArgumentException("Qdrant 请求超时必须大于零");
        }
        // 两库共用同一套实体编码规则。
        this.json = Objects.requireNonNull(json);
        // 绑定了集合参数时立即检查，仍不连接数据库。
        if (dimensions != null) {
            // 防止直到初始化远端时才发现无效维数或距离。
            validateVectorConfig(dimensions, distance);
        }
        // 记录配置文件中的维数，供无参数初始化使用。
        this.configuredDimensions = dimensions;
        // 距离与维数作为同一份集合配置保存。
        this.configuredDistance = distance;
    }

    /** 使用配置文件绑定的维数和距离初始化集合，调用时才访问数据库。 */
    public void initialize() {
        // 手动构造但未提供集合参数时，不静默使用默认维数。
        if (configuredDimensions == null) {
            // 调用方可改用带参数的 initialize 方法。
            throw new IllegalStateException("未绑定集合配置，请使用 initialize(dimensions, distance)");
        }
        // 复用已有集合创建及一致性检查流程。
        initialize(configuredDimensions, configuredDistance);
    }

    /** 显式创建集合或检查现有配置；已有集合不会被删除或重建。 */
    public void initialize(int dimensions, String distance) {
        // 在远端操作前验证集合参数。
        validateVectorConfig(dimensions, distance);
        // 查询集合是否已存在。
        HttpResponse<String> response = request("GET", "", null);
        // 只有明确的 404 才创建集合，认证等错误不能被误当成不存在。
        if (response.statusCode() == 404) {
            // 创建无名向量集合，维数来自配置或显式参数。
            send("PUT", "", Map.of("vectors", Map.of("size", dimensions, "distance", distance)));
            // 创建成功后结束初始化。
            return;
        }
        // 先验证 HTTP 和服务状态，再提取现有向量配置。
        JsonNode config = checked(response).path("result").path("config").path("params").path("vectors");
        // 已有集合的维数或距离不匹配时拒绝继续使用。
        if (config.path("size").asInt() != dimensions || !config.path("distance").asText().equals(distance)) {
            // 保留已有集合，由调用方解决配置差异。
            throw new IllegalStateException("Qdrant 集合向量配置不匹配");
        }
    }

    /** 校验当前适配器支持的无名向量配置。 */
    private static void validateVectorConfig(int dimensions, String distance) {
        // 不接受零维、空距离或未知距离算法。
        if (dimensions < 1 || distance == null || !Set.of("Cosine", "Dot", "Euclid", "Manhattan").contains(distance)) {
            // 错误消息不包含认证信息。
            throw new IllegalArgumentException("无效向量配置");
        }
    }

    /** 将实体稳定编号映射为合法 UUID，原 ID 另外保存在 payload 中。 */
    public static String pointId(String id) {
        // 固定命名空间和 UTF-8 编码使同一实体始终对应同一点。
        return UUID.nameUUIDFromBytes(("myhelper:entity:" + id).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** 按编号还原完整实体；不根据实体类型选库或读取引用目标。 */
    @Override
    public Optional<WorldEntity> read(String id) {
        // 只获取明确指定的点，同时要求返回完整 payload。
        JsonNode result = send("POST", "/points", Map.of("ids", List.of(pointId(id)), "with_payload", true, "with_vector", true)).path("result");
        // 检查响应结构，避免将协议错误当作实体不存在。
        if (!result.isArray()) {
            // 返回无效结构时明确失败。
            throw new IllegalStateException("Qdrant 返回无效点列表");
        }
        // 空结果表示当前库中没有该点。
        if (result.isEmpty()) {
            // 不自动回退到 Neo4j。
            return Optional.empty();
        }
        // 读取本模块保存的完整实体快照。
        JsonNode entity = result.get(0).path("payload").path("entity");
        // 外部点若没有本模块的数据格式，不能假装还原成功。
        if (!entity.isTextual()) {
            // 显式报告不兼容的点内容。
            throw new IllegalStateException("Qdrant 点缺少实体快照");
        }
        // 保留原始正文、引用和向量值，不用搜索返回值重写实体。
        WorldEntity value = json.entity(entity.asText());
        // 检查 UUID 映射结果确实属于本次请求的原始 ID。
        if (!value.id().equals(id)) {
            // 不返回属于其他实体的数据。
            throw new IllegalStateException("Qdrant 点 ID 冲突");
        }
        // 返回完整实体，引用含义由上层处理。
        return Optional.of(value);
    }

    /** 保存实体内容及可选向量；无向量也保留实体和引用。 */
    @Override
    protected void save(List<WorldEntity> entities) {
        // 逐点提交，部分成功后由协调器使用快照补偿。
        for (WorldEntity entity : entities) {
            // 所有业务字段保留在完整 JSON 中，不做语义筛选。
            Map<String, Object> payload = new LinkedHashMap<>();
            // 保存原始实体编号供读回校验。
            payload.put("entity_id", entity.id());
            // 同步保存当前修订号。
            payload.put("revision", entity.revision());
            // 完整序列化类型、正文、引用、证据和其他字段。
            payload.put("entity", json.encode(entity));
            // 仅在实体实际携带向量时写入模型元信息。
            if (entity.embedding() != null) {
                // 记录向量模型名称。
                payload.put("model", entity.embedding().model());
                // 记录准确模型版本。
                payload.put("model_version", entity.embedding().modelVersion());
            }
            // 空映射代表无向量点；不能用零维数组、零向量或删除实体代替。
            Object vectors = entity.embedding() == null ? Map.of() : entity.embedding().values();
            // upsert 替换整个点，清空向量时仍保留当前正文与引用。
            Map<String, Object> point = Map.of("id", pointId(entity.id()), "vector", vectors, "payload", payload);
            // 等待操作完成并检查结果，不能把接收请求当作写入成功。
            completed(send("PUT", "/points?wait=true&ordering=strong", Map.of("points", List.of(point))));
        }
    }

    /** 幂等删除本次新增的点，供失败补偿使用。 */
    @Override
    protected void remove(String id) {
        // wait=true 等待删除完成，重复删除不存在的点也可安全执行。
        completed(send("POST", "/points/delete?wait=true&ordering=strong", Map.of("points", List.of(pointId(id)))));
    }

    /** 明确区分操作完成与仅被服务端接收。 */
    private void completed(JsonNode response) {
        // 只有 completed 才能作为本分支的成功结果。
        if (!response.path("result").path("status").asText().equals("completed")) {
            // 让协调器处理未确认的提交。
            throw new IllegalStateException("Qdrant 操作尚未确认完成");
        }
    }

    /** 发送请求并统一校验 HTTP 与 Qdrant 的响应状态。 */
    private JsonNode send(String method, String path, Object body) {
        // 网络异常和服务异常都向上抛出，保留统一补偿入口。
        return checked(request(method, path, body));
    }

    /** 检查服务响应，不将错误状态解释为成功。 */
    private JsonNode checked(HttpResponse<String> response) {
        // 所有非 2xx HTTP 状态均视为失败。
        if (response.statusCode() / 100 != 2) {
            // 只报告状态码，避免服务响应泄露认证或业务数据。
            throw new IllegalStateException("Qdrant HTTP " + response.statusCode());
        }
        // 解析结构化响应。
        JsonNode root = json.parse(response.body());
        // Qdrant 的服务状态也必须成功。
        if (!root.path("status").asText().equals("ok")) {
            // 拒绝把 HTTP 200 内的服务错误当作成功。
            throw new IllegalStateException("Qdrant 响应失败");
        }
        // 返回校验后的响应供具体操作读取。
        return root;
    }

    /** 按配置地址、认证及超时执行实际 HTTP 请求。 */
    private HttpResponse<String> request(String method, String path, Object body) {
        // 每次请求使用集合的固定基础路径。
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(collectionUrl + path));
        // 应用配置中的请求超时。
        builder.timeout(timeout);
        // 请求体统一使用 JSON。
        builder.header("Content-Type", "application/json");
        // 只有配置了非空密钥才附带认证头。
        if (apiKey != null && !apiKey.isBlank()) {
            // 密钥只进入请求头，不进入 URL 或日志。
            builder.header("api-key", apiKey);
        }
        // GET 等无请求体操作使用 noBody，其余编码完整 JSON。
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.encode(body));
        // 明确设置 HTTP 方法与请求体。
        builder.method(method, publisher);
        // 同步等待远端响应，让协调器掌握提交状态。
        try {
            // 响应体作为文本读取后交给统一 JSON 编解码器。
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        // 中断不能证明远端请求没有执行。
        } catch (InterruptedException error) {
            // 保留线程中断标志，不吞掉取消信号。
            Thread.currentThread().interrupt();
            // 原始异常让协调器识别“结果未知”。
            throw new IllegalStateException("Qdrant 请求中断，结果可能未知", error);
        // 网络失败同样可能发生在服务端已写入之后。
        } catch (IOException error) {
            // 交给上层核对实际数据库状态。
            throw new IllegalStateException("Qdrant 请求失败，结果可能未知", error);
        }
    }
}
