package com.example.desktopbrain.autogen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li><b>持久化</b>：验证通过后才写入 src/main/java/.../generated/（Maven 编译用）</li>
 * </ol>
 *
 * <p>新增 <b>运行时编译</b>：编译到 target/generated-classes/，供 URLClassLoader 动态加载，
 * 实现生成工具即时生效无需重启。</p>
 *
 * <h3>classpath 构造</h3>
 * <p>编译需要 Spring AI 注解（@Tool/@ToolParam）和 Spring 注解（@Component）在 classpath 上。
 * 用当前 ClassLoader 的 classpath（java.class.path）+ 依赖 JAR 路径。</p>
 */
@Service
public class ToolCompiler {

    private static final Logger log = LoggerFactory.getLogger(ToolCompiler.class);

    /** 生成工具的源码目录（在 ComponentScan 范围内，重启后自动扫描） */
    public static final String GENERATED_SOURCE_DIR = "src/main/java/com/example/desktopbrain/generated";

    /** 生成工具的运行时编译输出目录（供动态加载） */
    public static final String GENERATED_CLASSES_DIR = "target/generated-classes";

    /** 生成工具的包名 */
    public static final String GENERATED_PACKAGE = "com.example.desktopbrain.generated";

    /**
     * 编译结果。
     *
     * @param success 是否编译成功
     * @param className 类名
     * @param sourceFile 持久化的源码文件路径（成功时非 null）
     * @param classDir 编译输出的 class 目录（运行时编译时非 null，用于 URLClassLoader）
     * @param errorMessage 编译错误信息（失败时非 null）
     */
    public record CompileResult(boolean success, String className,
                                 Path sourceFile, Path classDir,
                                 String errorMessage) {
        public static CompileResult ok(String className, Path sourceFile) {
            return new CompileResult(true, className, sourceFile, null, null);
        }
        public static CompileResult okRuntime(String className, Path sourceFile, Path classDir) {
            return new CompileResult(true, className, sourceFile, classDir, null);
        }
        public static CompileResult fail(String className, String error) {
            return new CompileResult(false, className, null, null, error);
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

            boolean ok = doCompile(compiler, tempSource, tempDir.resolve("classes"));
            if (!ok) {
                // 重新编译一次获取错误信息
                StringWriter errorWriter = new StringWriter();
                List<String> options = new ArrayList<>(List.of(
                        "-classpath", buildClasspath(),
                        "-d", tempDir.resolve("classes").toString(),
                        "--release", "17"
                ));
                JavaCompiler.CompilationTask task = compiler.getTask(
                        errorWriter, null, null, options, null,
                        compiler.getStandardFileManager(null, null, null).getJavaFileObjects(tempSource));
                task.call();
                return CompileResult.fail(className, "编译失败:\n" + errorWriter.toString().trim());
            }
        } catch (Exception e) {
            return CompileResult.fail(className, "编译过程异常: " + e.getMessage());
        } finally {
            try {
                deleteRecursively(tempDir.toFile());
            } catch (Exception ignored) {}
        }

        // ===== 阶段2：验证通过，持久化到源码目录 =====
        try {
            Path targetDir = Path.of(GENERATED_SOURCE_DIR);
            Files.createDirectories(targetDir);
            Path targetFile = targetDir.resolve(className + ".java");
            Files.writeString(targetFile, source);
            log.info("📦 工具源码已持久化: {}", targetFile);
            return CompileResult.ok(className, targetFile);
        } catch (Exception e) {
            return CompileResult.fail(className, "持久化源码失败: " + e.getMessage());
        }
    }

    /**
     * 运行时编译：编译到 target/generated-classes/，返回 class 文件目录供 URLClassLoader 加载。
     *
     * @param sourceCode AI 生成的源码
     * @param className 类名
     * @return 编译后的 class 目录路径（失败返回 null）
     */
    public Path compileForRuntime(String sourceCode, String className) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            log.warn("❌ 运行时编译失败: JDK JavaCompiler 不可用");
            return null;
        }
        try {
            Path classDir = Path.of(GENERATED_CLASSES_DIR);
            Files.createDirectories(classDir);

            // 先写临时 .java 文件再编译
            Path pkgDir = classDir.resolve(GENERATED_PACKAGE.replace('.', '/'));
            Files.createDirectories(pkgDir);
            Path sourceFile = pkgDir.resolve(className + ".java");
            Files.writeString(sourceFile, sourceCode);

            boolean ok = doCompile(compiler, sourceFile, classDir);
            if (!ok) {
                log.error("❌ 运行时编译失败: {}", className);
                return null;
            }

            // 编译完删除 .java 文件（源码已在 GENERATED_SOURCE_DIR 持久化）
            try { Files.deleteIfExists(sourceFile); } catch (Exception ignored) {}

            log.info("⚡ 运行时编译完成: {} → {}", className, classDir);
            return classDir;
        } catch (Exception e) {
            log.error("❌ 运行时编译异常", e);
            return null;
        }
    }

    /** 执行一次编译，成功返回 true */
    private boolean doCompile(JavaCompiler compiler, Path sourceFile, Path outputDir) throws Exception {
        Files.createDirectories(outputDir);
        List<String> options = new ArrayList<>(List.of(
                "-classpath", buildClasspath(),
                "-d", outputDir.toString(),
                "--release", "17"
        ));
        StringWriter errorWriter = new StringWriter();
        JavaCompiler.CompilationTask task = compiler.getTask(
                errorWriter, null, null, options, null,
                compiler.getStandardFileManager(null, null, null).getJavaFileObjects(sourceFile));
        boolean ok = task.call();
        if (!ok) {
            String err = errorWriter.toString().trim();
            if (!err.isEmpty()) log.warn("⚠️ 编译错误:\n{}", err);
        }
        return ok;
    }

    /**
     * 构造编译用 classpath：当前 java.class.path + 项目编译输出目录。
     */
    private String buildClasspath() {
        String classpath = System.getProperty("java.class.path");
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
