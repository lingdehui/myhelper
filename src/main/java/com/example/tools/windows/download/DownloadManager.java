package com.example.tools.windows.download;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Windows 下载与 winget 安装的基础工具。网络传输、安装和搜索分别保持独立，便于调用方选择副作用范围。
 */
@Component
public class DownloadManager {

    private static final Logger log = LoggerFactory.getLogger(DownloadManager.class);

    /** 下载完整文件并记录可观测的进度；当前实现不保存断点状态。 */
    @Tool(description = "从指定 URL 下载完整文件到本地，并在日志中显示进度")
    public String downloadFile(String url, String savePath) {
        try {
            URL fileUrl = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) fileUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.connect();

            String fileName = new File(url).getName();
            File outputFile = new File(savePath, fileName);

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytes = 0;
                long contentLength = conn.getContentLength();

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;
                    if (contentLength > 0) {
                        // 可以在此处发送进度信息
                        int progress = (int) (totalBytes * 100 / contentLength);
                        log.info("下载进度: {}%", progress);
                    }
                }
            }
            return "✅ 文件下载成功: " + outputFile.getAbsolutePath();
        } catch (Exception e) {
            return "❌ 下载失败: " + e.getMessage();
        }
    }

    /** 通过 winget 静默安装指定软件包。 */
    @Tool(description = "使用 winget 静默安装指定软件包")
    public String installSoftware(String softwareId) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "winget", "install", "--silent",
                    "--accept-package-agreements", softwareId);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0 ? "✅ 安装成功" : "❌ 安装失败，退出码: " + exitCode;
        } catch (Exception e) {
            return "❌ 安装异常: " + e.getMessage();
        }
    }

    /** 搜索 winget 软件包；结果应由专用安装工具进一步解析。 */
    @Tool(description = "搜索 winget 软件包")
    public String searchSoftware(String keyword) {
        try {
            ProcessBuilder builder = new ProcessBuilder("winget", "search", keyword);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (InputStream stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            int exitCode = process.waitFor();
            return exitCode == 0
                    ? "搜索结果：\n" + output
                    : "搜索失败，退出码: " + exitCode + "\n" + output;
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }
}
