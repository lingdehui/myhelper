package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Docker 运行环境检查与操作工具，用于诊断或管理本机容器环境。
 */
@Component
@GeneratedTool
public class DockerEnvironmentOperations {

    @Tool(description = "创建Docker容器")
    public String createDockerContainer(@ToolParam(description = "镜像名称") String imageName,
                                        @ToolParam(description = "容器名称") String containerName) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("docker", "create", "--name", containerName, imageName);
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            if (exitCode == 0)
                return "Docker container created successfully.";
            else
                return "Failed to create Docker container.";
        } catch (Exception e) {
            return "Error occurred while creating the container: " + e.getMessage();
        }
    }

    @Tool(description = "移除Docker容器")
    public String removeDockerContainer(@ToolParam(description = "容器名称") String containerName) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("docker", "rm", "-f", containerName);
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            if (exitCode == 0)
                return "Docker container removed successfully.";
            else
                return "Failed to remove Docker container.";
        } catch (Exception e) {
            return "Error occurred while removing the container: " + e.getMessage();
        }
    }

}
