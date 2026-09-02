package com.example.myhelper.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 统一 Prompt 加载器：从 classpath:prompts/*.txt 加载 AI Prompt 模板。
 * 各 Service 通过此类获取 prompt 文本，不再硬编码在 Java 源码中。
 *
 * <p>prompt 文件支持 {@code %s} 占位符，调用方通过 {@code String.format()} 填入动态参数。</p>
 */
@Component
public class PromptLoader {

    @Value("classpath:prompts/default-system.txt")
    private Resource defaultSystem;

    @Value("classpath:prompts/planning.txt")
    private Resource planning;

    @Value("classpath:prompts/replanning.txt")
    private Resource replanning;

    @Value("classpath:prompts/verify-execution.txt")
    private Resource verifyExecution;

    @Value("classpath:prompts/extract-signature.txt")
    private Resource extractSignature;

    @Value("classpath:prompts/reflect-success.txt")
    private Resource reflectSuccess;

    @Value("classpath:prompts/reflect-failure.txt")
    private Resource reflectFailure;

    @Value("classpath:prompts/tool-generator.txt")
    private Resource toolGenerator;

    @Value("classpath:prompts/tool-category-sync.txt")
    private Resource toolCategorySync;

    @Value("classpath:prompts/autonomous-exploration.txt")
    private Resource autonomousExploration;

    @Value("classpath:prompts/category-consolidation.txt")
    private Resource categoryConsolidation;

    public String getDefaultSystem()        { return read(defaultSystem); }
    public String getPlanning()             { return read(planning); }
    public String getReplanning()           { return read(replanning); }
    public String getVerifyExecution()      { return read(verifyExecution); }
    public String getExtractSignature()     { return read(extractSignature); }
    public String getReflectSuccess()       { return read(reflectSuccess); }
    public String getReflectFailure()       { return read(reflectFailure); }
    public String getToolGenerator()        { return read(toolGenerator); }
    public String getToolCategorySync()     { return read(toolCategorySync); }
    public String getAutonomousExploration() { return read(autonomousExploration); }
    public String getCategoryConsolidation() { return read(categoryConsolidation); }

    private String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("无法加载 prompt 文件: " + resource, e);
        }
    }
}
