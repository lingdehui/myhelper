package com.example.tools.windows.download;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class SoftwareCatalog {

    // 分类 -> 推荐软件列表（按优先级排序）
    private static final Map<String, List<Software>> CATALOG = new HashMap<>();

    static {
        // 开发工具
        CATALOG.put("开发工具", List.of(
                new Software("VS Code", "Microsoft.VisualStudioCode", "轻量级代码编辑器，支持所有主流语言"),
                new Software("IntelliJ IDEA", "JetBrains.IntelliJIDEA.Ultimate", "Java/Kotlin 首选 IDE"),
                new Software("Notepad++", "Notepad++.Notepad++", "轻量级文本编辑器，适合快速编辑"),
                new Software("Git", "Git.Git", "版本控制工具"),
                new Software("Docker Desktop", "Docker.DockerDesktop", "容器化开发工具"),
                new Software("Postman", "Postman.Postman", "API 测试工具")
        ));

        // 浏览器
        CATALOG.put("浏览器", List.of(
                new Software("Chrome", "Google.Chrome", "Google 浏览器"),
                new Software("Edge", "Microsoft.Edge", "Windows 默认浏览器"),
                new Software("Firefox", "Mozilla.Firefox", "开源浏览器"),
                new Software("Brave", "Brave.Brave", "隐私保护浏览器")
        ));

        // 办公软件
        CATALOG.put("办公软件", List.of(
                new Software("WPS Office", "Kingsoft.WPSOffice", "国产办公套件"),
                new Software("LibreOffice", "TheDocumentFoundation.LibreOffice", "开源办公套件"),
                new Software("Microsoft Office", "Microsoft.Office", "微软办公套件（可能需要登录）")
        ));

        // 通信工具
        CATALOG.put("通信工具", List.of(
                new Software("微信", "Tencent.WeChat", "即时通讯"),
                new Software("钉钉", "Alibaba.DingTalk", "企业通讯"),
                new Software("QQ", "Tencent.QQ", "腾讯 QQ"),
                new Software("Telegram", "Telegram.TelegramDesktop", "安全通讯")
        ));

        // 多媒体
        CATALOG.put("多媒体", List.of(
                new Software("VLC", "VideoLAN.VLC", "万能播放器"),
                new Software("PotPlayer", "PotPlayer.PotPlayer", "高性能播放器"),
                new Software("Audacity", "Audacity.Audacity", "音频编辑"),
                new Software("OBS Studio", "OBSProject.OBSStudio", "录屏/直播软件")
        ));

        // 压缩工具
        CATALOG.put("压缩工具", List.of(
                new Software("7-Zip", "7zip.7zip", "开源压缩工具"),
                new Software("Bandizip", "Bandisoft.Bandizip", "轻量级压缩工具"),
                new Software("WinRAR", "WinRAR.WinRAR", "经典压缩工具（需要许可证）")
        ));

        // 系统工具
        CATALOG.put("系统工具", List.of(
                new Software("Everything", "voidtools.Everything", "秒级文件搜索"),
                new Software("Ditto", "Ditto.Ditto", "剪切板增强"),
                new Software("F.lux", "f.lux.f.lux", "护眼工具"),
                new Software("Process Explorer", "Microsoft.Sysinternals.ProcessExplorer", "高级任务管理器")
        ));

        // 下载工具（新增）
        CATALOG.put("下载工具", List.of(
                new Software("IDM", "InternetDownloadManager.IDM", "知名下载管理器（需付费）"),
                new Software("Free Download Manager", "FreeDownloadManager.FreeDownloadManager", "开源下载工具"),
                new Software("qBittorrent", "qBittorrent.qBittorrent", "BT 下载工具")
        ));
    }

    @Tool(description = "根据用户的自然语言意图，返回最匹配的软件推荐。例如用户说'写代码'则推荐开发工具。返回格式：类别名 + 推荐列表")
    public String recommendByIntent(String intent) {
        // 简单关键词匹配（实际可用向量检索增强）
        String matchedCategory = null;
        if (intent.contains("代码") || intent.contains("编程") || intent.contains("开发") || intent.contains("IDE")) {
            matchedCategory = "开发工具";
        } else if (intent.contains("网页") || intent.contains("上网") || intent.contains("浏览")) {
            matchedCategory = "浏览器";
        } else if (intent.contains("办公") || intent.contains("文档") || intent.contains("写作") || intent.contains("表格")) {
            matchedCategory = "办公软件";
        } else if (intent.contains("聊天") || intent.contains("通讯") || intent.contains("微信") || intent.contains("钉钉")) {
            matchedCategory = "通信工具";
        } else if (intent.contains("音乐") || intent.contains("视频") || intent.contains("播放") || intent.contains("录屏")) {
            matchedCategory = "多媒体";
        } else if (intent.contains("压缩") || intent.contains("解压") || intent.contains("zip") || intent.contains("rar")) {
            matchedCategory = "压缩工具";
        } else if (intent.contains("系统") || intent.contains("工具") || intent.contains("搜索") || intent.contains("剪贴")) {
            matchedCategory = "系统工具";
        } else if (intent.contains("下载") || intent.contains("BT") || intent.contains("torrent")) {
            matchedCategory = "下载工具";
        }

        if (matchedCategory == null) {
            // 未匹配到任何分类，返回所有可用分类列表让用户选择
            return "您要安装什么类型的软件？可选类别：开发工具、浏览器、办公软件、通信工具、多媒体、压缩工具、系统工具、下载工具。请告诉我想安装哪一类。";
        }

        List<Software> list = CATALOG.get(matchedCategory);
        if (list == null || list.isEmpty()) {
            return "未找到类别：" + matchedCategory;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(matchedCategory).append("】推荐软件如下：\n");
        for (int i = 0; i < list.size(); i++) {
            Software s = list.get(i);
            sb.append("  ").append(i + 1).append(". ").append(s.displayName)
                    .append(" (ID: ").append(s.wingetId).append(")")
                    .append(" - ").append(s.description).append("\n");
        }
        sb.append("\n请告诉我您想安装哪一个，例如 '安装 VS Code'。");
        return sb.toString();
    }

    @Tool(description = "直接获取某个类别的软件列表，类别为：开发工具、浏览器、办公软件、通信工具、多媒体、压缩工具、系统工具、下载工具")
    public String getSoftwareByCategory(String category) {
        List<Software> list = CATALOG.get(category);
        if (list == null || list.isEmpty()) {
            return "未找到类别：" + category + "，可选类别：开发工具、浏览器、办公软件、通信工具、多媒体、压缩工具、系统工具、下载工具";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(category).append("】软件列表：\n");
        for (int i = 0; i < list.size(); i++) {
            Software s = list.get(i);
            sb.append("  ").append(i + 1).append(". ").append(s.displayName)
                    .append(" (ID: ").append(s.wingetId).append(")")
                    .append(" - ").append(s.description).append("\n");
        }
        return sb.toString();
    }

    // 内部数据类
    private record Software(String displayName, String wingetId, String description) {}
}