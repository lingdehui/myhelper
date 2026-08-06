package com.example.desktopbrain.autogen;

import org.springframework.stereotype.Service;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具编译器：用 JDK 自带的 javax.tools.JavaCompiler 编译 AI 生成的源码。
 *
 * <p>两阶段编译策略：</p>
 * <ol>
 *   <li><b>验证编译</b>：先编译到临时目录，验证语法/API 正确性</li>
 *   <li><b>持久化</b>：验证通过后才写入 src/main/java/com/example/desktopbrain/generated/，
 *       重启后 Spring 自动扫描编译</li>
 * </ol>
 *
 * <h3>classpath 构造</h3>
 * <p>编译需要 Spring AI 注解（@Tool/@ToolParam）和 Spring 注解（@Component）在 classpath 上。
 * 用当前 ClassLoader 的 classpath（java.class.path）+ 依赖 JAR 路径。</p>
 */
@Service
public class ToolCompiler {

    /** 生成工具的源码目录（在 ComponentScan 范围内，重启后自动扫描） */
    public static final String GENERATED_SOURCE_DIR = "src/main/java/com/example/desktopbrain/generated";

    /** 生成工具的包名 */
    public static final String GENERATED_PACKAGE = "com.example.desktopbrain.generated";

    /**
     * 编译结果。
     *
     * @param success 是否编译成功
     * @param className 类名
     * @param sourceFile 持久化的源码文件路径（成功时非 null）
     * @param errorMessage 编译错误信息（失败时非 null）
     */
    public record CompileResult(boolean success, String className,
                                 Path sourceFile, String errorMessage) {
        public static CompileResult ok(String className, Path sourceFile) {
            return new CompileResult(true, className, sourceFile, null);
        }
        public static CompileResult fail(String className, String error) {
            return new CompileResult(false, className, null, error);
        }
    }

    /**
     * 编译并持久化生成的工具源码。
     *
     * <p>流程：先在临时目录编译验证 → 成功后写入 GENERATED_SOURCE_DIR。</p>
     *
     * @param source AI 生成的完整 .java 源码
     * @param className 类名（用于文件命名）
     * @return 编译结果
     */
    public CompileResult compileAndPersist(String source, String className) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return CompileResult.fail(className, "JDK JavaCompiler 不可用（可能运行在 JRE 而非 JDK）");
        }

        // ===== 阶段1：临时目录编译验证 =====
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("tool-compile-");
        } catch (Exception e) {
            return CompileResult.fail(className, "创建临时目录失败: " + e.getMessage());
        }

        String packageName = GENERATED_PACKAGE;
        Path packageDir = tempDir.resolve(packageName.replace('.', '/'));
        try {
            Files.createDirectories(packageDir);
            Path tempSource = packageDir.resolve(className + ".java");
            Files.writeString(tempSource, source);

            StringWriter errorWriter = new StringWriter();
            List<String> options = new ArrayList<>(List.of(
                    "-classpath", buildClasspath(),
                    "--release", "17"
            ));

            JavaCompiler.CompilationTask task = compiler.getTask(
                    errorWriter, null, null, options, null,
                    compiler.getStandardFileManager(null, null, null).getJavaFileObjects(tempSource));

            boolean ok = task.call();
            if (!ok) {
                String error = errorWriter.toString().trim();
                return CompileResult.fail(className, "编译失败:\n" + error);
            }
        } catch (Exception e) {
            return CompileResult.fail(className, "编译过程异常: " + e.getMessage());
        } finally {
            // 清理临时目录
            try {
                deleteRecursively(tempDir.toFile());
            } catch (Exception ignored) {}
        }

        // ===== 阶段2：验证通过，持久化到源码目录 =====
        try {
            Path targetDir = Path.of(GENERATED_SOURCE_DIR);
            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(className + ".java");

            // 已存在则覆盖（AI 重新生成时更新）
            Files.writeString(targetFile, source);
            System.out.println("📦 工具源码已持久化: " + targetFile + "（重启后生效）");
            return CompileResult.ok(className, targetFile);
        } catch (Exception e) {
            return CompileResult.fail(className, "持久化源码失败: " + e.getMessage());
        }
    }

    /**
     * 构造编译用 classpath：当前 java.class.path + 项目编译输出目录。
     */
    private String buildClasspath() {
        String classpath = System.getProperty("java.class.path");
        // Maven 编译输出目录通常在 target/classes，已在 java.class.path 中
        return classpath;
    }

    /** 递归删除目录（清理临时文件用） */
    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        f.delete();
    }
}
