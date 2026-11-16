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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Qdrant REST 适配器；无名向量用于检索，payload 保存完整实体与引用。 */
public final class QdrantEntityStore extends SnapshotParticipant {
    /** 明确拒绝接收写入的 HTTP 状态，未知或超时状态需要核对。 */
    private static final Set<Integer> REJECTED_WRITE_STATUSES = Set.of(400, 401, 403, 404, 405, 413, 415, 422, 429);

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
        // 单条与批量读取共享点身份及快照校验。
        return Optional.ofNullable(readBatch(List.of(id)).get(id));
    }

    /** 一个 HTTP 请求取得批次中的点，缺失项省略。 */
    @Override
    public Map<String, WorldEntity> readBatch(List<String> ids) {
        // 明确转换请求 ID，禁止根据返回的实体类型再查别的库。
        List<String> points = new ArrayList<>();
        // 保持输入顺序；返回顺序由统一入口恢复。
        for (String id : ids) {
            // 只转换物理点 ID，不改业务 ID。
            points.add(pointId(id));
        }
        // 完整嵌入在实体 JSON 中，不需要服务端重新返回或归一化向量。
        JsonNode result = send("POST", "/points", Map.of("ids", points, "with_payload", true, "with_vector", false)).path("result");
        // 检查响应结构，避免将协议错误当作实体不存在。
        if (!result.isArray()) {
            // 返回无效结构时明确失败。
            throw new IllegalStateException("Qdrant 返回无效点列表");
        }
        // 收集真实存在的实体，空数组自然返回空映射。
        Map<String, WorldEntity> entities = new LinkedHashMap<>();
        // 每个点都必须符合完整实体合同。
        for (JsonNode point : result) {
            // 校验点 ID 与原实体 ID 的对应关系。
            WorldEntity entity = decodePoint(point);
            // 返回未请求或重复的点属于协议故障。
            if (!ids.contains(entity.id()) || entities.put(entity.id(), entity) != null) {
                // 不把后端异常当作实体缺失。
                throw new IllegalStateException("Qdrant 批量读取返回异常实体");
            }
        }
        // 结果不可修改，不附带跨库读取。
        return Collections.unmodifiableMap(entities);
    }

    /** 按字段条件分页，使用 Qdrant 原生 scroll 游标。 */
    @Override
    public StorageQuery.Page query(StorageQuery.PageRequest request) {
        // 基础请求只含固定数量和完整快照。
        Map<String, Object> body = new LinkedHashMap<>();
        // Qdrant 在服务端执行限制。
        body.put("limit", request.limit());
        // 实体始终从 payload 中还原。
        body.put("with_payload", true);
        // 不用数据库返回的归一化向量覆盖实体原值。
        body.put("with_vector", false);
        // 只有实际字段条件才设置过滤器。
        List<Object> must = conditions(request.filter());
        // 空条件允许扫描旧版记录以便显式重写投影。
        if (!must.isEmpty()) {
            // 每个字段条件必须同时成立。
            body.put("filter", Map.of("must", must));
        }
        // 首次查询不传 offset。
        if (request.cursor() != null) {
            // 本模块只写 UUID 点，拒绝误用的非 UUID 游标。
            body.put("offset", UUID.fromString(request.cursor().position()).toString());
        }
        // 读取本页，不自动循环。
        JsonNode result = send("POST", "/points/scroll", body).path("result");
        // 协议结构与数量必须符合本页请求。
        JsonNode points = pointList(result, request.limit());
        // 收集完整实体快照。
        List<WorldEntity> entities = new ArrayList<>();
        // 保留服务端的点顺序。
        for (JsonNode point : points) {
            // 不丢弃引用或替调用方判断类型。
            entities.add(decodePoint(point));
        }
        // Qdrant 返回 null 时已经没有下一页。
        JsonNode offset = result.get("next_page_offset");
        // 缺失分页字段是协议错误，不伪装为最后一页。
        if (offset == null || (!offset.isNull() && !offset.isTextual())) {
            // 本集合的写入格式只允许 UUID 点。
            throw new IllegalStateException("Qdrant 返回无效分页位置");
        }
        // 只有非空位置才建立下一页游标。
        StorageQuery.Cursor next = offset.isNull() ? null
                : new StorageQuery.Cursor(DualWriteStore.Database.QDRANT, UUID.fromString(offset.asText()).toString());
        // 防止异常服务不断返回同一个续页位置。
        if (next != null && next.equals(request.cursor())) {
            // 不让调用方在无进展页面无限循环。
            throw new IllegalStateException("Qdrant 分页位置没有前进");
        }
        // 明确返回续页位置，调用方自行决定是否继续。
        return new StorageQuery.Page(entities, next);
    }

    /** 通过 Query points 查询向量候选，保留后端分数，不判断执行资格。 */
    @Override
    public List<StorageQuery.Hit> search(StorageQuery.VectorRequest request) {
        // 已绑定配置时立即验证维数，避免请求错误的向量空间。
        if (configuredDimensions != null && request.vector().size() != configuredDimensions) {
            // 手动构造未绑定维数时仍由数据库检查。
            throw new IllegalArgumentException("查询向量维数与 Qdrant 配置不符");
        }
        // 保持原有 model/model_version payload 过滤，兼容既有向量点。
        List<Object> must = new ArrayList<>();
        // 同模型名称才允许比较。
        must.add(Map.of("key", "model", "match", Map.of("value", request.model())));
        // 相同版本才属于本次指定的向量空间。
        must.add(Map.of("key", "model_version", "match", Map.of("value", request.modelVersion())));
        // 业务调用方可以额外传入类型、范围等条件。
        must.addAll(conditions(request.filter()));
        // 建立本次独立请求，支持共享适配器并发使用。
        Map<String, Object> body = new LinkedHashMap<>();
        // 嵌入由上层生成，本模块只传输向量。
        body.put("query", request.vector());
        // 服务器侧限制候选数量。
        body.put("limit", request.limit());
        // 返回完整实体 payload。
        body.put("with_payload", true);
        // 完整实体已经携带原始嵌入值。
        body.put("with_vector", false);
        // 同时应用向量空间和字段条件。
        body.put("filter", Map.of("must", must));
        // null 表示不设置分数阈值。
        if (request.scoreThreshold() != null) {
            // 阈值的方向由集合的距离度量决定。
            body.put("score_threshold", request.scoreThreshold());
        }
        // 所有数据库 HTTP 都经本适配器的状态、认证与超时处理。
        JsonNode result = send("POST", "/points/query", body).path("result");
        // 不把错误响应解释成空候选。
        JsonNode points = pointList(result, request.limit());
        // 返回实体与原始分数。
        List<StorageQuery.Hit> hits = new ArrayList<>();
        // 不自动加载权威版本或展开引用。
        for (JsonNode point : points) {
            // 缺少 score 时不能默默填零。
            if (!point.path("score").isNumber()) {
                // 将协议故障交给调用方。
                throw new IllegalStateException("Qdrant 候选缺少分数");
            }
            // Hit 构造器进一步拒绝非有限分数。
            hits.add(new StorageQuery.Hit(decodePoint(point), point.path("score").asDouble()));
        }
        // 结果不允许修改。
        return List.copyOf(hits);
    }

    /** 两类列表查询共用形状和数量校验。 */
    private JsonNode pointList(JsonNode result, int limit) {
        // scroll 和 query 都返回 result.points。
        JsonNode points = result.path("points");
        // 超量响应明确失败，不丢弃记录后伪造连续游标。
        if (!points.isArray() || points.size() > limit) {
            // 任何协议异常都不当作合法空页。
            throw new IllegalStateException("Qdrant 返回无效候选列表");
        }
        // 调用方继续校验每个点的内容。
        return points;
    }

    /** 生成由字段等值投影支持的 AND / OR 条件。 */
    private List<Object> conditions(StorageQuery.Filter filter) {
        // 外层 must 表示每个字段都必须匹配。
        List<Object> must = new ArrayList<>();
        // 组内任一候选都可以命中同一字段。
        for (List<String> group : QueryTerms.groups(json, filter)) {
            // match.any 对 query_terms 字符串数组执行成员匹配。
            must.add(Map.of("key", "query_terms", "match", Map.of("any", group)));
        }
        // 当前请求独占此列表，不共享可变状态。
        return must;
    }

    /** 完整快照是实体真值，物理点身份必须与业务 ID 对应。 */
    private WorldEntity decodePoint(JsonNode point) {
        // 读取本模块保存的完整实体快照。
        JsonNode entity = point.path("payload").path("entity");
        // 外部点若没有本模块的数据格式，不能假装还原成功。
        if (!entity.isTextual()) {
            // 显式报告不兼容的点内容。
            throw new IllegalStateException("Qdrant 点缺少实体快照");
        }
        // 保留原始正文、引用和向量值，不用搜索返回值重写实体。
        WorldEntity value = json.entity(entity.asText());
        // 检查 UUID 映射结果确实属于本次请求的原始 ID。
        if (!point.path("id").asText().equals(pointId(value.id()))) {
            // 不返回属于其他实体的数据。
            throw new IllegalStateException("Qdrant 点 ID 冲突");
        }
        // 返回完整实体，引用含义由上层处理。
        return value;
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
            String encoded = json.encode(entity);
            // 同一个 upsert 保存快照与派生字段，补偿时也一起恢复。
            payload.put("entity", encoded);
            // 只增加查询投影，不改变实体业务结构。
            payload.put("query_terms", QueryTerms.project(json, encoded));
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
            mutate("PUT", "/points?wait=true&ordering=strong", Map.of("points", List.of(point)));
        }
    }

    /** 幂等删除本次新增的点，供失败补偿使用。 */
    @Override
    protected void remove(String id) {
        // wait=true 等待删除完成，重复删除不存在的点也可安全执行。
        mutate("POST", "/points/delete?wait=true&ordering=strong", Map.of("points", List.of(pointId(id))));
    }

    /** 没有可靠完成回执时保留写入结果未知，供协调器和调用方核对。 */
    private void mutate(String method, String path, Object body) {
        HttpResponse<String> response;
        try {
            response = request(method, path, body);
        } catch (IllegalStateException failure) {
            if (failure.getCause() instanceof IOException || failure.getCause() instanceof InterruptedException) {
                throw new DualWriteStore.WriteOutcomeUnknown("Qdrant 未收到写入回执", failure);
            }
            throw failure;
        }
        int status = response.statusCode();
        // 只把有明确拒绝含义的响应当作普通失败。超时、中断和不熟悉的状态仍需核对。
        if (REJECTED_WRITE_STATUSES.contains(status)) {
            throw new IllegalStateException("Qdrant HTTP " + status);
        }
        JsonNode result;
        try {
            result = checked(response);
        } catch (RuntimeException failure) {
            throw new DualWriteStore.WriteOutcomeUnknown("Qdrant 写入回执无法确认", failure);
        }
        if (!result.path("result").path("status").asText().equals("completed")) {
            throw new DualWriteStore.WriteOutcomeUnknown("Qdrant 操作尚未确认完成", null);
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
