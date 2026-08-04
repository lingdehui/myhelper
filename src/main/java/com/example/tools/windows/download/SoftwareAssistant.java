package com.example.tools.windows.download;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class SoftwareAssistant {

    private final SoftwareCatalog catalog;
    private final SoftwareInstaller installer;

    public SoftwareAssistant(SoftwareCatalog catalog, SoftwareInstaller installer) {
        this.catalog = catalog;
        this.installer = installer;
    }

    @Tool(description = "一站式安装：根据用户意图推荐并安装。会先调用分类推荐，然后要求确认。")
    public String installByIntent(String intent) {
        String recommendation = catalog.recommendByIntent(intent);
        // 这里仅返回推荐，具体安装由大模型决定是否调用 installer
        return recommendation;
    }

    // 也可以提供直接安装（需要用户明确指定软件名）
    @Tool(description = "直接安装指定名称的软件，需要知道准确的 winget ID")
    public String installDirect(String softwareId) {
        return installer.installSoftware(softwareId);
    }
}