package com.example.desktopbrain.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;

/**
 * Home Assistant REST API 客户端
 * 封装与 HA 的所有 HTTP 交互，包括：
 * - 设备/实体状态查询
 * - 服务调用（开灯、关空调等）
 * - 传感器数据获取
 */
@Component
public class HomeAssistantClient {

    private final WebClient webClient;
    private final String accessToken;
    private final ObjectMapper objectMapper;

    public HomeAssistantClient(
            @Value("${homeassistant.url}") String haUrl,
            @Value("${homeassistant.access-token}") String accessToken,
            WebClient.Builder webClientBuilder) {
        this.accessToken = accessToken;
        this.objectMapper = new ObjectMapper();
        this.webClient = webClientBuilder
                .baseUrl(haUrl)
                .defaultHeader("Authorization", "Bearer " + accessToken)
                .build();
    }

    // ========== 实体查询 ==========

    /**
     * 获取所有实体列表
     * 返回 HA 中所有设备/传感器的状态
     */
    public List<Map<String, Object>> getAllStates() {
        try {
            String response = webClient.get()
                    .uri("/api/states")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return objectMapper.readValue(response,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        } catch (Exception e) {
            System.err.println("❌ HA 查询所有实体失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 根据实体 ID 获取状态
     * 例如：getState("light.living_room") 返回客厅灯的状态
     */
    public Map<String, Object> getState(String entityId) {
        try {
            String response = webClient.get()
                    .uri("/api/states/" + entityId)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return objectMapper.readValue(response, Map.class);
        } catch (Exception e) {
            System.err.println("❌ HA 查询实体 " + entityId + " 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取特定领域的所有实体
     * 例如：getStatesByDomain("light") 获取所有灯
     */
    public List<Map<String, Object>> getStatesByDomain(String domain) {
        try {
            List<Map<String, Object>> all = getAllStates();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> state : all) {
                String entityId = (String) state.get("entity_id");
                if (entityId != null && entityId.startsWith(domain + ".")) {
                    result.add(state);
                }
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 搜索实体（按关键字匹配 entity_id 或 attributes）
     */
    public List<Map<String, Object>> searchEntities(String keyword) {
        try {
            List<Map<String, Object>> all = getAllStates();
            List<Map<String, Object>> result = new ArrayList<>();
            String lowerKeyword = keyword.toLowerCase();
            for (Map<String, Object> state : all) {
                String entityId = (String) state.get("entity_id");
                if (entityId != null && entityId.toLowerCase().contains(lowerKeyword)) {
                    result.add(state);
                }
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // ========== 服务调用 ==========

    /**
     * 调用 HA 服务（通用方法）
     *
     * @param domain  服务领域（如 "light", "climate", "switch"）
     * @param service 服务名（如 "turn_on", "turn_off", "toggle"）
     * @param entityId 目标实体 ID
     * @param params   额外参数（如亮度、温度等）
     */
    public boolean callService(String domain, String service, String entityId,
                               Map<String, Object> params) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("entity_id", entityId);
            if (params != null) {
                payload.putAll(params);
            }

            webClient.post()
                    .uri("/api/services/" + domain + "/" + service)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return true;
        } catch (Exception e) {
            System.err.println("❌ HA 调用服务 " + domain + "/" + service + " 失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 调用 HA 服务（简化版，只有 entity_id）
     */
    public boolean callService(String domain, String service, String entityId) {
        return callService(domain, service, entityId, null);
    }

    // ========== 便捷操作 ==========

    /** 开灯 */
    public boolean turnOnLight(String entityId) {
        return callService("light", "turn_on", entityId);
    }

    /** 关灯 */
    public boolean turnOffLight(String entityId) {
        return callService("light", "turn_off", entityId);
    }

    /** 切换灯开关 */
    public boolean toggleLight(String entityId) {
        return callService("light", "toggle", entityId);
    }

    /** 设置灯亮度 (0-255) */
    public boolean setBrightness(String entityId, int brightness) {
        Map<String, Object> params = Map.of("brightness", brightness);
        return callService("light", "turn_on", entityId, params);
    }

    /** 设置空调温度 */
    public boolean setTemperature(String entityId, double temperature) {
        Map<String, Object> params = Map.of("temperature", temperature);
        return callService("climate", "set_temperature", entityId, params);
    }

    /** 打开开关/插座 */
    public boolean turnOnSwitch(String entityId) {
        return callService("switch", "turn_on", entityId);
    }

    /** 关闭开关/插座 */
    public boolean turnOffSwitch(String entityId) {
        return callService("switch", "turn_off", entityId);
    }

    // ========== 系统状态 ==========

    /** 检查 HA 连接是否正常 */
    public boolean isConnected() {
        try {
            webClient.get()
                    .uri("/api/")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 获取 HA 配置信息 */
    public Map<String, Object> getConfig() {
        try {
            String response = webClient.get()
                    .uri("/api/config")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return objectMapper.readValue(response, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
