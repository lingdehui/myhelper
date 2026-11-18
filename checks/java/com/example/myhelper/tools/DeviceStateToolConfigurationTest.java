package com.example.myhelper.tools;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceStateToolConfigurationTest {
    /** 本地检查中宿主提供给模型的固定工具身份。 */
    private static final ToolDirectory.ToolRef REF = new ToolDirectory.ToolRef("read_device_power", "ha-local", "2");

    @Test
    void noSwitchLeavesDeviceStateToolAndItsDirectoryUnconfigured() {
        try (var context = application(Map.of())) {
            context.refresh();

            assertTrue(context.getBeansOfType(DeviceStateTool.class).isEmpty());
            assertTrue(context.getBeansOfType(HomeAssistantStateReader.class).isEmpty());
            assertTrue(context.getBeansOfType(ToolDirectory.Binding.class).isEmpty());
            assertTrue(context.getBeansOfType(ToolDirectory.class).isEmpty());
        }
    }

    @Test
    void enabledToolUsesTheConfiguredDirectoryBinding() {
        try (var context = application(enabledProperties(REF.implementationVersion()))) {
            context.refresh();

            var binding = context.getBean(ToolDirectory.Binding.class);
            var registration = context.getBean(ToolDirectory.class).find(REF.toolName()).orElseThrow();
            assertEquals(REF, binding.ref());
            assertEquals(REF, registration.ref());
            assertEquals(REF.toolName(), binding.callback().getToolDefinition().name());
        }
    }

    @Test
    void enabledToolRejectsAConflictingImplementationVersion() {
        try (var context = application(enabledProperties("1"))) {
            BeanCreationException failure = assertThrows(BeanCreationException.class, new RefreshContext(context));

            assertEquals("设备状态工具与目录登记的固定实现不一致", failure.getMostSpecificCause().getMessage());
        }
    }

    private static AnnotationConfigApplicationContext application(Map<String, Object> values) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("deviceStateTest", values));
        context.register(DeviceStateToolConfiguration.class);
        return context;
    }

    private static Map<String, Object> enabledProperties(String registeredVersion) {
        String directory = "myhelper.tools.registrations[0].";
        String tool = "myhelper.tools.device-state.ha.";
        return Map.ofEntries(
                Map.entry(directory + "tool-name", REF.toolName()),
                Map.entry(directory + "source-instance", REF.sourceInstance()),
                Map.entry(directory + "implementation-version", registeredVersion),
                Map.entry(directory + "source", "local-check"),
                Map.entry(directory + "model-generated", false),
                Map.entry(directory + "consolidated", false),
                Map.entry(directory + "project-developed", true),
                Map.entry(tool + "enabled", true),
                Map.entry(tool + "tool-name", REF.toolName()),
                Map.entry(tool + "source-instance", REF.sourceInstance()),
                Map.entry(tool + "implementation-version", REF.implementationVersion()),
                Map.entry(tool + "observation-source", "ha-local-check"),
                Map.entry(tool + "sensor-unit-id", "sensor:hvac-power"),
                Map.entry(tool + "sensor-unit-revision", 2L),
                Map.entry(tool + "base-url", "http://127.0.0.1:8123"),
                Map.entry(tool + "access-token", "local-check-token"),
                Map.entry(tool + "mappings[0].device-id", "device:living-room-hvac"),
                Map.entry(tool + "mappings[0].entity-id", "climate.living_room"));
    }

    private record RefreshContext(
            /** 已装入测试配置，尚未刷新。 */
            AnnotationConfigApplicationContext context) implements Executable {
        @Override
        public void execute() {
            context.refresh();
        }
    }
}
