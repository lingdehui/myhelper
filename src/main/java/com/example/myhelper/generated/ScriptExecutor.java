package com.example.myhelper.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 脚本执行的兼容工具入口；调用方应在执行前完成脚本内容与目标环境的安全校验。
 */
@Component
public class ScriptExecutor {

    @Tool(description = "Execute a local script on the operating system.")
    public String executeScript(@ToolParam(description = "The command or script to be executed.") String command) {
        ProcessBuilder processBuilder = new ProcessBuilder();
        StringBuilder outputMessage = new StringBuilder();

        try {
            if (System.getProperty("os.name").toLowerCase().startsWith("windows")) {
                processBuilder.command("cmd", "/c", command);
            } else if (System.getProperty("os.name").contains("mac os x") ||
                    System.getProperty("os.name").contains("darwin")) {
                processBuilder.command("/bin/sh", "-c", "open " + command);
            } else {
                processBuilder.command("/bin/sh", "-c", "xdg-open " + command);
            }

            Process process = processBuilder.start();
            outputMessage.append(new String(process.getInputStream().readAllBytes()));
        } catch (Exception e) {
            return "An error occurred while executing the script: " + e.getMessage();
        }
        return outputMessage.toString();
    }
}
