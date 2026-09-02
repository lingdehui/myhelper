package dev.harrjdk.robotmcp.tools.software;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 常见软件安装错误的本地知识库。
 *
 * <p>优先给出确定的离线解决方案；未命中时明确交给联网检索，而不伪造诊断结论。</p>
 */
@Component
public class ErrorKnowledgeBase {

    /** 错误关键词到面向用户的解决方案；键值均使用小写进行包含匹配。 */
    private static final Map<String, String> ERROR_SOLUTIONS = new HashMap<>();

    static {
        // —— winget 相关错误 ——
        ERROR_SOLUTIONS.put("winget not found",
                "❌ winget 未安装。\n" +
                        "解决方案：\n" +
                        "1. 从 Microsoft Store 搜索并安装 '应用安装程序'（App Installer）\n" +
                        "2. 或访问 https://github.com/microsoft/winget-cli 手动安装\n" +
                        "3. 安装完成后，重新打开命令行或重启本程序");

        ERROR_SOLUTIONS.put("package not found",
                "❌ 未找到指定的软件包。\n" +
                        "解决方案：\n" +
                        "1. 请先使用 searchSoftware 工具搜索正确的包名\n" +
                        "2. 包名可能区分大小写，请确认拼写正确\n" +
                        "3. 某些软件可能需要从官网手动下载安装");

        ERROR_SOLUTIONS.put("access denied",
                "❌ 权限不足。\n" +
                        "解决方案：\n" +
                        "1. 请以管理员身份运行本程序（右键 -> 以管理员身份运行）\n" +
                        "2. 或在管理员命令行中启动：java -jar robot-mcp.jar");

        ERROR_SOLUTIONS.put("already installed",
                "⚠️ 该软件已安装。\n" +
                        "解决方案：\n" +
                        "1. 无需重复安装\n" +
                        "2. 如需重新安装，请先手动卸载后再试");

        ERROR_SOLUTIONS.put("source not found",
                "❌ winget 源连接失败。\n" +
                        "解决方案：\n" +
                        "1. 检查网络连接\n" +
                        "2. 运行 winget source reset 重置源\n" +
                        "3. 如使用代理，请配置 winget 代理");

        // —— 通用错误 ——
        ERROR_SOLUTIONS.put("timeout",
                "⏰ 操作超时。\n" +
                        "解决方案：\n" +
                        "1. 检查网络连接是否正常\n" +
                        "2. 稍后重试\n" +
                        "3. 如果问题持续，可手动下载安装");
    }

    /**
     * 根据错误信息查询已知解决方案。
     * 如果命中已知错误，返回具体解决方案；否则建议联网搜索。
     */
    /** 查找本地已知错误的修复建议；未命中则建议使用网络搜索。 */
    @Tool(description = """
            根据错误信息查询已知的解决方案。
            支持 winget 常见错误、权限问题、网络问题等。
            如果本地知识库没有匹配，会建议使用 webSearch 工具联网搜索。
            """)
    public String getSolutionForError(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) {
            return "⚠️ 错误信息为空，无法分析。请提供具体的错误日志。";
        }

        String lowerMsg = errorMessage.toLowerCase();

        // 精确匹配
        for (Map.Entry<String, String> entry : ERROR_SOLUTIONS.entrySet()) {
            if (lowerMsg.contains(entry.getKey().toLowerCase())) {
                return entry.getValue();
            }
        }

        // 未命中，提供建议
        return "🔍 本地知识库未找到该错误的具体解决方案。\n" +
                "建议操作：\n" +
                "1. 使用 webSearch 工具搜索以下关键词：\n" +
                "   \"" + errorMessage.substring(0, Math.min(50, errorMessage.length())) + "\"\n" +
                "2. 或访问 winget 官方文档：https://github.com/microsoft/winget-cli\n" +
                "3. 也可尝试在命令行中手动执行 winget 命令，查看详细错误";
    }

    /**
     * 获取所有已知错误的索引（供大模型参考，可选）
     */
    @Tool(description = "列出本地知识库支持的所有错误类型关键词，帮助用户了解本工具能处理哪些错误")
    public String getKnownErrorTypes() {
        StringBuilder sb = new StringBuilder("📚 本地知识库支持的错误类型：\n");
        for (String key : ERROR_SOLUTIONS.keySet()) {
            sb.append("  - ").append(key).append("\n");
        }
        return sb.toString();
    }
}
