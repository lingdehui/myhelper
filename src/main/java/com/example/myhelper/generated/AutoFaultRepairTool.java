package com.example.myhelper.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AutoFaultRepairTool {

    @Tool(description="修复网络连接")
    public String fixNetworkConnection(@ToolParam(description="设备名称") String deviceName) {
        try {
            Runtime runtime = Runtime.getRuntime();
            Process process;
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                process = new ProcessBuilder("cmd", "/c", "ipconfig /renew").start(); // Windows 命令
            } else {
                throw new IllegalStateException("当前操作系统未支持该操作");
            }
            return "已尝试修复设备 '" + deviceName + "' 的网络连接。";
        } catch (Exception e) {
            return "无法修复设备的网络连接：" + e.getMessage();
        }
    }

    @Tool(description="重启系统服务")
    public String restartSystemService(@ToolParam(description="服务名") String serviceName, 
                                       @ToolParam(description="是否需要确认重启") boolean confirmRestart) {
        try {
            if (confirmRestart && !(serviceName.equals("importantService"))) {
                throw new Exception("用户拒绝重启");
            }
            Runtime runtime = Runtime.getRuntime();
            Process process;
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                process = new ProcessBuilder("cmd", "/c", "net stop " + serviceName + " && net start " + serviceName).start(); // Windows 命令
            } else {
                throw new IllegalStateException("当前操作系统未支持该操作");
            }
            return "服务 '" + serviceName + "' 已重启。";
        } catch (Exception e) {
            return "无法重启指定的服务：" + e.getMessage();
        }
    }

    @Tool(description="打开网页")
    public String openWebPage(@ToolParam(description="URL地址") String url) {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                new ProcessBuilder("cmd", "/c", "start", url).inheritIO().start(); // Windows 命令
            }
            Thread.sleep(2000); // 等待浏览器加载，便于后续核对窗口标题
            return "已打开网页：" + url + "（请立即用 getActiveWindowTitle 或截图 OCR 核对当前页面是否为正确网站，若不符请用正确 URL 重新打开，不要在错误页面上继续操作）";
        } catch (Exception e) {
            return "无法打开指定的网址：" + e.getMessage();
        }
    }
}