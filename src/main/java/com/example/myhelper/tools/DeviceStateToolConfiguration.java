package com.example.myhelper.tools;

import com.example.myhelper.world.WorldDeviceStatus;
import com.example.myhelper.world.model.EntityRef;
import com.example.myhelper.world.model.UnitType;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

/** 仅在宿主显式启用并登记固定实现后，装配 Home Assistant 状态读取工具。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = DeviceStateToolConfiguration.PREFIX + ".enabled", havingValue = "true")
@EnableConfigurationProperties(DeviceStateToolConfiguration.HaProperties.class)
@Import(ToolDirectoryConfiguration.class)
public class DeviceStateToolConfiguration {
    /** 设备状态工具专用配置，凭证只供实际读取适配器使用。 */
    static final String PREFIX = "myhelper.tools.device-state.ha";
    /** 未配置时采用的单次读取超时。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public DeviceStateTool deviceStateTool(HaProperties properties, ToolDirectory directory,
            @Qualifier("deviceStateHomeAssistantReader") HomeAssistantStateReader reader) {
        var ref = new ToolDirectory.ToolRef(properties.toolName(), properties.sourceInstance(),
                properties.implementationVersion());
        var registration = directory.find(ref.toolName()).orElse(null);
        if (registration == null || !ref.equals(registration.ref())) {
            throw new IllegalArgumentException("设备状态工具与目录登记的固定实现不一致");
        }
        if (properties.sensorUnitRevision() == null) {
            throw new IllegalArgumentException("缺少设备状态工具的 sensor-unit-revision");
        }
        var unit = new EntityRef(properties.sensorUnitId(), properties.sensorUnitRevision(),
                null, null, UnitType.TOOL, null);
        var sensor = new WorldDeviceStatus.Sensor(unit, properties.observationSource());
        if (properties.mappings() == null || properties.mappings().isEmpty()) {
            throw new IllegalArgumentException("设备状态工具需要配置 mappings");
        }
        List<DeviceStateTool.Mapping> mappings = new ArrayList<>();
        for (MappingProperties mapping : properties.mappings()) {
            mappings.add(new DeviceStateTool.Mapping(mapping.deviceId(), mapping.entityId()));
        }
        return new DeviceStateTool(ref, sensor, mappings, reader);
    }

    @Bean
    public ToolDirectory.Binding deviceStateBinding(DeviceStateTool tool) {
        return tool.binding();
    }

    @Bean(destroyMethod = "close")
    public HomeAssistantStateReader deviceStateHomeAssistantReader(HaProperties properties, Environment environment) {
        Duration timeout = properties.timeout();
        if (timeout == null) {
            timeout = DEFAULT_TIMEOUT;
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("设备状态工具的 timeout 必须为正");
        }
        // 连接配置单独读取，避免配置记录的字符串表示或绑定失败回显令牌与 URL。
        String baseUrl = connectionProperty(environment, "base-url");
        String accessToken = connectionProperty(environment, "access-token");
        try {
            return new HomeAssistantStateReader(URI.create(baseUrl), accessToken, timeout);
        } catch (IllegalArgumentException invalidConnection) {
            throw new IllegalArgumentException("设备状态工具的 base-url 或 access-token 无效");
        }
    }

    private static String connectionProperty(Environment environment, String name) {
        String value;
        try {
            value = environment.getProperty(PREFIX + "." + name);
        } catch (IllegalArgumentException unresolvedProperty) {
            throw new IllegalArgumentException("无法解析设备状态工具的 " + name);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少设备状态工具的 " + name);
        }
        return value;
    }

    @ConfigurationProperties(prefix = PREFIX)
    public record HaProperties(
            /** 当前实际回调的调用名。 */
            String toolName,
            /** 接入实例的稳定编号。 */
            String sourceInstance,
            /** 当前实际回调对应的实现版本。 */
            String implementationVersion,
            /** 宿主认可的观测来源标识。 */
            String observationSource,
            /** 世界模型中登记的感知工具实体。 */
            String sensorUnitId,
            /** 感知工具实体的固定修订。 */
            Long sensorUnitRevision,
            /** 单次 Home Assistant 读请求的最大时长。 */
            Duration timeout,
            /** 宿主允许读取的世界设备与 Home Assistant 原生实体映射。 */
            List<MappingProperties> mappings) {
    }

    public record MappingProperties(
            /** 世界设备的稳定编号。 */
            String deviceId,
            /** 已登记的 Home Assistant 原生实体编号。 */
            String entityId) {
    }

}
