package com.example.tools.windows.filesystem;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 基础文件系统工具。所有操作只使用调用方明确传入的路径，不推测或扩大目标范围。
 */
@Component
public class FileOperations {

    /** 创建目录；返回值区分“已存在”和“创建失败”，便于上层给出正确提示。 */
    @Tool(description = "在指定路径创建文件夹")
    public String createFolder(String path) {
        try {
            File folder = new File(path);
            if (folder.mkdirs()) {
                return "✅ 文件夹创建成功: " + path;
            } else {
                return "⚠️ 文件夹已存在或创建失败: " + path;
            }
        } catch (Exception e) {
            return "❌ 创建失败: " + e.getMessage();
        }
    }

    /** 列出单层目录内容，不递归扫描，防止无意间产生大量输出。 */
    @Tool(description = "列出指定目录下的所有文件和文件夹")
    public String listDirectory(String path) {
        try {
            File dir = new File(path);
            if (!dir.exists() || !dir.isDirectory()) {
                return "❌ 路径不存在或不是目录: " + path;
            }
            StringBuilder sb = new StringBuilder("📁 " + path + " 的内容:\n");
            for (File file : dir.listFiles()) {
                sb.append("  ").append(file.isDirectory() ? "📂" : "📄")
                        .append(" ").append(file.getName())
                        .append(file.isDirectory() ? "/" : "")
                        .append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "❌ 列出目录失败: " + e.getMessage();
        }
    }

    /** 移动或重命名路径；同名目标会被替换，因此调用前必须确认覆盖意图。 */
    @Tool(description = "移动或重命名文件/文件夹")
    public String moveFile(String source, String target) {
        try {
            Path sourcePath = Paths.get(source);
            Path targetPath = Paths.get(target);
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            return "✅ 移动成功: " + source + " → " + target;
        } catch (Exception e) {
            return "❌ 移动失败: " + e.getMessage();
        }
    }
}
