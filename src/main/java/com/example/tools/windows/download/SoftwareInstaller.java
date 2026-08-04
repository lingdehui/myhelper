package com.example.tools.windows.download;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@Component
public class SoftwareInstaller {

    @Tool(description = "检查 winget 包管理器是否可用")
    public String checkWingetAvailable() {
        try {
            Process process = new ProcessBuilder("winget", "--version").start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return "✅ winget 可用";
            } else {
                return "❌ winget 不可用，请检查是否安装。";
            }
        } catch (Exception e) {
            return "❌ 检查 winget 时出错: " + e.getMessage();
        }
    }

    @Tool(description = "搜索 winget 软件包，返回包名、版本和描述。关键词可以是模糊描述，如 'code editor'")
    public String searchSoftware(String keyword) {
        try {
            List<String> command = new ArrayList<>();
            command.add("winget");
            command.add("search");
            command.add(keyword);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return "搜索结果：\n" + output.toString();
            } else {
                return "搜索失败，退出码: " + exitCode + "\n" + output.toString();
            }
        } catch (Exception e) {
            return "❌ 搜索时出错: " + e.getMessage();
        }
    }

    @Tool(description = "安装指定软件，需要精确的 winget ID（如 'Microsoft.VisualStudioCode'）")
    public String installSoftware(String softwareId) {
        // 先检查 winget 是否可用
        String check = checkWingetAvailable();
        if (!check.contains("✅")) {
            return check;
        }

        try {
            List<String> command = new ArrayList<>();
            command.add("winget");
            command.add("install");
            command.add("--silent");
            command.add("--accept-package-agreements");
            command.add("--accept-source-agreements");
            command.add(softwareId);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return "✅ 软件 " + softwareId + " 安装成功！\n" + output.toString();
            } else {
                return "❌ 安装失败，退出码: " + exitCode + "\n" + output.toString();
            }
        } catch (Exception e) {
            return "❌ 安装时出错: " + e.getMessage();
        }
    }

    @Tool(description = "卸载指定软件，需要精确的 winget ID")
    public String uninstallSoftware(String softwareId) {
        String check = checkWingetAvailable();
        if (!check.contains("✅")) {
            return check;
        }

        try {
            List<String> command = new ArrayList<>();
            command.add("winget");
            command.add("uninstall");
            command.add("--silent");
            command.add(softwareId);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                return "✅ 软件 " + softwareId + " 卸载成功！\n" + output.toString();
            } else {
                return "❌ 卸载失败，退出码: " + exitCode + "\n" + output.toString();
            }
        } catch (Exception e) {
            return "❌ 卸载时出错: " + e.getMessage();
        }
    }
}