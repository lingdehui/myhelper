package com.example.desktopbrain.generated;

import com.example.desktopbrain.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Component
@GeneratedTool
public class FileReadTool {

    @Tool(description = "读取指定路径的文件内容，返回文件全部文本")
    public String readFileContent(
            @ToolParam(description = "文件的绝对或相对路径") String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (Files.notExists(path)) {
                return "错误：文件不存在 - " + filePath;
            }
            if (!Files.isReadable(path)) {
                return "错误：文件不可读 - " + filePath;
            }
            return Files.readString(path);
        } catch (Exception e) {
            return "读取文件失败：路径 " + filePath + "，原因：" + e.getMessage();
        }
    }
}