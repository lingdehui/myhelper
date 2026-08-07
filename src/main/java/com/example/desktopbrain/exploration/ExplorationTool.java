package com.example.desktopbrain.exploration;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 自主探索相关工具，供 AI 直接调用。
 */
@Component
public class ExplorationTool {

    private final AutonomousExplorationService explorationService;
    private final MemoryMaintenanceService maintenanceService;

    public ExplorationTool(AutonomousExplorationService explorationService,
                            MemoryMaintenanceService maintenanceService) {
        this.explorationService = explorationService;
        this.maintenanceService = maintenanceService;
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
            System.out.println("📚 手动触发探索，主题: " + topic);
        } else {
            System.out.println("📚 手动触发自主探索");
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

    @Tool(description = "将指定 Episode 标记为永久保护，防止被凌晨记忆清理任务自动删除。参数 episodeId 为 Episode 的 UUID。")
    public String protectMemory(
            @ToolParam(description = "Episode 的 UUID") String episodeId) {
        if (episodeId == null || episodeId.isBlank()) {
            return "请提供要保护的 Episode ID。";
        }
        maintenanceService.protectMemory(episodeId);
        return "已标记永久保护: " + episodeId + "。该记忆在凌晨清理时将被跳过。";
    }
}
