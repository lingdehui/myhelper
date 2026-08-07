package com.example.desktopbrain.autogen;

import jakarta.annotation.PostConstruct;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成工具注册中心：协调工具自生成的完整流程 + 管理已生成工具记录。
 *
 * <h3>核心流程（用户设计："工具缺失→自己写工具→即时生效"）</h3>
 * <ol>
 *   <li>ToolPlanner 报告 missingDescriptions（能力缺失）</li>
 *   <li>本中心调 {@link ToolGenerator} 让 AI 生成 .java 源码</li>
 *   <li>调 {@link ToolCompiler} 编译验证 + 持久化到 generated 目录</li>
 *   <li>编译成功 → 运行时编译到 target/generated-classes/ → URLClassLoader 动态加载 → 即时生效</li>
 *   <li>编译失败 → 带错误信息让 AI 重新生成（最多 2 次重试）</li>
 * </ol>
 *
 * <h3>加载策略</h3>
 * <ul>
 *   <li><b>即时加载</b>：编译到 target/generated-classes/，URLClassLoader 加载，MethodToolCallbackProvider 创建 ToolCallback</li>
 *   <li><b>持久化</b>：同步写入 src/main/java/.../generated/，重启后 Maven 编译 + Spring 自动扫描</li>
 * </ul>
 *
 * <h3>防重复生成</h3>
 * <p>用 descriptionHash → className 映射记录已生成工具，
 * 相同描述的缺失不重复生成（除非用户显式要求重新生成）。</p>
 */
@Service
public class GeneratedToolRegistry {

    private final ToolGenerator toolGenerator;
    private final ToolCompiler toolCompiler;
    private final ApplicationContext applicationContext;

    /** 已生成工具记录：descriptionHash → 注册信息（防止重复生成） */
    private final Map<String, GeneratedToolInfo> generatedTools = new ConcurrentHashMap<>();

    /** 动态加载的工具列表（运行时即时生效） */
    private final List<ToolCallback> dynamicTools = Collections.synchronizedList(new ArrayList<>());

    /** 用于动态加载的 ClassLoader（parent = 当前 ClassLoader） */
    private URLClassLoader dynamicClassLoader;

    /** 生成结果记录 */
    public record GeneratedToolInfo(String className, String description, Path sourceFile, long timestamp) {}

    /**
     * 单次生成结果。
     *
     * @param success 是否成功（生成+编译+持久化全通过）
     * @param message 反馈消息（用于 TTS 播报和日志）
     * @param className 生成的类名（成功时非 null）
     */
    public record GenerationOutcome(boolean success, String message, String className) {}

    public GeneratedToolRegistry(ToolGenerator toolGenerator, ToolCompiler toolCompiler,
                                   ApplicationContext applicationContext) {
        this.toolGenerator = toolGenerator;
        this.toolCompiler = toolCompiler;
        this.applicationContext = applicationContext;
    }

    /**
     * 启动时扫描 generated 目录，加载已生成工具的记录。
     */
    @PostConstruct
    public void scanExistingGeneratedTools() {
        try {
            Path dir = Path.of(ToolCompiler.GENERATED_SOURCE_DIR);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                return;
            }
            List<Path> files = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".java"))
                      .forEach(files::add);
            }
            for (Path f : files) {
                String className = f.getFileName().toString().replace(".java", "");
                String content = Files.readString(f);
                String desc = extractFirstToolDescription(content);
                String hash = hashDescription(desc != null ? desc : className);
                generatedTools.put(hash,
                        new GeneratedToolInfo(className, desc != null ? desc : className, f, 0));
            }
            if (!files.isEmpty()) {
                System.out.println("🔧 已加载 " + files.size() + " 个生成工具记录: "
                        + generatedTools.values().stream().map(GeneratedToolInfo::className).toList());
            }
        } catch (Exception e) {
            System.err.println("⚠️ 扫描生成工具目录失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有动态加载的工具（供 DesktopBrainApplication 合并到工具列表）。
     */
    public ToolCallback[] getDynamicTools() {
        return dynamicTools.toArray(new ToolCallback[0]);
    }

    /**
     * 初始化动态 ClassLoader（需在 target/classes 就绪后调用）。
     */
    public void initDynamicClassLoader() {
        try {
            Path classDir = Path.of(ToolCompiler.GENERATED_CLASSES_DIR);
            if (!Files.exists(classDir)) Files.createDirectories(classDir);
            this.dynamicClassLoader = new URLClassLoader(
                    new URL[]{classDir.toUri().toURL()},
                    getClass().getClassLoader()
            );
            System.out.println("⚡ 动态 ClassLoader 就绪 (" + classDir + ")");
        } catch (Exception e) {
            System.err.println("⚠️ 初始化动态 ClassLoader 失败: " + e.getMessage());
        }
    }

    /**
     * 生成并持久化新工具（带重试 + 运行时动态加载）。
     *
     * <p>流程：AI 生成源码 → 编译验证 → 持久化 → 运行时编译 → 动态加载 → 即时生效。</p>
     *
     * @param description 工具功能描述（来自 missingDescriptions）
     * @param maxRetries 编译失败时最大重试次数（默认 2）
     * @return 生成结果
     */
    public GenerationOutcome generateAndPersist(String description, int maxRetries) {
        String hash = hashDescription(description);
        GeneratedToolInfo existing = generatedTools.get(hash);
        if (existing != null) {
            String msg = "工具已存在（" + existing.className() + "），跳过重复生成";
            System.out.println("ℹ️ " + msg);
            return new GenerationOutcome(true, msg, existing.className());
        }

        // 带编译错误反馈的重试循环
        String feedback = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            String effectiveDesc = (feedback != null)
                    ? description + "\n\n上次生成的代码编译失败，错误如下，请修复:\n" + feedback
                    : description;

            ToolGenerator.GeneratedSource generated = toolGenerator.generate(effectiveDesc);
            if (generated == null) {
                return new GenerationOutcome(false, "AI 生成源码失败", null);
            }

            ToolCompiler.CompileResult result = toolCompiler.compileAndPersist(
                    generated.sourceCode(), generated.className());

            if (result.success()) {
                generatedTools.put(hash, new GeneratedToolInfo(
                        generated.className(), description, result.sourceFile(),
                        System.currentTimeMillis()));

                // ===== 运行时动态加载（即时生效，无需重启）=====
                boolean runtimeLoaded = loadAtRuntime(generated.sourceCode(), generated.className());

                String msg = "已生成新工具 " + generated.className()
                        + (runtimeLoaded ? "（已即时生效）" : "（重启后生效）");
                System.out.println("✅ " + msg);
                return new GenerationOutcome(true, msg, generated.className());
            }

            // 编译失败，记录错误信息作为下次生成的反馈
            feedback = result.errorMessage();
            System.out.println("⚠️ 第 " + (attempt + 1) + " 次生成编译失败，"
                    + (attempt < maxRetries ? "将带错误信息重试" : "已达最大重试次数"));
        }

        return new GenerationOutcome(false, "工具生成失败（编译错误，重试 " + maxRetries + " 次仍未通过）", null);
    }

    // ===== 运行时动态加载 =====

    /**
     * 运行时编译 + 类加载 + 创建 ToolCallback。
     *
     * @return true = 即时生效成功；false = 降级到重启后生效
     */
    private boolean loadAtRuntime(String sourceCode, String className) {
        try {
            // 1. 运行时编译到 target/generated-classes/
            Path classDir = toolCompiler.compileForRuntime(sourceCode, className);
            if (classDir == null) return false;

            // 2. URLClassLoader 加载类
            if (dynamicClassLoader == null) initDynamicClassLoader();
            if (dynamicClassLoader == null) return false;

            Class<?> clazz;
            try {
                clazz = Class.forName(ToolCompiler.GENERATED_PACKAGE + "." + className,
                        true, dynamicClassLoader);
            } catch (ClassNotFoundException e) {
                // 新 URLClassLoader 重新加载
                dynamicClassLoader = new URLClassLoader(
                        new URL[]{classDir.toUri().toURL()},
                        getClass().getClassLoader()
                );
                clazz = Class.forName(ToolCompiler.GENERATED_PACKAGE + "." + className,
                        true, dynamicClassLoader);
            }

            // 3. 尝试实例化
            // 优先无参构造（简单工具），失败则尝试注入 Spring Bean（持久化工具）
            Object instance;
            try {
                instance = clazz.getDeclaredConstructor().newInstance();
            } catch (NoSuchMethodException e) {
                // AI 按 prompt 生成了带构造注入的类 → 从 Spring context 获取 Bean
                instance = applicationContext.getBean(clazz);
            }

            // 4. 用 MethodToolCallbackProvider 创建 ToolCallback
            ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(instance)
                    .build()
                    .getToolCallbacks();

            // 5. 注册到动态工具列表
            for (ToolCallback tc : callbacks) {
                dynamicTools.add(tc);
                System.out.println("  ⚡ 动态注册工具: " + tc.getToolDefinition().name());
            }

            System.out.println("⚡ 工具 " + className + " 已即时生效（" + callbacks.length + " 个方法）");
            return true;
        } catch (Exception e) {
            System.err.println("⚠️ 运行时动态加载失败（降级为重启后生效）: " + e.getMessage());
            return false;
        }
    }

    /** 便捷重载：默认重试 2 次 */
    public GenerationOutcome generateAndPersist(String description) {
        return generateAndPersist(description, 2);
    }

    /** 列出所有已生成工具 */
    public List<GeneratedToolInfo> listGeneratedTools() {
        return new ArrayList<>(generatedTools.values());
    }

    /** 删除已生成工具（删除源码文件 + 清除记录） */
    public boolean removeGeneratedTool(String className) {
        var entry = generatedTools.entrySet().stream()
                .filter(e -> e.getValue().className().equals(className))
                .findFirst();
        if (entry.isEmpty()) return false;

        try {
            Files.deleteIfExists(entry.get().getValue().sourceFile());
            generatedTools.remove(entry.get().getKey());
            System.out.println("🗑️ 已删除生成工具: " + className + "（重启后完全移除）");
            return true;
        } catch (Exception e) {
            System.err.println("❌ 删除生成工具失败: " + e.getMessage());
            return false;
        }
    }

    // ========== 内部工具方法 ==========

    /** description 的简单 hash（避免重复生成相同描述的工具） */
    private static String hashDescription(String desc) {
        if (desc == null) return "null";
        // 标准化：去标点空格小写后取 hash
        String normalized = desc.replaceAll("[\\s\\p{P}]", "").toLowerCase();
        return Integer.toHexString(normalized.hashCode());
    }

    /** 从源码中提取第一个 @Tool 的 description（用于记录） */
    private static String extractFirstToolDescription(String source) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "@Tool\\s*\\(\\s*description\\s*=\\s*\"([^\"]+)\"");
        java.util.regex.Matcher m = p.matcher(source);
        return m.find() ? m.group(1) : null;
    }
}
