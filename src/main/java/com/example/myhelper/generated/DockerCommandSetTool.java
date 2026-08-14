package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@GeneratedTool
public class DockerCommandSetTool {

    @Tool(description = "获取Docker容器列表")
    public String listContainers() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("docker", "ps");
            return processBuilder.start().getInputStream().readAllBytes()
                    .toString();
        } catch (Exception e) {
            return "Failed to retrieve Docker containers: " + e.getMessage();
        }
    }

    @Tool(description = "启动指定的Docker容器")
    public String startContainer(@ToolParam(description = "容器ID或名称") String containerIdOrName) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("docker", "start", containerIdOrName);
            return processBuilder.start().getInputStream().readAllBytes()
                    .toString();
        } catch (Exception e) {
            return "Failed to start Docker container: " + e.getMessage();
        }
    }

    @Tool(description = "停止指定的Docker容器")
    public String stopContainer(@ToolParam(description = "容器ID或名称") String containerIdOrName) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("docker", "stop", containerIdOrName);
            return processBuilder.start().getInputStream().readAllBytes()
                    .toString();
        } catch (Exception e) {
            return "Failed to stop Docker container: " + e.getMessage();
        }
    }

    @Tool(description = "重启指定的Docker容器")
    public String restartContainer(@ToolParam(description = "容器ID或名称") String containerIdOrName) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder("docker", "restart", containerIdOrName);
            return processBuilder.start().getInputStream().readAllBytes()
                    .toString();
        } catch (Exception e) {
            return "Failed to restart Docker container: " + e.getMessage();
        }
    }

}