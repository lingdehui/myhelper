package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 临时目录生命周期工具，用于创建、列举和清理任务级临时工作区。
 */
@Component
@GeneratedTool
public class TempDirectoryManager {

    @Tool(description = "创建指定名称的临时目录")
    public String createTempDir(@ToolParam(description = "临时目录的名称") String dirName) {
        try {
            Path tempDir = Files.createTempDirectory(dirName);
            return "Temporary directory created: " + tempDir.toAbsolutePath().toString();
        } catch (IOException e) {
            return "Failed to create temporary directory. Error: " + e.getMessage();
        }
    }

    @Tool(description = "清理指定名称的临时目录")
    public String cleanTempDir(@ToolParam(description = "要删除的临时目录路径") String pathStr) {
        try {
            Path pathToDelete = Path.of(pathStr);
            if (Files.exists(pathToDelete)) {
                Files.walk(pathToDelete)
                     .sorted((p1, p2) -> -p1.compareTo(p2))
                     .map(Path::toFile)
                     .forEach(File::delete);
                return "Temporary directory cleaned: " + pathToDelete.toAbsolutePath().toString();
            } else {
                return "Specified temporary directory does not exist.";
            }
        } catch (IOException e) {
            return "Failed to clean temporary directory. Error: " + e.getMessage();
        }
    }
}
