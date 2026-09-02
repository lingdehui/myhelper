package com.example.myhelper.generated;

import com.example.myhelper.generated.GeneratedTool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Docker 安装问题的说明工具，向调用方提供平台相关的后续操作建议。
 */
@Component
@GeneratedTool
public class DockerInstallationGuide {

    @Tool(description = "获取Docker的安装教程")
    public String getDockerInstallationGuide(
            @ToolParam(description = "操作系统类型") String osType) {
        try {
            switch (osType.toLowerCase()) {
                case "windows":
                    return "在Windows上，可以通过访问Docker官网下载Docker Desktop，并按照提示进行安装。";
                case "mac":
                    return "在MacOS上，可以通过苹果应用商店或者Docker官网下载Docker Desktop来完成安装过程。";
                case "linux":
                    return """
                            在Linux系统中，请打开终端并执行以下命令：
                            $ sudo apt-get update
                            $ sudo apt-get install docker-ce docker-ce-cli containerd.io
                            安装完成后，通过输入 'docker run hello-world' 来验证Docker是否安装成功。
                            """;
                default:
                    return "未知操作系统类型，请提供有效的操作系统如Windows, Mac或Linux。";
            }
        } catch (Exception e) {
            return "获取安装教程时发生错误：" + e.getMessage();
        }
    }
}
