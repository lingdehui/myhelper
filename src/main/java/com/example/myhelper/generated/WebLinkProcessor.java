package com.example.myhelper.generated;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;

/**
 * 链接处理工具，负责规范化、检查和提取链接指向的网页信息。
 */
@Component
@GeneratedTool
public class WebLinkProcessor {

    @Tool(description = "规范化 http 或 https 网页链接，去除多余路径段并转换国际化域名")
    public String formatWebLink(@ToolParam(description = "需要格式化的网页链接") String webLink) {
        try {
            URI uri = validateHttpUrl(webLink);
            String host = IDN.toASCII(uri.getHost());
            int port = uri.getPort();
            boolean defaultPort = ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                    || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443);
            URI normalized = new URI(uri.getScheme().toLowerCase(Locale.ROOT), uri.getUserInfo(), host,
                    defaultPort ? -1 : port, uri.getPath(), uri.getQuery(), null).normalize();
            return normalized.toASCIIString();
        } catch (Exception e) {
            return "无法规范化链接：" + e.getMessage();
        }
    }

    @Tool(description = "验证提供的网址是否为带主机名的 http 或 https 链接")
    public String checkWebUrl(@ToolParam(description = "需要被验证的网页链接") String webUrl) {
        try {
            URI uri = validateHttpUrl(webUrl);
            return "链接有效：协议=" + uri.getScheme().toLowerCase(Locale.ROOT) + "，主机=" + uri.getHost();
        } catch (Exception e) {
            return "链接无效：" + e.getMessage();
        }
    }

    private URI validateHttpUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("链接不能为空");
        URI uri = URI.create(value.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("仅支持 http 或 https 协议");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException("缺少主机名");
        return uri;
    }
}
