package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 系统命令执行工具。命令具备外部副作用，因此上层必须提供明确且已确认的命令内容。
 */
@Component
@GeneratedTool
public class SystemCommandExecutor {

    @Tool(description = "Execute a system command and return the output.")
    public String executeSystemCommand(@ToolParam(description = "The command to be executed.") final String command) {
        try {
            Process process = new ProcessBuilder(command.split(" "))
                    .redirectErrorStream(true)
                    .start();
            
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            
            return output.toString();

        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
