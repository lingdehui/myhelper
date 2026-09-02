package com.example.myhelper.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 浏览器相关操作的兼容入口，保留为旧工具调用的适配层。
 */
@Component
public class BrowserTool {

    @Tool(description = "Open a URL in the default web browser using system-native methods")
    public String openUrl(@ToolParam(description = "The URL to open in the browser") String url) {
        try {
            String osName = System.getProperty("os.name").toLowerCase();
            String[] command;

            if (osName.contains("win")) {
                command = new String[]{"cmd", "/c", "start", url};
            } else if (osName.contains("mac")) {
                command = new String[]{"open", url};
            } else if (osName.contains("linux")) {
                command = new String[]{"xdg-open", url};
            } else {
                throw new UnsupportedOperationException("Unsupported operating system: " + osName);
            }

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.start();
            Thread.sleep(2000); // 等待浏览器加载，便于后续核对窗口标题
            return "已打开网页: " + url + "（请立即用 getActiveWindowTitle 或截图 OCR 核对当前页面是否为正确网站，若不符请用正确 URL 重新打开，不要在错误页面上继续操作）";
        } catch (Exception e) {
            return "打开网页失败: " + e.getMessage();
        }
    }
}
