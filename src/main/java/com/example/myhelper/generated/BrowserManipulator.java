package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@GeneratedTool
public class BrowserManipulator {

    @Tool(description = "打开给定的URL并在默认浏览器中显示。")
    public String browseWebsite(@ToolParam(description = "要浏览的网页Url。") String url) {
        try {
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                new ProcessBuilder("cmd", "/c", "start", url).inheritIO().start();
            } else if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                new ProcessBuilder("/usr/bin/open", url).inheritIO().start();
            } else { // 默认 linux 或其他支持 xdg-open 系统
                new ProcessBuilder("xdg-open", url).inheritIO().start();
            }
            return "成功打开浏览器并浏览网页：" + url;
        } catch (Exception e) {
            return "打开网页时遇到问题： " + e.getMessage();
        }
    }

    @Tool(description = "模拟鼠标点击，向给定的URL发送GET请求。此方法不会在浏览器中显示结果。")
    public String requestWebsite(@ToolParam(description = "要访问的网页Url。") String url) {
        try {
            ProcessBuilder processBuilder;
            if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                processBuilder = new ProcessBuilder("cmd", "/c", "curl.exe", "-o", "NUL", url); // Windows 系统可能使用 curl 工具
            } else if (System.getProperty("os.name").toLowerCase().contains("mac") || System.getProperty("os.name").toLowerCase().contains("linux")) {
                processBuilder = new ProcessBuilder("bash", "-c", String.format("curl -o /dev/null %s", url));
            } else {
                return "未知操作系统；请求可能未执行。";
            }
            Process process = processBuilder.start();
            if (process.waitFor() == 0) {
                return "成功向 URL 发送 GET 请求：" + url;
            } else {
                return "发送请求时遇到问题：非零返回状态码";
            }
        } catch (Exception e) {
            return "模拟点击并获取网页内容时出现问题：" + e.getMessage();
        }
    }

}