package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@GeneratedTool
public class WebBrowserController {

    @Tool(description = "Open a URL in the default web browser.")
    public String openUrlInDefaultBrowser(@ToolParam(description = "The URL to be opened") String url) {
        try {
            if (System.getProperty("os.name").startsWith("Windows")) {
                ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "start", url);
                pb.inheritIO();
                pb.start();
                return "Successfully opened the URL in default browser.";
            }
            // For MacOS and Linux, you can add handling here according to the requirements.
        } catch (Exception e) {
            return "Failed to open URL: " + e.getMessage();
        }
        return "Unsupported operating system operation not supported.";
    }

}