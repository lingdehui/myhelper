package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 面向网页元素的浏览器操作工具；调用方应提供明确页面与目标信息，避免隐式猜测。
 */
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
            Thread.sleep(2000); // 等待浏览器加载，便于后续核对窗口标题
            return "已打开网页: " + url + "（请立即用 getActiveWindowTitle 或截图 OCR 核对当前页面是否为正确网站，若不符请用正确 URL 重新打开，不要在错误页面上继续操作）";
        } catch (Exception e) {
            return "打开网页失败: " + e.getMessage();
        }
    }
}
