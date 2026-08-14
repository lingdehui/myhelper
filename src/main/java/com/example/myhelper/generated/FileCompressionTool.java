package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

@Component
@GeneratedTool
public class FileCompressionTool {

    @Tool(description = "Compresses the given file or directory into a ZIP archive.")
    public String compressFile(@ToolParam(description = "The path to the file or directory to be compressed.") String sourcePath,
                               @ToolParam(description = "The destination path for the zip file") String destZipFile) {
        File dir = new File(sourcePath);
        if (!dir.exists()) return "Source file does not exist.";
        
        try {
            ProcessBuilder pb;
            String osName = System.getProperty("os.name");
            
            if (osName.startsWith("Windows")) {
                pb = new ProcessBuilder("cmd", "/c", "powershell", "-Command", "[System.IO.Compression.ZipFile]::CreateFromDirectory('" + sourcePath.replace("\\", "\\\\") + "', '" + destZipFile.replace("\\", "\\\\") + "')");
            } else if (osName.startsWith("Mac")) {
                pb = new ProcessBuilder("zip", "-r", destZipFile, sourcePath);
            } else { // Assume Linux or Unix-like systems
                pb = new ProcessBuilder("zip", "-r", destZipFile, sourcePath);
            }
            
            Process process = pb.start();
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                return "Compression successful.";
            } else {
                return "Failed to compress file: Unknown error occurred.";
            }

        } catch (InterruptedException | IOException ex) {
            Thread.currentThread().interrupt();
            return "Failed to compress file: " + ex.getMessage();
        }
    }
}