package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@GeneratedTool
public class AmbiguousRequestHandler {

    @Tool(description = "无法理解用户请求“二十”的具体意图，返回友好的澄清提示")
    public String clarifyAmbiguousNumber(
            @ToolParam(description = "用户原始请求文本，例如“二十”") String userRequest) {
        try {
            if (userRequest == null || userRequest.trim().isEmpty()) {
                return "抱歉，我没有收到有效的请求内容，请补充您的具体需求。";
            }

            String request = userRequest.trim();
            return "抱歉，我暂时无法理解“" + request + "”的具体意图。"
                    + "如果您想表达数字20，请说明具体用途；如果“二十”有其他含义，也请提供更多上下文。";
        } catch (Exception e) {
            return "处理您的请求时出现异常，请稍后重试。";
        }
    }
}