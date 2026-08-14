package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@GeneratedTool
public class BrowserWebpageOperationTool {

    @Tool(description = "使用浏览器打开指定的网页")
    public String openWebPageWithBrowser(
            @ToolParam(description = "要访问的网页 URL")
                    String url) {
        try {
            ProcessBuilder pb;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "start", url);
            } else if (System.getProperty("os.name").toLowerCase().contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            pb.start();
            return "已成功打开网页: " + url;
        } catch (Exception e) {
            return "打开网页失败: " + e.getMessage();
        }
    }
}
