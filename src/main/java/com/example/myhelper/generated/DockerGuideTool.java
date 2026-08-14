package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Collectors;

@Component
@GeneratedTool
public class DockerGuideTool {

    @Tool(description = "生成Docker安装与使用教程")
    public String generateDockerGuide(@ToolParam(description = "操作系统类型") String osType) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder();
            if ("windows".equalsIgnoreCase(osType)) {
                processBuilder.command("powershell", "-Command", "docker --version");
            } else if ("linux".equalsIgnoreCase(osType)) {
                processBuilder.command("bash", "-c", "docker --version");
            } else if ("macos".equalsIgnoreCase(osType)) {
                processBuilder.command("zsh", "-c", "docker --version");
            }
            Process process = processBuilder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output = reader.lines().collect(Collectors.joining("\n"));
            return "Docker 版本信息:\n" + output;
        } catch (IOException e) {
            return "生成教程时发生错误: " + e.getMessage();
        }
    }

}