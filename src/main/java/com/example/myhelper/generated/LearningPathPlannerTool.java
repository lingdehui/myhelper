package com.example.myhelper.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 根据目标生成学习路径建议的工具。它产出计划，不负责执行学习任务或写入长期记忆。
 */
@Component
@GeneratedTool
public class LearningPathPlannerTool {

    @Tool(description = "根据输入的学习主题，生成带时间、练习和验收标准的学习路径")
    public String generatePersonalizedLearningPath(@ToolParam(description = "学习的主题") String topic) {
        if (topic == null || topic.isBlank()) return "请提供要学习的主题。";
        String normalizedTopic = topic.trim();
        return "《" + normalizedTopic + "》学习路径\n"
                + "1. 明确目标（30 分钟）：写下希望解决的问题、已有基础和可投入时间。\n"
                + "2. 建立基础（第 1 周）：学习核心概念与术语；每个概念配一个最小示例。\n"
                + "3. 刻意练习（第 2-3 周）：围绕 " + normalizedTopic + " 完成 3 个由易到难的小练习，并记录错误与改进。\n"
                + "4. 项目整合（第 4 周）：完成一个可展示的小项目，说明设计取舍、结果和下一步。\n"
                + "5. 复盘巩固：每周回顾一次；无法独立完成的部分回到第 2 步补齐。\n"
                + "验收标准：能够用自己的话解释核心概念，并独立完成一个与 " + normalizedTopic + " 相关的实际任务。";
    }
}
