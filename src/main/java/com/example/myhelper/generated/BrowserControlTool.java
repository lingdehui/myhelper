package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

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
            Thread.sleep(2000); // 等待浏览器加载，便于后续核对窗口标题
            return "已打开网页: " + url + "（请立即用 getActiveWindowTitle 或截图 OCR 核对当前页面是否为正确网站，若不符请用正确 URL 重新打开，不要在错误页面上继续操作）";
        } catch (Exception e) {
            e.printStackTrace();
            return "打开网址失败: " + e.getMessage();
        }
    }

}