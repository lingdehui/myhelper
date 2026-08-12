package com.example.desktopbrain.registry;

import com.example.desktopbrain.autogen.GeneratedToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具同步服务：启动时扫描所有工具来源，与数据库对比，确保一致性。
 *
 * <h3>同步流程</h3>
 * <ol>
 *   <li>从 ToolCallback[] 提取所有工具的完整元数据 → ToolModel 列表</li>
 *   <li>与 Neo4j 现有记录对比：新增入库 / 更新 / 废弃（代码中已不存在的）</li>
 *   <li>MCP 工具验证存活（可选异步）</li>
 *   <li>分类写入图谱</li>
 * </ol>
 */
@Service
public class ToolSyncService {

    private static final Logger log = LoggerFactory.getLogger(ToolSyncService.class);

    private final ToolRegistry toolRegistry;
    private final GeneratedToolRegistry generatedToolRegistry;

    public ToolSyncService(ToolRegistry toolRegistry,
                            GeneratedToolRegistry generatedToolRegistry) {
        this.toolRegistry = toolRegistry;
        this.generatedToolRegistry = generatedToolRegistry;
    }

    /**
     * 启动时执行全量同步。
     *
     * @param allTools  所有可用 ToolCallback（MCP + 本地 Java @Tool + 动态生成）
     * @param mcpCount  MCP 工具数量（用于区分来源）
     */
    public void syncOnStartup(ToolCallback[] allTools, int mcpCount) {
        log.info("🔄 启动工具同步: {} 个工具 (MCP {} + 本地 {})...",
                allTools.length, mcpCount, allTools.length - mcpCount);

        // 🔍 诊断：打印前 30 个 MCP 工具的注册名（看是否有前缀）
        if (mcpCount > 0) {
            int dumpCount = Math.min(mcpCount, 30);
            StringBuilder mcpNames = new StringBuilder("🔍 [诊断] MCP工具注册名 (前" + dumpCount + "):\n");
            for (int i = 0; i < dumpCount; i++) {
                String name = allTools[i].getToolDefinition().name();
                mcpNames.append("  [").append(i).append("] ").append(name).append("\n");
            }
            log.info(mcpNames.toString());
        }

        // 1. 确保 Qdrant 集合存在
        toolRegistry.ensureCollection();

        // 2. 从 ToolCallback 提取 ToolModel（含完整参数）
        Map<String, ToolModel> currentModels = new LinkedHashMap<>();
        int mcpIdx = 0;
        for (int i = 0; i < allTools.length; i++) {
            ToolCallback tc = allTools[i];
            ToolModel model = extractFromToolCallback(tc, i < mcpCount);
            if (model != null) {
                currentModels.put(model.id(), model);
            }
        }

        // 3. 对比 Neo4j 已有记录
        Set<String> dbIds;
        try {
            dbIds = toolRegistry.findAllActive().stream()
                    .map(ToolModel::id).collect(Collectors.toSet());
        } catch (Exception e) {
            log.warn("⚠️ 无法读取 Neo4j 工具记录，全量入库: {}", e.getMessage());
            dbIds = Set.of();
        }

        // 4. 新增 & 更新（Neo4j 不可用时跳过写库））
        int added = 0, updated = 0;
        boolean neo4jAvailable = true;
        try {
            for (ToolModel model : currentModels.values()) {
                if (!dbIds.contains(model.id())) {
                    toolRegistry.upsertTool(model);
                    added++;
                } else {
                    Optional<ToolModel> existing = toolRegistry.findById(model.id());
                    if (existing.isPresent() && needsUpdate(existing.get(), model)) {
                        toolRegistry.upsertTool(model);
                        updated++;
                    }
                }
            }
        } catch (Exception e) {
            neo4jAvailable = false;
            log.warn("⚠️ Neo4j 写入失败，工具注册表仅使用内存+Qdrant: {}", e.getMessage());
        }

        // 5. 废弃（数据库有但代码中没有的）
        int deprecated = 0;
        if (neo4jAvailable) {
            Set<String> currentIds = currentModels.keySet();
            try {
                for (String dbId : dbIds) {
                    if (!currentIds.contains(dbId)) {
                        toolRegistry.deprecateTool(dbId);
                        deprecated++;
                    }
                }
            } catch (Exception ignored) {}
        }

        // 6. 分类写入图谱
        if (neo4jAvailable) {
            syncCategoriesToGraph(currentModels.values(), dbIds);
        }

        // 7. 重新加载内存缓存（Neo4j 不可用时从 Qdrant 补充））
        try {
            toolRegistry.loadFromNeo4j();
        } catch (Exception e) {
            log.warn("⚠️ 无法从 Neo4j 加载缓存，使用空内存缓存");
        }

        log.info("✅ 工具同步完成: +{} 新增, ~{} 更新, -{} 废弃, 当前 {} 个活跃工具",
                added, updated, deprecated, toolRegistry.countActive());
    }

    /**
     * 新增工具时调用（动态生成工具 / 新增 MCP 服务）。
     */
    public void onNewTool(ToolModel model) {
        toolRegistry.upsertTool(model);
        log.info("📦 新工具已注册: {} [{}]", model.name(), model.type());
    }

    /**
     * 删除工具时调用。
     */
    public void onToolRemoved(String toolId) {
        toolRegistry.deprecateTool(toolId);
    }

    // ==================== 内部方法 ====================

    /**
     * 从 ToolCallback 提取完整 ToolModel。
     * @param isMcp true=来自 MCP，false=来自 Java @Tool
     */
    private ToolModel extractFromToolCallback(ToolCallback tc, boolean isMcp) {
        try {
            ToolDefinition def = tc.getToolDefinition();
            String name = def.name();
            String desc = def.description();
            String type = isMcp ? "MCP" : (generatedToolRegistry.isGeneratedTool(name) ? "GENERATED" : "JAVA");

            // 来源
            String source;
            if (isMcp) {
                source = extractMcpServerName(name);
            } else {
                source = extractClassName(tc);
            }

            // 参数信息
            List<ToolModel.ParamInfo> params = extractParameters(tc);
            String returnType = "String";

            // 如果能获取到 inputSchema，提取返回类型
            String schemaStr = resolveInputSchema(def);
            if (schemaStr != null && schemaStr.contains("return")) {
                returnType = "String (具体类型见 schema)";
            }

            // 唯一 ID
            String id = type + ":" + source + ":" + name;

            return ToolModel.of(id, name, desc, type, source, params, returnType, List.of(), null);

        } catch (Exception e) {
            log.warn("⚠️ 提取工具元数据失败: {}", tc.getToolDefinition().name(), e);
            return null;
        }
    }

    /** 从 ToolDefinition 提取参数列表 */
    @SuppressWarnings("unchecked")
    private List<ToolModel.ParamInfo> extractParameters(ToolCallback tc) {
        List<ToolModel.ParamInfo> params = new ArrayList<>();
        try {
            // 尝试通过反射获取 toolDefinition 的 inputSchema
            Method getDef = tc.getClass().getMethod("getToolDefinition");
            Object def = getDef.invoke(tc);

            // 尝试获取 inputSchema
            try {
                Method schemaMethod = def.getClass().getMethod("inputSchema");
                Object schema = schemaMethod.invoke(def);
                if (schema instanceof Map) {
                    Map<String, Object> schemaMap = (Map<String, Object>) schema;
                    // JSON Schema 格式: {"type":"object","properties":{...},"required":[...]}
                    Map<String, Object> properties = (Map<String, Object>) schemaMap.get("properties");
                    List<String> required = (List<String>) schemaMap.get("required");

                    if (properties != null) {
                        Set<String> requiredSet = required != null ? new HashSet<>(required) : Set.of();
                        for (Map.Entry<String, Object> entry : properties.entrySet()) {
                            Map<String, Object> propDef = (Map<String, Object>) entry.getValue();
                            String propType = String.valueOf(propDef.getOrDefault("type", "string"));
                            String propDesc = String.valueOf(propDef.getOrDefault("description", ""));
                            params.add(new ToolModel.ParamInfo(
                                    entry.getKey(), propType,
                                    requiredSet.contains(entry.getKey()),
                                    propDesc));
                        }
                    }
                }
            } catch (NoSuchMethodException ignored) {
                // Spring AI 版本可能不支持 inputSchema() 方法，fallback
            }
        } catch (Exception e) {
            log.debug("提取参数失败: {}", tc.getToolDefinition().name(), e);
        }

        // Fallback：如果反射提取失败，用 description 里的 @ToolParam 注解信息
        // （已经在 ToolDefinition.description 中包含参数描述）
        if (params.isEmpty()) {
            extractParamsFromDescription(tc.getToolDefinition().description(), params);
        }

        return params;
    }

    /** 从工具描述文本中粗略提取参数（备用方案） */
    private void extractParamsFromDescription(String desc, List<ToolModel.ParamInfo> params) {
        if (desc == null || desc.isBlank()) return;
        // 简单模式：识别 "参数名: 描述" 或 "@param 参数名 描述"
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?:@param|参数)\\s+(\\w+)\\s*[:：]?\\s*([^,，\\n]+)");
        java.util.regex.Matcher m = p.matcher(desc);
        while (m.find()) {
            params.add(new ToolModel.ParamInfo(m.group(1), "String", false, m.group(2).trim()));
        }
    }

    /** 从 ToolCallback 提取类名 */
    private String extractClassName(ToolCallback tc) {
        try {
            // MethodToolCallback 内部持有 target object
            Method getTarget = tc.getClass().getMethod("getToolDefinition");
            Object def = getTarget.invoke(tc);

            // 尝试反射获取底层 Method 信息
            // 备用：用 toString() 提取类名
            String str = tc.toString();
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "target=([\\w.$]+)");
            java.util.regex.Matcher m = p.matcher(str);
            if (m.find()) return m.group(1);
        } catch (Exception ignored) {}
        return "unknown";
    }

    /** 提取 MCP Server 名称（从工具名推断） */
    private String extractMcpServerName(String toolName) {
        // MCP 工具通常格式为 serverName_toolName（但有时没有）
        // 从配置中可知 MCP 连接名为 "robot-mcp"
        return "robot-mcp";
    }

    /** 检查是否需要更新（对比描述和参数变化） */
    private boolean needsUpdate(ToolModel existing, ToolModel current) {
        if (!Objects.equals(existing.description(), current.description())) return true;
        if (existing.parameters().size() != current.parameters().size()) return true;
        // 简单比较参数列表
        Set<String> existingParams = existing.parameters().stream()
                .map(p -> p.name() + ":" + p.type())
                .collect(Collectors.toSet());
        Set<String> currentParams = current.parameters().stream()
                .map(p -> p.name() + ":" + p.type())
                .collect(Collectors.toSet());
        return !existingParams.equals(currentParams);
    }

    /** 尝试获取输入 JSON Schema 字符串 */
    private String resolveInputSchema(ToolDefinition def) {
        try {
            Method m = def.getClass().getMethod("inputSchema");
            Object schema = m.invoke(def);
            return schema != null ? schema.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 分类图谱同步 ====================

    /**
     * 将工具的分类关系写入 Neo4j 图谱。
     * 工具通过 BELONGS_TO 关系关联到 ToolCategory 节点。
     *
     * <p>分类来源：</p>
     * <ul>
     *   <li>从工具名称/描述推断分类（如 "screenshot" → GUI_CONTROL）</li>
     *   <li>MCP 工具归入 MCP_SERVICE 分类</li>
     *   <li>生成工具归入 GENERATED 分类</li>
     * </ul>
     */
    private void syncCategoriesToGraph(Collection<ToolModel> currentModels, Set<String> dbIds) {
        try {
            // 预定义分类（硬编码映射，后续可从配置文件读取）
            Map<String, String> categoryMap = buildCategoryHintMap();

            for (ToolModel model : currentModels) {
                String category = guessCategory(model, categoryMap);
                if (category != null) {
                    toolRegistry.ensureCategory(category, category, "自动分类", 10);
                    toolRegistry.linkCategory(model.id(), category);
                }
            }
            log.info("📁 工具分类已写入 Neo4j 图谱");
        } catch (Exception e) {
            log.warn("⚠️ 分类图谱写入失败: {}", e.getMessage());
        }
    }

    /** 根据工具信息推断分类 */
    private String guessCategory(ToolModel model, Map<String, String> categoryMap) {
        String nameLower = model.name().toLowerCase();
        String descLower = model.description() != null ? model.description().toLowerCase() : "";

        // MCP 工具
        if ("MCP".equals(model.type())) return "MCP_SERVICE";
        // 生成工具
        if ("GENERATED".equals(model.type())) return "GENERATED";

        // 按关键词匹配
        for (Map.Entry<String, String> entry : categoryMap.entrySet()) {
            if (nameLower.contains(entry.getKey()) || descLower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return "GENERAL";
    }

    /** 构建关键词→分类映射表 */
    private Map<String, String> buildCategoryHintMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("screen", "GUI_CONTROL");
        map.put("screenshot", "GUI_CONTROL");
        map.put("capture", "GUI_CONTROL");
        map.put("window", "GUI_CONTROL");
        map.put("click", "GUI_CONTROL");
        map.put("mouse", "GUI_CONTROL");
        map.put("keyboard", "GUI_CONTROL");
        map.put("type", "GUI_CONTROL");
        map.put("ocr", "SCREEN_OCR");
        map.put("file", "FILE_SYSTEM");
        map.put("read", "FILE_SYSTEM");
        map.put("write", "FILE_SYSTEM");
        map.put("browser", "NETWORK");
        map.put("http", "NETWORK");
        map.put("download", "NETWORK");
        map.put("search", "SEARCH");
        map.put("speech", "SPEECH");
        map.put("asr", "SPEECH");
        map.put("tts", "SPEECH");
        map.put("voice", "SPEECH");
        map.put("home", "SMART_HOME");
        map.put("light", "SMART_HOME");
        map.put("device", "SMART_HOME");
        map.put("skill", "SKILL");
        map.put("tool", "TOOL_MANAGEMENT");
        map.put("plan", "TOOL_MANAGEMENT");
        map.put("memory", "MEMORY");
        map.put("episode", "MEMORY");
        map.put("explor", "EXPLORATION");
        return map;
    }
}
