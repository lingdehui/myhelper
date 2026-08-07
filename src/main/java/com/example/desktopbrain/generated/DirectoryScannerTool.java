package com.example.desktopbrain.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class DirectoryScannerTool {

    @Tool(description = "Scan a directory and return a list of files, optionally filtered by extension")
    public String scanDirectory(
            @ToolParam(description = "The directory path to scan") String directory,
            @ToolParam(description = "Optional file extension filter (e.g., txt, java). Leave blank or null to list all files") String extensionFilter) {
        try {
            Path startPath = Paths.get(directory);
            if (!Files.isDirectory(startPath)) {
                return "Error: The provided path is not a directory or does not exist.";
            }

            List<String> result = new ArrayList<>();
            String filter = (extensionFilter != null && !extensionFilter.trim().isEmpty()) ? extensionFilter.trim().toLowerCase() : null;

            Files.walkFileTree(startPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (filter == null) {
                        result.add(file.toString());
                    } else {
                        String fileName = file.getFileName().toString().toLowerCase();
                        if (fileName.endsWith("." + filter)) {
                            result.add(file.toString());
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    // skip files that cannot be read
                    return FileVisitResult.CONTINUE;
                }
            });

            if (result.isEmpty()) {
                return "No files found" + (filter != null ? " with extension ." + filter : "") + " in " + directory;
            }

            return "Found " + result.size() + " file(s):\n" + String.join("\n", result);
        } catch (Exception e) {
            return "Error during directory scan: " + e.getMessage();
        }
    }
}