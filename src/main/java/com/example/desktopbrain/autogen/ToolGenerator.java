package com.example.desktopbrain.autogen;

import com.example.desktopbrain.common.AiResponseUtils;
import com.example.desktopbrain.common.PromptLoader;
import com.example.desktopbrain.config.ModelRouter;
import com.example.desktopbrain.config.SystemEnvironmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ToolGenerator.class);

    private final ModelRouter modelRouter;
    private final PromptLoader promptLoader;
    private final SystemEnvironmentService envService;

    public ToolGenerator(ModelRouter modelRouter, PromptLoader promptLoader,
                          SystemEnvironmentService envService) {
        this.modelRouter = modelRouter;
        this.promptLoader = promptLoader;
        this.envService = envService;
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
        String prompt = promptLoader.getToolGenerator()
                .formatted(envService.getOsInfo(), toolDescription);

        try {
            String source = modelRouter.normal().prompt().user(prompt).call().content();
            if (source == null || source.isBlank()) return null;

            // 去掉可能的 markdown 代码块标记
            source = AiResponseUtils.stripMarkdownCodeBlock(source);

            String className = extractClassName(source);
            if (className == null) {
                log.warn("❌ 生成的源码无法提取类名");
                return null;
            }

            log.info("✅ AI 已生成工具源码: {}", className);
            return new GeneratedSource(className, source);
        } catch (Exception e) {
            log.error("❌ 工具源码生成失败", e);
            return null;
        }
    }

    /**
     * 从源码中提取类名（支持 public class / public final class / public abstract class）。
     */
    private String extractClassName(String source) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "public\\s+(?:final\\s+|abstract\\s+)?class\\s+(\\w+)");
        java.util.regex.Matcher m = p.matcher(source);
        if (m.find()) return m.group(1);
        return null;
    }
}
