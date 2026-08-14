package com.example.myhelper.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class CategoryDataNotReadyTool {

    @Tool(description = "处理分类数据未就绪的情况，返回友好提示信息")
    public String handleCategoryDataNotReady(
            @ToolParam(description = "分类名称") String categoryName) {
        try {
            if (categoryName == null || categoryName.trim().isEmpty()) {
                return "错误：分类名称不能为空。";
            }
            return "分类数据未就绪：分类 [" + categoryName + "] 的数据尚未准备好，请稍后重试或联系管理员检查数据初始化状态。";
        } catch (Exception e) {
            return "处理分类数据未就绪时发生异常：" + e.getMessage();
        }
    }
}