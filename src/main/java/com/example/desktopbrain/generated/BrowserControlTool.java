package com.example.desktopbrain.generated;

import com.example.desktopbrain.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@GeneratedTool
public class BrowserControlTool {

    @Tool(description = "打开指定的网址")
    public String openUrl(@ToolParam(description = "要打开的网址") String url) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder();
            if (System.getProperty("os.name").contains("Windows")) {
                // Windows 系统
                processBuilder.command("cmd.exe", "/c", "start", url);
            } else if (System.getProperty("os.name").contains("Mac OS")) {
                // macOS 系统
                processBuilder.command("open", url);
            } else {
                // Linux 系统或其他可使用 xdg-open 的系统
                processBuilder.command("xdg-open", url);
            }
            processBuilder.start();
            return "已打开网址: " + url;
        } catch (Exception e) {
            e.printStackTrace();
            return "打开网址失败: " + e.getMessage();
        }
    }

}