package com.example.desktopbrain.generated;

import com.example.desktopbrain.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

@Component
@GeneratedTool
public class NetworkScraperTool {

    @Tool(description = "根据给定的URL抓取网页内容")
    public String scrapeWebPage(@ToolParam(description = "要刮取的目标网址") String url) {
        try {
            URL targetUrl = new URL(url);
            HttpURLConnection connection = (HttpURLConnection) targetUrl.openConnection();
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder content = new StringBuilder();

            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine).append("\n");
            }
            in.close();

            return content.toString();
        } catch (Exception e) {
            return "抓取网页失败: " + e.getMessage();
        }
    }
}