package com.example.myhelper.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 将打包在 classpath 中的 Sherpa-ONNX 原生库提取到当前工作目录。
 *
 * <p>必须在首次加载 JNI 前调用；使用固定文件名覆盖旧文件，确保 Java 接口和 DLL 版本保持一致。</p>
 */
public final class NativeLoader {
    private static final Logger log = LoggerFactory.getLogger(NativeLoader.class);

    private NativeLoader() {
        // 工具类不应被实例化。
    }

    /** 提取运行所需的全部 DLL；任一库缺失即快速失败，避免留下半可用状态。 */
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

    /** 从应用资源复制单个 DLL 到运行目录。 */
    private static void extract(Path dir, String libName) throws IOException {
        try (InputStream in = NativeLoader.class.getResourceAsStream("/native/" + libName)) {
            if (in == null) throw new IOException("Resource not found: " + libName);
            Files.copy(in, dir.resolve(libName), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
