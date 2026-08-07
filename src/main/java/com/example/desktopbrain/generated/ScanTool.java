package com.example.desktopbrain.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

@Component
public class ScanTool {

    @Tool(description = "扫描指定目录下的文件和子目录，返回每行一个路径的字符串")
    public String scanDirectory(@ToolParam(description = "要扫描的目录路径") String directoryPath) {
        try {
            Path dir = Paths.get(directoryPath);
            if (!Files.exists(dir)) {
                return "路径不存在: " + directoryPath;
            }
            if (!Files.isDirectory(dir)) {
                return "路径不是目录: " + directoryPath;
            }
            return Files.list(dir)
                    .map(Path::toString)
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "扫描目录时发生错误: " + e.getMessage();
        }
    }
}