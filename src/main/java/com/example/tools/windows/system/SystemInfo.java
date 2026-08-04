package com.example.tools.windows.system;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;

@Component
public class SystemInfo {

    @Tool(description = "获取系统信息，包括操作系统、CPU、内存等")
    public String getSystemInfo() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();

        return String.format(
                "💻 系统信息:\n" +
                        "  OS: %s %s\n" +
                        "  CPU 核心数: %d\n" +
                        "  JVM: %s %s\n" +
                        "  运行时间: %d 毫秒",
                osBean.getName(),
                osBean.getVersion(),
                osBean.getAvailableProcessors(),
                runtimeBean.getVmName(),
                runtimeBean.getVmVendor(),
                runtimeBean.getUptime()
        );
    }
}