package com.example.myhelper.integration;

import com.example.myhelper.config.SystemEnvironmentService;
import com.example.myhelper.memory.vector.EmbeddingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * 局域网设备发现服务
 *
 * 项目运行在原生 Windows，直接扫描物理局域网。
 * 扫到设备后：识别类型 → 存 Qdrant → 注册到 HA。
 */
@Service
public class DeviceDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(DeviceDiscoveryService.class);

    /** 端口 → (设备类型, API路径, 人类可读名) */
    record Fingerprint(int port, String type, String apiPath, String displayName) {}

    private static final List<Fingerprint> FINGERPRINTS = List.of(
            new Fingerprint(7125, "moonraker",    "/printer/info",   "3D打印机(Klipper)"),
            new Fingerprint(5000, "octoprint",    "/api/version",    "3D打印机(OctoPrint)"),
            new Fingerprint(6000, "bambu_lab",    "/",               "3D打印机(拓竹)"),
            new Fingerprint(8123, "homeassistant","/api/",           "Home Assistant"),
            new Fingerprint(6053, "esphome",      "/",               "ESPHome设备"),
            new Fingerprint(1880, "nodered",      "/",               "Node-RED"),
            new Fingerprint(8008, "chromecast",   "/",               "Chromecast"),
            new Fingerprint(80,   "web",          "/",               "Web服务"),
            new Fingerprint(8080, "web",          "/",               "Web服务")
    );

    private final WebClient qdrant;
    private final HomeAssistantClient haClient;
    private final EmbeddingService embeddingService;
    private final String haUrl;
    private final String collectionName;
    private final int vectorSize;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService pool = Executors.newFixedThreadPool(50);

    private final List<Map<String, Object>> devices = new CopyOnWriteArrayList<>();
    private volatile boolean scanning;

    private String subnet; // 启动时自动探测，如 "192.168.31"

    public DeviceDiscoveryService(@Qualifier("qdrantWebClient") WebClient qdrant,
                                   HomeAssistantClient haClient,
                                   EmbeddingService embeddingService,
                                   SystemEnvironmentService environmentService,
                                   @org.springframework.beans.factory.annotation.Value("${homeassistant.url:http://localhost:8123}") String haUrl,
                                   @org.springframework.beans.factory.annotation.Value("${qdrant.vector-size:768}") int vectorSize) {
        this.qdrant = qdrant;
        this.haClient = haClient;
        this.embeddingService = embeddingService;
        this.haUrl = haUrl;
        this.collectionName = environmentService.collectionName("discovered-devices");
        this.vectorSize = vectorSize;
        initQdrantCollection();
    }

    private void initQdrantCollection() {
        try {
            qdrant.put()
                    .uri("/collections/" + collectionName)
                    .header("Content-Type", "application/json")
                    .bodyValue("{\"vectors\":{\"size\":" + vectorSize + ",\"distance\":\"Cosine\"}}")
                    .retrieve().toBodilessEntity().block();
        } catch (Exception ignored) {}
    }

    // ========== 公开方法（Tool 调用） ==========

    /** 从本机 IP 自动探测子网 */
    private void detectSubnet() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                    InetAddress ip = addr.getAddress();
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress()
                            && ip.getHostAddress().startsWith("192.168")) {
                        String full = ip.getHostAddress();
                        subnet = full.substring(0, full.lastIndexOf('.'));
                        log.info("🌐 探测到子网: {}.0/24", subnet);
                        return;
                    }
                }
            }
            subnet = "192.168.31";
        } catch (Exception e) {
            subnet = "192.168.31";
        }
    }

    /** AI 工具调用入口 */
    public List<Map<String, Object>> scan() {
        if (scanning) return devices;
        scanning = true;
        devices.clear();
        if (subnet == null) detectSubnet();
        long t0 = System.currentTimeMillis();

        // 1. Ping 全网段
        List<InetAddress> alive = pingSweep();

        // 2. 端口指纹
        fingerprint(alive);

        // 3. 加载 Qdrant 历史 + 合并去重
        loadFromQdrant();

        // 4. 对比 HA 已注册
        markRegistered();

        // 每次扫描都写入带真实语义向量的历史记录，供后续检索和去重使用。
        saveToQdrant();

        log.info("🔍 扫描完成: {} 个设备 ({}ms)", devices.size(), System.currentTimeMillis() - t0);
        scanning = false;
        return devices;
    }

    /** AI 工具调用：获取已发现设备 */
    public List<Map<String, Object>> getDevices() {
        return new ArrayList<>(devices);
    }

    /** 从环境隔离的 Qdrant 设备库中按语义检索。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchSimilarDevices(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        try {
            List<Float> vector = embeddingService.embed(query);
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("vector", vector);
            request.put("limit", Math.max(1, Math.min(limit, 50)));
            request.put("with_payload", true);
            String response = qdrant.post()
                    .uri("/collections/" + collectionName + "/points/search")
                    .header("Content-Type", "application/json")
                    .bodyValue(mapper.writeValueAsString(request))
                    .retrieve().bodyToMono(String.class).block();
            if (response == null) return List.of();

            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode point : mapper.readTree(response).path("result")) {
                Map<String, Object> payload = mapper.convertValue(point.path("payload"), Map.class);
                if (payload != null && !payload.isEmpty()) result.add(payload);
            }
            return result;
        } catch (Exception e) {
            log.warn("设备语义搜索失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** AI 工具调用：注册到 HA */
    public Map<String, Object> registerToHA() {
        List<Map<String, Object>> ok = new ArrayList<>();
        List<Map<String, Object>> fail = new ArrayList<>();

        for (Map<String, Object> d : devices) {
            if (Boolean.TRUE.equals(d.get("registered"))) continue;
            String type = (String) d.get("type");
            String haIntegration = mapHA(type);
            if (haIntegration == null) continue;

            String ip = (String) d.get("ip");
            int port = (int) d.get("port");
            try {
                boolean r = registerOne(haIntegration, ip, port);
                d.put("registered", r);
                (r ? ok : fail).add(d);
            } catch (Exception e) {
                fail.add(d);
            }
        }

        saveToQdrant();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("registered", ok);
        result.put("failed", fail);
        result.put("summary", ok.size() + " 成功, " + fail.size() + " 失败");
        return result;
    }

    // ========== 扫描引擎 ==========

    private List<InetAddress> pingSweep() {
        List<InetAddress> alive = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(254);
        for (int i = 1; i <= 254; i++) {
            final int host = i;
            pool.submit(() -> {
                try {
                    InetAddress addr = InetAddress.getByName(subnet + "." + host);
                    if (addr.isReachable(250)) alive.add(addr);
                } catch (Exception ignored) {}
                latch.countDown();
            });
        }
        try { latch.await(12, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return alive;
    }

    private void fingerprint(List<InetAddress> alive) {
        List<Future<?>> fts = new ArrayList<>();
        for (InetAddress addr : alive) {
            fts.add(pool.submit(() -> probe(addr)));
        }
        for (Future<?> f : fts) {
            try { f.get(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
    }

    private void probe(InetAddress addr) {
        String ip = addr.getHostAddress();
        String hostname = resolve(addr);

        for (Fingerprint fp : FINGERPRINTS) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(ip, fp.port), 400);
                s.close();

                Map<String, Object> dev = new LinkedHashMap<>();
                dev.put("ip", ip);
                dev.put("hostname", hostname);
                dev.put("port", fp.port);
                dev.put("type", fp.type);
                dev.put("name", fp.displayName);
                dev.put("registered", false);

                if (fp.apiPath != null) httpFingerprint(dev, fp);

                // 同 IP 同类型去重
                boolean dup = devices.stream().anyMatch(
                        d -> ip.equals(d.get("ip")) && fp.type.equals(d.get("type")));
                if (!dup) {
                    devices.add(dev);
                    log.info("🔍 {} -> {}:{}", fp.displayName, ip, fp.port);
                }
            } catch (Exception ignored) {}
        }
    }

    private void httpFingerprint(Map<String, Object> dev, Fingerprint fp) {
        try {
            URL url = new URL("http://" + dev.get("ip") + ":" + fp.port + fp.apiPath);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(800); c.setReadTimeout(800);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() >= 200 && c.getResponseCode() < 500) {
                byte[] buf = new byte[4096];
                int len = c.getInputStream().read(buf);
                if (len > 0) parse(fp.type, new String(buf, 0, len), dev);
            }
            c.disconnect();
        } catch (Exception ignored) {}
    }

    private void parse(String type, String body, Map<String, Object> dev) {
        try {
            JsonNode r = mapper.readTree(body);
            switch (type) {
                case "moonraker" -> {
                    if (r.has("result")) {
                        JsonNode info = r.get("result");
                        dev.put("model", info.path("app").asText("Klipper"));
                        dev.put("name", "Klipper 3D打印机");
                    }
                }
                case "homeassistant" -> {
                    dev.put("name", "Home Assistant");
                    if (r.has("version")) dev.put("model", r.get("version").asText());
                }
                case "octoprint" -> {
                    dev.put("name", "OctoPrint 3D打印机");
                    if (r.has("text")) dev.put("model", r.get("text").asText());
                }
                case "esphome" -> {
                    dev.put("name", body.contains("ESPHome") ? "ESPHome设备" : "ESP设备");
                }
                default -> {
                    int ti = body.indexOf("<title>");
                    if (ti >= 0) {
                        int te = body.indexOf("</title>", ti);
                        if (te > ti) dev.put("name", body.substring(ti + 7, te).trim());
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private String resolve(InetAddress addr) {
        try { String h = addr.getHostName(); return h.equals(addr.getHostAddress()) ? null : h; }
        catch (Exception e) { return null; }
    }

    // ========== Qdrant 持久化 ==========

    private void saveToQdrant() {
        try {
            List<Map<String, Object>> points = new ArrayList<>();
            for (Map<String, Object> device : devices) {
                try {
                    List<Float> vector = embeddingService.embed(buildDeviceText(device));
                    if (vector == null || vector.isEmpty()) {
                        log.warn("跳过未生成向量的设备: {}", device.get("ip"));
                        continue;
                    }
                    Map<String, Object> point = new LinkedHashMap<>();
                    String idSeed = String.join("|", String.valueOf(device.get("ip")),
                            String.valueOf(device.get("type")), String.valueOf(device.get("port")));
                    point.put("id", UUID.nameUUIDFromBytes(idSeed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
                    point.put("vector", vector);
                    point.put("payload", device);
                    points.add(point);
                } catch (Exception e) {
                    // 不得用零向量污染语义库；单个设备失败不影响其它设备持久化。
                    log.warn("设备 {} 向量化失败，已跳过: {}", device.get("ip"), e.getMessage());
                }
            }
            if (points.isEmpty()) return;
            var payload = new LinkedHashMap<String, Object>();
            payload.put("points", points);
            qdrant.put()
                    .uri("/collections/" + collectionName + "/points?wait=true")
                    .header("Content-Type", "application/json")
                    .bodyValue(mapper.writeValueAsString(payload))
                    .retrieve().toBodilessEntity().block();
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private void loadFromQdrant() {
        try {
            String resp = qdrant.post()
                    .uri("/collections/" + collectionName + "/points/scroll")
                    .header("Content-Type", "application/json")
                    .bodyValue("{\"limit\":100,\"with_payload\":true}")
                    .retrieve().bodyToMono(String.class).block();
            if (resp != null) {
                JsonNode root = mapper.readTree(resp);
                for (JsonNode pt : root.path("result").path("points")) {
                    Map<String, Object> payload = mapper.convertValue(pt.get("payload"), Map.class);
                    if (payload != null && !devices.stream().anyMatch(
                            d -> d.get("ip").equals(payload.get("ip"))
                              && d.get("type").equals(payload.get("type")))) {
                        devices.add(payload);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    // ========== HA 注册 ==========

    private boolean registerOne(String haIntegration, String ip, int port) {
        try {
            var body = Map.of("host", ip + (port != 80 ? ":" + port : ""));
            URL url = new URL(haUrl + "/api/config/config_entries/flow/" + haIntegration);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + getToken());
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            c.setConnectTimeout(3000); c.setReadTimeout(3000);
            c.getOutputStream().write(mapper.writeValueAsBytes(body));
            int code = c.getResponseCode();
            c.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private String mapHA(String type) {
        return switch (type) {
            case "moonraker" -> "moonraker";
            case "octoprint" -> "octoprint";
            case "bambu_lab" -> "bambu_lab";
            case "esphome" -> "esphome";
            case "chromecast" -> "google_cast";
            case "nodered" -> "nodered";
            default -> null;
        };
    }

    private String getToken() {
        return haClient.getAccessToken();
    }

    private String buildDeviceText(Map<String, Object> device) {
        return String.join(" ",
                "设备", String.valueOf(device.getOrDefault("name", "")),
                "类型", String.valueOf(device.getOrDefault("type", "")),
                "型号", String.valueOf(device.getOrDefault("model", "")),
                "主机", String.valueOf(device.getOrDefault("hostname", "")),
                "地址", String.valueOf(device.getOrDefault("ip", "")));
    }

    // ========== 杂项 ==========

    private void markRegistered() {
        if (!haClient.isConnected()) return;
        Set<String> haIps = new HashSet<>();
        try {
            for (var s : haClient.getAllStates()) {
                var attrs = (Map<?, ?>) s.get("attributes");
                if (attrs != null && attrs.get("ip_address") != null)
                    haIps.add(attrs.get("ip_address").toString());
            }
        } catch (Exception ignored) {}
        for (var d : devices) d.put("registered", haIps.contains(d.get("ip")));
    }
}
