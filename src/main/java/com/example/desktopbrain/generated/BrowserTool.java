package com.example.desktopbrain.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

@Component
public class BrowserTool {

    @Tool(description = "Open a URL in the default web browser using system-native methods")
    public String openUrl(@ToolParam(description = "The URL to open in the browser") String url) {
        try {
            String osName = System.getProperty("os.name").toLowerCase();
            String[] command;

            if (osName.contains("win")) {
                command = new String[]{"cmd", "/c", "start", url};
            } else if (osName.contains("mac")) {
                command = new String[]{"open", url};
            } else if (osName.contains("linux")) {
                command = new String[]{"xdg-open", url};
            } else {
                throw new UnsupportedOperationException("Unsupported operating system: " + osName);
            }

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.start();
            return "Opened URL in default browser: " + url;
        } catch (IOException e) {
            return "Failed to open URL: " + e.getMessage();
        }
    }
}