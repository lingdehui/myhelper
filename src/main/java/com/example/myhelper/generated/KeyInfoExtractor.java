package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@GeneratedTool
public class KeyInfoExtractor {

    @Tool(description = "从输入文本中提取摘要信息")
    public String extractSummary(@ToolParam(description = "要处理的原始文本") final String rawText) {
        try {
            // 这里模拟一个简单的分词和关键信息提取逻辑，实际应用可以根据需求改进
            String[] words = rawText.split("\\s+");
            StringBuilder summaryBuilder = new StringBuilder();
            for (String word : words) {
                if (word.length() > 5) { // 假设较长的词汇更可能是重要信息
                    summaryBuilder.append(word).append(" ");
                }
            }
            return summaryBuilder.toString().trim();
        } catch (Exception e) {
            return "提取摘要时出错: " + e.getMessage();
        }
    }

    @Tool(description = "根据提供的URL打开浏览器")
    public String openBrowser(@ToolParam(description = "要访问的网页地址") final String url){
        try{
            if(System.getProperty("os.name").startsWith("Windows")){
                ProcessBuilder builder = new ProcessBuilder("cmd", "/c", "start", url);
                builder.start();
            } else {
                // 对于非 Windows 系统，这里仅示例为 windows 内容提供一致性
                return "本功能当前在非 Windows 操作系统中不可用";
            }
        } catch (Exception e){
            return "打开浏览器时发生错误: " + e.getMessage();
        }
        return "操作成功执行";
    }
}