package com.example.desktopbrain.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

@Component
public class DirectoryScanTool {

    @Tool(description = "Scan the specified directory and return a list of files and subdirectories (one level deep)")
    public String scanDirectory(@ToolParam(description = "The absolute or relative path of the directory to scan") String directoryPath) {
        Path dir = Paths.get(directoryPath);
        if (!Files.isDirectory(dir)) {
            return "Error: The provided path is not a directory or does not exist: " + directoryPath;
        }
        try {
            return Files.list(dir)
                    .map(Path::toString)
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {
            return "Error scanning directory " + directoryPath + ": " + e.getMessage();
        }
    }
}