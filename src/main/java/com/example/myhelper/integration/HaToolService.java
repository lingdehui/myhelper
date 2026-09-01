package com.example.myhelper.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Home Assistant 工具服务
 * 将 HA 能力封装为 Spring AI @Tool，让 AI 能直接调用控制智能家居
 *
 * 使用示例：
 * - "打开客厅的灯" -> AI 调用 turn_on_light(entityId="light.living_room")
 * - "空调调到26度" -> AI 调用 set_ac_temperature(entityId="climate.bedroom", temperature=26)
 * - "现在家里有哪些设备" -> AI 调用 list_devices()
 */
@Component
public class HaToolService {

    private final HomeAssistantClient haClient;
    private final DeviceDiscoveryService discovery;
    private final ObjectMapper objectMapper;

    public HaToolService(HomeAssistantClient haClient,
                         DeviceDiscoveryService discovery) {
        this.haClient = haClient;
        this.discovery = discovery;
        this.objectMapper = new ObjectMapper();
    }

    // ========== 设备查询工具 ==========

    /**
     * 列出所有智能家居设备及其状态
     * AI 可以此了解当前家中设备情况
     */
    @Tool(description = "列出所有智能家居设备及其当前状态。包括灯、空调、开关、传感器等。")
    public String listDevices() {
        List<Map<String, Object>> states = haClient.getAllStates();
        if (states.isEmpty()) {
            return "暂无设备或 Home Assistant 未连接";
        }
        StringBuilder sb = new StringBuilder("家中设备列表：\n");
        for (Map<String, Object> state : states) {
            String entityId = (String) state.get("entity_id");
            String entityState = (String) state.get("state");
            if (entityId != null && entityState != null) {
                // 只列出有意义的实体（排除自动化、脚本等）
                if (entityId.startsWith("light.") || entityId.startsWith("climate.")
                        || entityId.startsWith("switch.") || entityId.startsWith("sensor.")
                        || entityId.startsWith("binary_sensor.") || entityId.startsWith("cover.")
                        || entityId.startsWith("fan.") || entityId.startsWith("media_player.")) {
                    sb.append(String.format("  %s: %s\n", entityId, entityState));
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 查询特定设备的当前状态
     */
    @Tool(description = "查询指定设备的当前状态，包括开关状态、温度、亮度等参数。")
    public String getDeviceState(
            @ToolParam(description = "设备实体ID，如 light.living_room, climate.bedroom") String entityId) {
        Map<String, Object> state = haClient.getState(entityId);
        if (state == null) {
            return "未找到设备: " + entityId;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("设备: ").append(entityId).append("\n");
        sb.append("状态: ").append(state.get("state")).append("\n");

        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) state.get("attributes");
        if (attributes != null) {
            // 列出重要属性
            String[] keys = {"friendly_name", "temperature", "brightness", "humidity",
                    "power_consumption", "battery_level", "unit_of_measurement"};
            for (String key : keys) {
                Object value = attributes.get(key);
                if (value != null) {
                    sb.append(key).append(": ").append(value).append("\n");
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 搜索设备（按名称或领域）
     */
    @Tool(description = "按关键字搜索设备，返回匹配的设备列表。支持按房间、设备类型搜索。")
    public String searchDevices(
            @ToolParam(description = "搜索关键字，如 '客厅'、'空调'、'灯'") String keyword) {
        List<Map<String, Object>> results = haClient.searchEntities(keyword);
        if (results.isEmpty()) {
            return "未找到匹配 '" + keyword + "' 的设备";
        }
        StringBuilder sb = new StringBuilder("搜索结果（").append(results.size()).append(" 个设备）：\n");
        for (Map<String, Object> state : results) {
            sb.append(String.format("  %s: %s\n", state.get("entity_id"), state.get("state")));
        }
        return sb.toString().trim();
    }

    @Tool(description = "通用 Home Assistant 设备控制。service 可写 domain.service（如 light.turn_on）或仅服务名（如 turn_on，会从实体ID推断领域）；paramsJson 为可选 JSON 对象。")
    public String controlDevice(
            @ToolParam(description = "设备实体ID，如 light.living_room") String entityId,
            @ToolParam(description = "服务名，如 light.turn_on、climate.set_temperature 或 turn_on") String service,
            @ToolParam(description = "可选 JSON 参数对象，例如 {\"brightness\":180}；没有参数传 {}") String paramsJson) {
        if (entityId == null || !entityId.contains(".")) return "设备实体ID无效：应为 domain.object 格式。";
        if (service == null || service.isBlank()) return "服务名不能为空。";

        String domain = entityId.substring(0, entityId.indexOf('.')).trim();
        String serviceName = service.trim();
        int dot = serviceName.indexOf('.');
        if (dot >= 0) {
            domain = serviceName.substring(0, dot).trim();
            serviceName = serviceName.substring(dot + 1).trim();
        }
        if (domain.isBlank() || serviceName.isBlank()) return "服务名无效：请使用 domain.service 或服务名。";

        try {
            Map<String, Object> params = parseServiceParams(paramsJson);
            params.remove("entity_id");
            boolean success = haClient.callService(domain, serviceName, entityId, params);
            return success ? "✅ 已调用 " + domain + "." + serviceName + " 控制 " + entityId
                    : "❌ 调用失败，请检查实体ID、服务名和 Home Assistant 连接状态。";
        } catch (IllegalArgumentException e) {
            return "参数无效：" + e.getMessage();
        }
    }

    private Map<String, Object> parseServiceParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank() || "{}".equals(paramsJson.trim())) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            JsonNode node = objectMapper.readTree(paramsJson);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("paramsJson 必须是 JSON 对象。");
            return objectMapper.convertValue(node, new TypeReference<java.util.LinkedHashMap<String, Object>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("paramsJson 不是合法 JSON 对象。");
        }
    }

    // ========== 灯光控制工具 ==========

    /** 打开指定的灯 */
    @Tool(description = "打开指定的灯。entity_id 可通过 list_devices 或 search_devices 获取。")
    public String turnOnLight(
            @ToolParam(description = "灯的实体ID，如 light.living_room") String entityId) {
        boolean success = haClient.turnOnLight(entityId);
        return success ? "✅ 已打开 " + entityId : "❌ 操作失败，请检查设备ID是否正确";
    }

    /** 关闭指定的灯 */
    @Tool(description = "关闭指定的灯。")
    public String turnOffLight(
            @ToolParam(description = "灯的实体ID，如 light.living_room") String entityId) {
        boolean success = haClient.turnOffLight(entityId);
        return success ? "✅ 已关闭 " + entityId : "❌ 操作失败";
    }

    @Tool(description = "切换指定灯的开关状态；当前开则关、当前关则开。")
    public String toggleLight(
            @ToolParam(description = "灯的实体ID，如 light.living_room") String entityId) {
        boolean success = haClient.toggleLight(entityId);
        return success ? "✅ 已切换 " + entityId + " 的开关状态" : "❌ 操作失败";
    }

    /** 设置灯的亮度 */
    @Tool(description = "调节灯的亮度，范围 0-255。")
    public String setLightBrightness(
            @ToolParam(description = "灯的实体ID") String entityId,
            @ToolParam(description = "亮度值，0-255，数字越大越亮") int brightness) {
        boolean success = haClient.setBrightness(entityId, brightness);
        return success ? "✅ 已将 " + entityId + " 亮度设为 " + brightness : "❌ 操作失败";
    }

    // ========== 空调控制工具 ==========

    /** 设置空调温度 */
    @Tool(description = "设置空调/恒温器的目标温度。")
    public String setAcTemperature(
            @ToolParam(description = "空调实体ID，如 climate.bedroom") String entityId,
            @ToolParam(description = "目标温度，如 26.0") double temperature) {
        boolean success = haClient.setTemperature(entityId, temperature);
        return success ? "✅ 已将 " + entityId + " 温度设为 " + temperature + "°C" : "❌ 操作失败";
    }

    // ========== 开关控制工具 ==========

    /** 打开开关/插座 */
    @Tool(description = "打开智能开关或插座。")
    public String turnOnSwitch(
            @ToolParam(description = "开关实体ID，如 switch.fan") String entityId) {
        boolean success = haClient.turnOnSwitch(entityId);
        return success ? "✅ 已打开 " + entityId : "❌ 操作失败";
    }

    /** 关闭开关/插座 */
    @Tool(description = "关闭智能开关或插座。")
    public String turnOffSwitch(
            @ToolParam(description = "开关实体ID") String entityId) {
        boolean success = haClient.turnOffSwitch(entityId);
        return success ? "✅ 已关闭 " + entityId : "❌ 操作失败";
    }

    // ========== 传感器查询 ==========

    /** 获取所有传感器读数 */
    @Tool(description = "获取所有传感器的最新读数，包括温度、湿度、电量、能耗等。")
    public String getSensorReadings() {
        List<Map<String, Object>> sensors = haClient.getStatesByDomain("sensor");
        List<Map<String, Object>> binarySensors = haClient.getStatesByDomain("binary_sensor");

        StringBuilder sb = new StringBuilder("传感器数据：\n");
        for (Map<String, Object> s : sensors) {
            String id = (String) s.get("entity_id");
            String state = (String) s.get("state");
            @SuppressWarnings("unchecked")
            Map<String, Object> attrs = (Map<String, Object>) s.get("attributes");
            String unit = attrs != null ? (String) attrs.getOrDefault("unit_of_measurement", "") : "";
            sb.append(String.format("  %s: %s %s\n", id, state, unit));
        }
        for (Map<String, Object> s : binarySensors) {
            sb.append(String.format("  %s: %s\n", s.get("entity_id"), s.get("state")));
        }
        return sb.toString().trim();
    }

    // ========== 系统状态 ==========

    /** 检查 Home Assistant 连接状态 */
    @Tool(description = "检查与 Home Assistant 的连接是否正常。")
    public String checkConnection() {
        boolean connected = haClient.isConnected();
        return connected ? "✅ Home Assistant 连接正常" : "❌ 无法连接到 Home Assistant";
    }

    // ========== 设备发现工具 ==========

    /**
     * 扫描局域网设备
     * 项目在原生 Windows 运行，能直接 Ping 内网。
     */
    @Tool(description = "扫描局域网，发现 3D打印机/智能设备/Chromecast/摄像头等。" +
            "支持端口指纹+HTTP响应识别设备型号。返回发现的设备列表和注册状态。")
    public String scanNetworkDevices() {
        java.util.List<java.util.Map<String, Object>> devices = discovery.scan();
        if (devices.isEmpty()) {
            return "未发现设备。当前子网可能没有开放端口的智能设备。" +
                    "\n支持识别: OctoPrint(5000), Moonraker(7125), 拓竹(6000), ESPHome(6053), " +
                    "Chromecast(8008), Node-RED(1880), Web服务(80/8080)";
        }
        StringBuilder sb = new StringBuilder("发现 " + devices.size() + " 个设备：\n\n");
        for (java.util.Map<String, Object> d : devices) {
            sb.append(String.format("[%s] %s  %s:%s  %s\n",
                    d.get("type"), d.get("name"), d.get("ip"), d.get("port"),
                    Boolean.TRUE.equals(d.get("registered")) ? "✓已注册" : "✗未注册"));
        }
        return sb.toString().trim();
    }

    /**
     * 注册发现的设备到 Home Assistant
     */
    @Tool(description = "将 scanNetworkDevices 发现的未注册设备自动注册到 Home Assistant。" +
            "成功注册后可在 HA 中管理和控制这些设备。")
    public String registerDiscoveredDevice() {
        java.util.Map<String, Object> result = discovery.registerToHA();

        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> registered =
                (java.util.List<java.util.Map<String, Object>>) result.get("registered");
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> failed =
                (java.util.List<java.util.Map<String, Object>>) result.get("failed");

        StringBuilder sb = new StringBuilder(result.get("summary") + "\n");
        for (var d : registered) sb.append("✅ ").append(d.get("name")).append(" (").append(d.get("ip")).append(")\n");
        for (var d : failed) sb.append("❌ ").append(d.get("name")).append(" (").append(d.get("ip")).append(")\n");
        return sb.toString().trim();
    }

    @Tool(description = "按自然语言语义搜索已持久化的局域网发现设备，例如“3D打印机”“客厅投屏设备”。请先扫描网络以建立设备库。")
    public String searchDiscoveredDevices(
            @ToolParam(description = "设备类型、名称或用途关键词") String query) {
        List<Map<String, Object>> matches = discovery.searchSimilarDevices(query, 10);
        if (matches.isEmpty()) return "未找到相关的已发现设备。可先调用 scanNetworkDevices 重新扫描。";
        StringBuilder result = new StringBuilder("语义搜索结果（").append(matches.size()).append(" 个）：\n");
        for (Map<String, Object> device : matches) {
            result.append("- ").append(device.getOrDefault("name", "未命名设备"))
                    .append(" [").append(device.getOrDefault("type", "unknown")).append("] ")
                    .append(device.getOrDefault("ip", "未知地址")).append(':')
                    .append(device.getOrDefault("port", "")).append('\n');
        }
        return result.toString().trim();
    }
}
