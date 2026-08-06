package com.example.desktopbrain.autogen;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生成工具注册中心：协调工具自生成的完整流程 + 管理已生成工具记录。
 *
 * <h3>核心流程（用户设计："工具缺失→自己写工具→提醒重启或动态加载"）</h3>
 * <ol>
 *   <li>ToolPlanner 报告 missingDescriptions（能力缺失）</li>
 *   <li>本中心调 {@link ToolGenerator} 让 AI 生成 .java 源码</li>
 *   <li>调 {@link ToolCompiler} 编译验证 + 持久化到 generated 目录</li>
 *   <li>编译成功 → TTS 提醒用户"已生成新工具，重启后生效"</li>
 *   <li>编译失败 → 带错误信息让 AI 重新生成（最多 2 次重试）</li>
 * </ol>
 *
 * <h3>加载策略</h3>
 * <ul>
 *   <li><b>默认（可靠）</b>：持久化到 src/main/java/.../generated/，重启后 Spring 自动扫描 @Component</li>
 *   <li><b>热加载（预留）</b>：用 URLClassLoader 运行时加载，但因 Spring AI ToolCallbackProvider
 *       是启动时扫描的，运行时注入复杂，暂不实现</li>
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

    /** 已生成工具记录：descriptionHash → 注册信息（防止重复生成） */
    private final Map<String, GeneratedToolInfo> generatedTools = new ConcurrentHashMap<>();

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

    public GeneratedToolRegistry(ToolGenerator toolGenerator, ToolCompiler toolCompiler) {
        this.toolGenerator = toolGenerator;
        this.toolCompiler = toolCompiler;
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
                System.out.println("🔧 已加载 " + files.size() + " 个生成工具: "
                        + generatedTools.values().stream().map(GeneratedToolInfo::className).toList());
            }
        } catch (Exception e) {
            System.err.println("⚠️ 扫描生成工具目录失败: " + e.getMessage());
        }
    }

    /**
     * 生成并持久化新工具（带重试）。
     *
     * <p>流程：AI 生成源码 → 编译验证 → 持久化。
     * 编译失败时带错误信息让 AI 重试，最多 {@code maxRetries} 次。</p>
     *
     * @param description 工具功能描述（来自 missingDescriptions）
     * @param maxRetries 编译失败时最大重试次数（默认 2）
     * @return 生成结果
     */
    public GenerationOutcome generateAndPersist(String description, int maxRetries) {
        String hash = hashDescription(description);
        GeneratedToolInfo existing = generatedTools.get(hash);
        if (existing != null) {
            String msg = "工具已存在（" + existing.className() + "），如需重新生成请先删除";
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
                String msg = "已生成新工具 " + generated.className()
                        + "，重启应用后生效（已写入 " + result.sourceFile() + "）";
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
