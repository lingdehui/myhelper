package com.example.myhelper.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 技能配置管理。
 *
 * <p>同时读取全局 {@code skills-config.txt} 与 {@code skills/*.txt}。同名的独立技能文件
 * 会覆盖全局配置，便于把某一项规则独立维护；两种来源都会参与热加载。</p>
 */
@Component
public class SkillConfig {

    private static final Logger log = LoggerFactory.getLogger(SkillConfig.class);
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,80}");

    private volatile List<Skill> skills = List.of();
    private final ConcurrentHashMap<Path, Long> fileMtimes = new ConcurrentHashMap<>();
    private volatile long lastDirScan = 0;
    private volatile Path skillsDir;
    private volatile Path globalSkillsConfig;

    @PostConstruct
    public void init() {
        skillsDir = findSkillsDir();
        globalSkillsConfig = findGlobalSkillsConfig();
        reload();
        log.info("📋 技能配置就绪 ({} 个技能，热加载已开启)", skills.size());
    }

    /** 开发时优先使用源码目录，使工具新建的技能能在下次构建中保留。 */
    private Path findSkillsDir() {
        Path source = Path.of("src", "main", "resources", "skills").toAbsolutePath().normalize();
        if (Files.isDirectory(source)) return source;

        Path external = Path.of("skills").toAbsolutePath().normalize();
        if (Files.isDirectory(external)) return external;

        try {
            var resource = getClass().getClassLoader().getResource("skills");
            if (resource != null) {
                URI uri = resource.toURI();
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    Path path = Paths.get(uri);
                    if (Files.isDirectory(path)) return path;
                }
            }
        } catch (Exception ignored) {
            // 打包在 jar 中时可能无法作为 Path 访问，仍可使用外部 skills/ 目录。
        }
        return null;
    }

    private Path findGlobalSkillsConfig() {
        Path source = Path.of("src", "main", "resources", "skills-config.txt").toAbsolutePath().normalize();
        if (Files.isRegularFile(source)) return source;

        Path external = Path.of("skills-config.txt").toAbsolutePath().normalize();
        if (Files.isRegularFile(external)) return external;

        try {
            var resource = getClass().getClassLoader().getResource("skills-config.txt");
            if (resource != null) {
                URI uri = resource.toURI();
                if ("file".equalsIgnoreCase(uri.getScheme())) {
                    Path path = Paths.get(uri);
                    if (Files.isRegularFile(path)) return path;
                }
            }
        } catch (Exception ignored) {
            // 见 findSkillsDir 的说明。
        }
        return null;
    }

    /** 重新扫描全局和独立技能文件。 */
    private synchronized void reload() {
        try {
            Map<String, Skill> loaded = new LinkedHashMap<>();
            Map<Path, Long> mtimes = new LinkedHashMap<>();

            if (globalSkillsConfig != null && Files.isRegularFile(globalSkillsConfig)) {
                for (Skill skill : parseGlobalSkillFile(globalSkillsConfig)) {
                    loaded.put(skill.name, skill);
                }
                mtimes.put(globalSkillsConfig, Files.getLastModifiedTime(globalSkillsConfig).toMillis());
            }

            if (skillsDir != null && Files.isDirectory(skillsDir)) {
                List<Path> files = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir, "*.txt")) {
                    for (Path file : stream) files.add(file);
                }
                files.sort(Comparator.comparing(path -> path.getFileName().toString()));
                for (Path file : files) {
                    Skill skill = parseSkillFile(file);
                    if (skill != null) {
                        // 独立文件覆盖同名全局条目。
                        loaded.put(skill.name, skill);
                    }
                    mtimes.put(file, Files.getLastModifiedTime(file).toMillis());
                }
            }

            skills = List.copyOf(loaded.values());
            fileMtimes.clear();
            fileMtimes.putAll(mtimes);
        } catch (IOException e) {
            log.error("⚠ 技能配置扫描失败: {}", e.getMessage());
        }
    }

    /** 解析一个独立技能文件：文件名为技能名，首个有效行可声明 keywords:。 */
    private Skill parseSkillFile(Path file) {
        try {
            String name = file.getFileName().toString().replaceFirst("\\.txt$", "");
            List<String> lines = Files.readAllLines(file);
            List<String> keywords = new ArrayList<>();
            StringBuilder instruction = new StringBuilder();
            boolean firstContentLine = true;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (firstContentLine && trimmed.startsWith("keywords:")) {
                    keywords.addAll(parseKeywords(trimmed.substring("keywords:".length())));
                } else {
                    appendLine(instruction, trimmed);
                }
                firstContentLine = false;
            }
            return createSkill(name, keywords, instruction.toString());
        } catch (IOException e) {
            log.warn("无法读取技能文件 {}: {}", file, e.getMessage());
            return null;
        }
    }

    /** 解析 [name] / keywords: / instruction: 形式的全局技能文件。 */
    private List<Skill> parseGlobalSkillFile(Path file) {
        List<Skill> parsed = new ArrayList<>();
        try {
            String currentName = null;
            List<String> keywords = new ArrayList<>();
            StringBuilder instruction = new StringBuilder();

            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
                    addIfValid(parsed, currentName, keywords, instruction);
                    currentName = trimmed.substring(1, trimmed.length() - 1).trim();
                    keywords = new ArrayList<>();
                    instruction = new StringBuilder();
                } else if (currentName != null && trimmed.startsWith("keywords:")) {
                    keywords.addAll(parseKeywords(trimmed.substring("keywords:".length())));
                } else if (currentName != null && trimmed.startsWith("instruction:")) {
                    String firstInstruction = trimmed.substring("instruction:".length()).trim();
                    if (!firstInstruction.isEmpty()) appendLine(instruction, firstInstruction);
                } else if (currentName != null) {
                    appendLine(instruction, trimmed);
                }
            }
            addIfValid(parsed, currentName, keywords, instruction);
        } catch (IOException e) {
            log.warn("无法读取全局技能配置 {}: {}", file, e.getMessage());
        }
        return parsed;
    }

    private void addIfValid(List<Skill> parsed, String name, List<String> keywords, StringBuilder instruction) {
        Skill skill = createSkill(name, keywords, instruction.toString());
        if (skill != null) parsed.add(skill);
    }

    private List<String> parseKeywords(String raw) {
        List<String> result = new ArrayList<>();
        for (String keyword : raw.split(",")) {
            String value = keyword.trim();
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }

    private void appendLine(StringBuilder builder, String line) {
        if (builder.length() > 0) builder.append('\n');
        builder.append(line);
    }

    private Skill createSkill(String name, List<String> keywords, String instruction) {
        if (name == null || name.isBlank() || instruction == null || instruction.isBlank()) return null;
        List<String> effectiveKeywords = keywords == null || keywords.isEmpty() ? List.of(".*") : keywords;
        Skill skill = new Skill();
        skill.name = name;
        skill.instruction = instruction.trim();
        skill.patterns = new ArrayList<>();
        for (String keyword : effectiveKeywords) {
            try {
                skill.patterns.add(".*".equals(keyword)
                        ? Pattern.compile(".*")
                        : Pattern.compile(keyword));
            } catch (Exception invalidRegex) {
                skill.patterns.add(Pattern.compile(Pattern.quote(keyword)));
            }
        }
        return skill;
    }

    private void checkReload() {
        long currentScan = System.currentTimeMillis();
        if (currentScan - lastDirScan < 2_000) return;
        lastDirScan = currentScan;

        try {
            Set<Path> currentFiles = new HashSet<>();
            if (globalSkillsConfig != null && Files.isRegularFile(globalSkillsConfig)) {
                currentFiles.add(globalSkillsConfig);
            }
            if (skillsDir != null && Files.isDirectory(skillsDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir, "*.txt")) {
                    for (Path file : stream) currentFiles.add(file);
                }
            }

            boolean changed = currentFiles.size() != fileMtimes.size();
            for (Path file : currentFiles) {
                long mtime = Files.getLastModifiedTime(file).toMillis();
                if (!Objects.equals(fileMtimes.get(file), mtime)) {
                    changed = true;
                    break;
                }
            }
            if (changed) {
                log.info("🔄 检测到技能配置变更，热加载中...");
                reload();
                log.info("📋 技能已更新 ({} 个技能)", skills.size());
            }
        } catch (IOException e) {
            log.debug("检查技能配置变更失败: {}", e.getMessage());
        }
    }

    @Tool(description = "创建或更新可热加载的本地技能规则。keywords 使用逗号分隔，instruction 为要注入给 AI 的完整规则。")
    public synchronized String createOrUpdateSkill(
            @ToolParam(description = "技能名，只能使用字母、数字、下划线和连字符") String name,
            @ToolParam(description = "触发关键词或正则表达式，多个用逗号分隔") String keywords,
            @ToolParam(description = "匹配后注入 AI 的规则文本") String instruction) {
        if (name == null || !SKILL_NAME_PATTERN.matcher(name).matches()) {
            return "技能名无效：只能使用字母、数字、下划线和连字符（最长 80 个字符）。";
        }
        if (instruction == null || instruction.isBlank()) return "技能规则不能为空。";

        try {
            if (skillsDir == null) {
                skillsDir = Path.of("skills").toAbsolutePath().normalize();
                Files.createDirectories(skillsDir);
            }
            Path target = skillsDir.resolve(name + ".txt").normalize();
            if (!target.getParent().equals(skillsDir)) return "技能文件路径无效。";
            String content = "keywords: " + (keywords == null || keywords.isBlank() ? ".*" : keywords.trim())
                    + System.lineSeparator() + instruction.trim() + System.lineSeparator();
            Files.writeString(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            reload();
            return "已保存技能 “" + name + "”，现有 " + skills.size() + " 个技能，规则将立即生效。";
        } catch (IOException e) {
            log.error("保存技能 {} 失败", name, e);
            return "保存技能失败：" + e.getMessage();
        }
    }

    /** 根据用户输入匹配技能，返回所有匹配的指令文本（多技能累加）。 */
    public String getInstructions(String userInput) {
        checkReload();
        if (userInput == null || userInput.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (Skill skill : skills) {
            for (Pattern pattern : skill.patterns) {
                if (pattern.matcher(userInput).find()) {
                    sb.append(skill.instruction).append('\n');
                    break;
                }
            }
        }
        return sb.toString().trim();
    }

    public List<String> getMatchedSkillNames(String userInput) {
        checkReload();
        if (userInput == null || userInput.isBlank()) return List.of();
        List<String> names = new ArrayList<>();
        for (Skill skill : skills) {
            for (Pattern pattern : skill.patterns) {
                if (pattern.matcher(userInput).find()) {
                    names.add(skill.name);
                    break;
                }
            }
        }
        return names;
    }

    public List<String> getSkillNames() {
        checkReload();
        return skills.stream().map(skill -> skill.name).toList();
    }

    private static class Skill {
        String name;
        String instruction;
        List<Pattern> patterns;
    }
}
