package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * 基于桌面自动化的浏览器操作工具。适用于没有专用浏览器连接器时的受控兜底操作。
 */
@Component
@GeneratedTool
public class BrowserManipulator {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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
            Thread.sleep(2000); // 等待浏览器加载，便于后续核对窗口标题
            return "已打开网页：" + url + "（请立即用 getActiveWindowTitle 或截图 OCR 核对当前页面是否为正确网站，若不符请用正确 URL 重新打开，不要在错误页面上继续操作）";
        } catch (Exception e) {
            return "打开网页时遇到问题： " + e.getMessage();
        }
    }

    @Tool(description = "向给定 URL 发送实际 HTTP GET 请求并返回状态码；此方法不会在浏览器中显示页面。")
    public String requestWebsite(@ToolParam(description = "要访问的网页Url。") String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) return "只支持 http 或 https URL。";
            HttpResponse<Void> response = httpClient.send(HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(20)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return "HTTP GET 已完成：" + uri + "，状态码 " + response.statusCode();
        } catch (Exception e) {
            return "HTTP GET 请求失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

}
