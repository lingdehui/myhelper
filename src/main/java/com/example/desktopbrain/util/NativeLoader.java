package com.example.desktopbrain.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class NativeLoader {
    private static final Logger log = LoggerFactory.getLogger(NativeLoader.class);

    public static void extractToCurrentDir() {
        Path currentDir = Path.of(System.getProperty("user.dir"));
        try {
            extract(currentDir, "onnxruntime.dll");
            extract(currentDir, "onnxruntime_providers_shared.dll");
            extract(currentDir, "sherpa-onnx-cxx-api.dll");
            extract(currentDir, "sherpa-onnx-jni.dll");
            log.info("✅ DLLs extracted to: {}", currentDir.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to extract native libraries", e);
        }
    }

    private static void extract(Path dir, String libName) throws IOException {
        try (InputStream in = NativeLoader.class.getResourceAsStream("/native/" + libName)) {
            if (in == null) throw new IOException("Resource not found: " + libName);
            Files.copy(in, dir.resolve(libName), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}