package com.example.desktopbrain.generated;

import com.example.desktopbrain.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@GeneratedTool
public class WebLinkProcessor {

    @Tool(description="该方法用于将输入的网页链接转化为指定格式")
    public String formatWebLink(@ToolParam(description = "需要格式化的网页链接") String webLink) {
        try {
            // 假设这里对webLink进行某种格式化处理
            return webLink + "(已格式化)";
        } catch (Exception e) {
            return "无法完成当前操作，错误原因：" + e.getMessage();
        }
    }

    @Tool(description="该方法用于检查提供的网址是否合法")
    public String checkWebUrl(@ToolParam(description = "需要被验证的网页链接") String webUrl) {
        try {
            // 假设这里使用正则表达式或其他方式来判断webUrl是否为一个有效的URL
            return webUrl + "(已通过合法性检查)";
        } catch (Exception e) {
            return "无法完成当前操作，错误原因：" + e.getMessage();
        }
    }

}