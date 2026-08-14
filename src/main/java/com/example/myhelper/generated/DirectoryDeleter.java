package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
@GeneratedTool
public class DirectoryDeleter {

    @Tool(description = "删除指定的目录及其内容")
    public String deleteDirectory(@ToolParam(description = "需要被删除的目录路径") Path directoryPath) {
        String resultMessage;
        try {
            Files.walk(directoryPath)
                .sorted((p1, p2) -> -p1.compareTo(p2))
                .map(Path::toFile)
                .forEach(file -> {
                    if (!file.delete()) {
                        throw new RuntimeException("Cannot delete file: " + file);
                    }
                });
            resultMessage = "目录及其内容已成功删除.";
        } catch (IOException e) {
            resultMessage = "发生错误，未能删除指定的目录: " + e.getMessage();
        }
        return resultMessage;
    }

}