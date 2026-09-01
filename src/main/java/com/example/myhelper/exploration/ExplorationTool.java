package com.example.myhelper.exploration;

import com.example.myhelper.memory.unit.ExperienceQualityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 自主探索相关工具，供 AI 直接调用。
 */
@Component
public class ExplorationTool {

    private static final Logger log = LoggerFactory.getLogger(ExplorationTool.class);

    private final AutonomousExplorationService explorationService;
    private final MemoryMaintenanceService maintenanceService;
    private final ExperienceQualityService experienceQualityService;

    public ExplorationTool(AutonomousExplorationService explorationService,
                            MemoryMaintenanceService maintenanceService,
                            ExperienceQualityService experienceQualityService) {
        this.explorationService = explorationService;
        this.maintenanceService = maintenanceService;
        this.experienceQualityService = experienceQualityService;
    }

    /**
     * 手动触发自主探索学习。
     * 用户可以说 "开始探索" 触发。
     *
     * @param topic 可选的学习主题，为空则 AI 自己决定
     * @return 探索结果描述
     */
    @Tool(description = "手动触发自主探索学习。AI 会在空闲时搜索新知识、测试工具组合、学习新技能。可选指定学习主题。")
    public String startAutonomousLearning(
            @ToolParam(description = "学习主题（可选），为空则 AI 自主决定学什么") String topic) {
        if (topic != null && !topic.isBlank()) {
            log.info("📚 手动触发探索，主题: {}", topic);
        } else {
            log.info("📚 手动触发自主探索");
        }
        explorationService.forceExplore();
        return "已触发自主探索任务，学习结果会自动沉淀到知识库中。";
    }

    /**
     * 查询当前记忆系统的占用率。
     *
     * @return 占用率信息
     */
    @Tool(description = "查询当前记忆系统（Qdrant + Neo4j）的占用率，了解是否需要清理。")
    public String getMemoryUsageStatus() {
        double usage = maintenanceService.getUsageRate();
        return String.format("记忆系统当前占用率: %.1f%%，清理阈值: %.0f%%，目标: %.0f%%",
                usage * 100,
                maintenanceService.getThreshold() * 100,
                maintenanceService.getTarget() * 100);
    }

    @Tool(description = "查看已沉淀执行经验的质量概况，包括可复用经验数量、平均质量分和复用门槛。")
    public String getExperienceQualityStatus() {
        ExperienceQualityService.QualitySummary summary = experienceQualityService.summarize();
        return String.format("经验质量：总计 %d 条，活跃 %d 条，可复用 %d 条；平均质量分 %.2f，复用门槛 %.2f。",
                summary.total(), summary.active(), summary.reusable(),
                summary.averageScore(), summary.minReuseScore());
    }

    @Tool(description = "将指定 Unit 标记为永久保护，防止被凌晨记忆清理任务自动删除。参数 unitId 为 Unit 的 UUID。")
    public String protectMemory(
            @ToolParam(description = "Unit 的 UUID") String unitId) {
        if (unitId == null || unitId.isBlank()) {
            return "请提供要保护的 Unit ID。";
        }
        maintenanceService.protectMemory(unitId);
        return "已标记永久保护: " + unitId + "。该记忆在凌晨清理时将被跳过。";
    }
}
