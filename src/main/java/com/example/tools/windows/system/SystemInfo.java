package com.example.tools.windows.system;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;

/**
 * 读取 JVM 可见的运行环境指标，供诊断与系统状态回答使用；不修改系统设置。
 */
@Component
public class SystemInfo {

    /** 返回操作系统、处理器和 JVM 运行时的只读摘要。 */
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
