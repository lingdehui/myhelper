package com.example.desktopbrain.exploration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 学习工作区服务：为 download_and_learn 等学习方法提供临时目录的创建/清理工具。
 *
 * <p>AI 可调用 {@code createLearningWorkspace} 获得一个隔离的临时目录，
 * 在里面下载、安装、试用软件。学完后调用 {@code cleanupLearningWorkspace} 删除。
 * JVM 关闭时自动兜底清理所有未清的工作区。</p>
 */
@Service
public class LearningWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(LearningWorkspaceService.class);

    /** 所有活跃的工作区路径，用于 JVM 关闭兜底清理 */
    private final Map<String, Path> activeWorkspaces = new ConcurrentHashMap<>();

    public LearningWorkspaceService() {
        // JVM 关闭兜底：删除所有未清理的工作区
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!activeWorkspaces.isEmpty()) {
                log.info("🧹 兜底清理 {} 个学习工作区...", activeWorkspaces.size());
                for (Map.Entry<String, Path> entry : activeWorkspaces.entrySet()) {
                    deleteRecursively(entry.getValue());
                    log.info("  🗑️ 已清理: {}", entry.getValue());
                }
                activeWorkspaces.clear();
            }
        }, "learning-workspace-cleanup"));
    }

    @Tool(description = "为学习会话创建临时工作目录。下载软件、安装、试用都在这个目录里进行。返回目录的绝对路径。")
    public String createLearningWorkspace(
            @ToolParam(description = "工作区名称（可选），如 'docker-learn'、'python-test'") String name) {
        try {
            String dirName = (name != null && !name.isBlank())
                    ? "learn-" + name.replaceAll("[^a-zA-Z0-9\\-_]", "_")
                    : "learn-workspace";
            Path workspace = Files.createTempDirectory(dirName);
            String path = workspace.toAbsolutePath().toString();
            activeWorkspaces.put(path, workspace);
            log.info("📁 学习工作区已创建: {}", path);
            return path;
        } catch (IOException e) {
            log.error("❌ 创建工作区失败: {}", e.getMessage());
            return "创建失败: " + e.getMessage();
        }
    }

    @Tool(description = "清理学习工作区，删除整个临时目录及其所有内容。学习完成后必须调用此方法清理。")
    public String cleanupLearningWorkspace(
            @ToolParam(description = "createLearningWorkspace 返回的目录路径") String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) {
            return "请提供工作区路径";
        }
        Path path = Path.of(workspacePath);
        if (!Files.exists(path)) {
            activeWorkspaces.remove(workspacePath);
            return "工作区不存在或已清理: " + workspacePath;
        }
        try {
            deleteRecursively(path);
            activeWorkspaces.remove(workspacePath);
            log.info("🧹 学习工作区已清理: {}", workspacePath);
            return "已清理: " + workspacePath;
        } catch (Exception e) {
            log.error("❌ 清理工作区失败: {} - {}", workspacePath, e.getMessage());
            return "清理失败: " + e.getMessage();
        }
    }

    @Tool(description = "列出所有活跃的学习工作区路径。")
    public String listLearningWorkspaces() {
        if (activeWorkspaces.isEmpty()) {
            return "当前无活跃工作区";
        }
        StringBuilder sb = new StringBuilder("活跃工作区 (" + activeWorkspaces.size() + "):\n");
        for (String p : activeWorkspaces.keySet()) {
            sb.append("- ").append(p).append("\n");
        }
        return sb.toString();
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            log.warn("⚠️ 递归删除失败: {} - {}", dir, e.getMessage());
        }
    }
}
