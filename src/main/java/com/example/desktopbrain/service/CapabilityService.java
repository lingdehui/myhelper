package com.example.desktopbrain.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 能力查询：暴露给 AI，让它知道自己有哪些技能
 */
@Component
public class CapabilityService {

    private final SkillConfig skillConfig;

    public CapabilityService(SkillConfig skillConfig) {
        this.skillConfig = skillConfig;
    }

    /**
     * 列出所有本地技能（来自 skills/ 目录）
     */
    @Tool(description = "列出所有本地技能名称。当用户问'你有什么技能'或'你能做什么'时调用此工具。")
    public String listLocalSkills() {
        List<String> names = skillConfig.getSkillNames();
        if (names.isEmpty()) {
            return "暂无本地技能";
        }
        StringBuilder sb = new StringBuilder("本地技能列表（" + names.size() + " 个）：\n");
        for (String name : names) {
            sb.append("  - ").append(name).append("\n");
        }
        return sb.toString().trim();
    }
}
