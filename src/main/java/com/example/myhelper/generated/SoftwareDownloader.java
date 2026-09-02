package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 软件包下载工具，负责网络传输与结果反馈，不负责静默信任未知来源的软件。
 */
@Component
@GeneratedTool
public class SoftwareDownloader {

    @Tool(description = "下载指定URL的软件到本地文件系统")
    public String downloadSoftware(
            @ToolParam(description = "软件的URL") String softwareUrl,
            @ToolParam(description = "保存路径，包括文件名") String localFilePath) {
        try {
            URI uri = new java.net.URI(softwareUrl);
            Path path = Path.of(localFilePath);
            Files.copy(uri.toURL().openStream(), path, StandardCopyOption.REPLACE_EXISTING);
            return "软件已成功下载到指定位置";
        } catch (Exception e) {
            if (e instanceof java.io.IOException || e instanceof java.net.URISyntaxException) {
                return "发生异常，可能是因为无效的URI或文件路径问题: " + e.getMessage();
            }
            return "下载软件时发生了意外错误: " + e.getMessage();
        }
    }
}
