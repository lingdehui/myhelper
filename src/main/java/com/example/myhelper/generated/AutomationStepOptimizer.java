package com.example.myhelper.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AutomationStepOptimizer {

    @Tool(description = "Open URL in default browser")
    public String openUrlInBrowser(@ToolParam(description = "URL to be opened") final String url) {
        try {
            if (System.getProperty("os.name").startsWith("Windows")) {
                new ProcessBuilder("cmd", "/c", "start", url).inheritIO().start();
            } else {
                throw new UnsupportedOperationException("Opening browser is currently supported only on Windows.");
            }
            return "Successfully opened URL in the default browser.";
        } catch (Exception e) {
            return "Failed to open URL. Reason: " + e.getMessage();
        }
    }

    @Tool(description = "Run a system command")
    public String runSystemCommand(@ToolParam(description = "Command to be executed") final String command) {
        try {
            if (System.getProperty("os.name").startsWith("Windows")) {
                new ProcessBuilder("cmd", "/c", command).inheritIO().start();
            } else {
                throw new UnsupportedOperationException("Running commands is currently supported only on Windows.");
            }
            return "Command executed successfully.";
        } catch (Exception e) {
            return "Failed to execute the command. Reason: " + e.getMessage();
        }
    }
}