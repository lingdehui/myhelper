package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@GeneratedTool
public class LearningPathPlannerTool {

    @Tool(description = "根据输入的学习主题，自动生成个性化学习路径")
    public String generatePersonalizedLearningPath(@ToolParam(description = "学习的主题") String topic) {
        try {
            // 模拟路径规划逻辑, 这里仅返回模拟结果
            return "您的个性化学习路径已准备好: 开始->" + topic + "->进阶知识";
        } catch (Exception e) {
            // 返回友好的错误信息，而不是抛出异常
            return "抱歉，生成学习路径时发生了意外。详细原因可能是: " + e.getMessage();
        }
    }

}