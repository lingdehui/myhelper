package com.example.desktopbrain.autogen;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 工具生成器：AI 根据缺失的工具描述生成 .java 源码。
 *
 * <p>当 ToolPlanner 报告 missingDescriptions（用户请求需要某能力但分类里没有）时，
 * 调本生成器让 AI 写一个带 @Component + @Tool 注解的 Java 类源码。</p>
 *
 * <h3>生成流程</h3>
 * <ol>
 *   <li>AI 根据工具描述 + 模板生成完整 .java 源码</li>
 *   <li>从源码中提取类名（用于后续编译和文件命名）</li>
 *   <li>返回 {@link GeneratedSource} 给 {@link ToolCompiler} 验证语法</li>
 * </ol>
 *
 * <h3>生成的代码规范</h3>
 * <ul>
 *   <li>包名固定：com.example.desktopbrain.generated（在 ComponentScan 范围内）</li>
 *   <li>必须带 @Component（重启后 Spring 自动扫描注册为 Bean）</li>
 *   <li>工具方法必须带 @Tool(description=...) 注解</li>
 *   <li>参数必须带 @ToolParam(description=...) 注解</li>
 *   <li>只返回 String 类型，异常内部 catch 不外抛</li>
 * </ul>
 */
@Service
public class ToolGenerator {

    private final ChatClient chatClient;

    public ToolGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder
                .defaultSystem("你是 Java 工具代码生成器，根据功能描述生成可直接编译的 @Tool 工具类源码。")
                .build();
    }

    /**
     * 生成结果。
     *
     * @param className 类名（不含包名，如 "FileCompressor"）
     * @param sourceCode 完整 .java 源码
     */
    public record GeneratedSource(String className, String sourceCode) {}

    /**
     * AI 生成工具源码。
     *
     * @param toolDescription 工具功能描述（来自 missingDescriptions）
     * @return 生成的源码 + 类名；AI 调用失败时返回 null
     */
    public GeneratedSource generate(String toolDescription) {
        String prompt = """
                根据以下功能描述，生成一个完整的 Java 工具类源码。

                功能描述: %s

                严格要求:
                1. 包名必须是: com.example.desktopbrain.generated
                2. 类名用大驼峰，见名知意，加 @Component 注解
                3. 工具方法加 @Tool(description="...") 注解，方法返回 String
                4. 参数加 @ToolParam(description="...") 注解
                5. 需要 import 的注解:
                   - org.springframework.ai.tool.annotation.Tool
                   - org.springframework.ai.tool.annotation.ToolParam
                   - org.springframework.stereotype.Component
                6. 方法内部异常要 try-catch，返回友好的错误提示字符串，不外抛异常
                7. 只用 JDK 标准库 + Spring AI 注解，不引入第三方依赖
                8. 如果涉及系统操作（执行命令/文件/进程），用 java.lang.ProcessBuilder 或 java.nio.file
                9. 只返回源码本身，不要 markdown 代码块标记，不要解释

                示例格式:
                package com.example.desktopbrain.generated;

                import org.springframework.ai.tool.annotation.Tool;
                import org.springframework.ai.tool.annotation.ToolParam;
                import org.springframework.stereotype.Component;

                @Component
                public class FileCompressor {

                    @Tool(description = "压缩指定文件或文件夹为 zip 格式")
                    public String compressToZip(@ToolParam(description = "要压缩的源文件路径") String sourcePath,
                                                @ToolParam(description = "输出的 zip 文件路径") String outputPath) {
                        try {
                            // 实现逻辑
                            return "压缩成功: " + outputPath;
                        } catch (Exception e) {
                            return "压缩失败: " + e.getMessage();
                        }
                    }
                }
                """.formatted(toolDescription);

        try {
            String source = chatClient.prompt().user(prompt).call().content();
            if (source == null || source.isBlank()) return null;

            // 去掉可能的 markdown 代码块标记
            source = source.trim();
            if (source.startsWith("```")) {
                source = source.replaceAll("^```\\w*\\n?", "").replaceAll("\\n?```$", "").trim();
            }

            String className = extractClassName(source);
            if (className == null) {
                System.err.println("❌ 生成的源码无法提取类名");
                return null;
            }

            System.out.println("✅ AI 已生成工具源码: " + className);
            return new GeneratedSource(className, source);
        } catch (Exception e) {
            System.err.println("❌ 工具源码生成失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从源码中提取类名（public class Xxx 后的标识符）。
     */
    private String extractClassName(String source) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "public\\s+class\\s+(\\w+)");
        java.util.regex.Matcher m = p.matcher(source);
        if (m.find()) return m.group(1);
        return null;
    }
}
