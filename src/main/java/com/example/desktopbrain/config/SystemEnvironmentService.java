package com.example.desktopbrain.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 启动时检测当前系统环境（OS、架构等），供 AI 规划/探索时注入上下文。
 * 避免 AI 在 Windows 上执行 macOS/Linux 命令。
 */
@Service
public class SystemEnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(SystemEnvironmentService.class);

    private String osInfo;
    private String envKey;

    @PostConstruct
    public void init() {
        String osName = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch");
        String home = System.getProperty("user.home");
        String tmp = System.getProperty("java.io.tmpdir");

        if (osName.contains("win")) {
            osInfo = String.format(
                "运行环境: Windows (%s, %s)%n" +
                "- 命令行: cmd /c 或 powershell -Command%n" +
                "- 浏览器打开: cmd /c start <url>%n" +
                "- 文件路径分隔符: \\%n" +
                "- 用户目录: %s%n" +
                "- 临时目录: %s%n" +
                "- 注意: 不要使用 sh、bash、xdg-open、open 等 Linux/macOS 命令",
                osName, arch, home, tmp);
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            osInfo = String.format(
                "运行环境: macOS (%s, %s)%n" +
                "- 命令行: sh -c 或 zsh%n" +
                "- 浏览器打开: open <url>%n" +
                "- 文件路径分隔符: /%n" +
                "- 用户目录: %s%n" +
                "- 临时目录: %s",
                osName, arch, home, tmp);
        } else {
            osInfo = String.format(
                "运行环境: Linux (%s, %s)%n" +
                "- 命令行: sh -c 或 bash%n" +
                "- 浏览器打开: xdg-open <url>%n" +
                "- 文件路径分隔符: /%n" +
                "- 用户目录: %s%n" +
                "- 临时目录: %s",
                osName, arch, home, tmp);
        }
        // 环境标识符，用于数据库集合名后缀（如 episodes-windows-amd64）
        String shortOs = osName.contains("win") ? "windows" : osName.contains("mac") ? "macos" : "linux";
        this.envKey = shortOs + "-" + arch;
        log.info("\u001b[36m💻 系统环境检测: {} | envKey={}\u001b[0m", osInfo.replace("\n", " | "), envKey);
    }

    /** 获取当前 OS 环境描述（已注入到 AI 上下文） */
    public String getOsInfo() {
        return osInfo;
    }

    /** 获取环境标识符（如 windows-amd64），用于数据库集合隔离 */
    public String getEnvironmentKey() {
        return envKey;
    }

    /** 为集合名追加环境后缀（如 episodes → episodes-windows-amd64） */
    public String collectionName(String baseName) {
        return baseName + "-" + envKey;
    }
}
