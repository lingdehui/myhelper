package com.example.desktopbrain.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 技能配置管理：从 src/main/resources/skills/ 目录扫描技能文件。
 * 支持热加载：每次查询时检测文件变更，自动重新加载，无需重启。
 *
 * 文件格式（一个文件一个技能）：
 *   第一行：keywords: 关键词1, 关键词2
 *   其余行：规则内容
 *
 * 技能名 = 文件名（不含 .txt）
 */
@Component
public class SkillConfig {

    private static final Logger log = LoggerFactory.getLogger(SkillConfig.class);

    /** 缓存的技能列表 */
    private volatile List<Skill> skills = new ArrayList<>();

    /** 记录每个文件的最后修改时间，用于热加载检测 */
    private final ConcurrentHashMap<Path, Long> fileMtimes = new ConcurrentHashMap<>();

    /** 技能目录最后扫描时间 */
    private volatile long lastDirScan = 0;
    private volatile Path skillsDir;

    @PostConstruct
    public void init() {
        // 尝试找到 skills 目录
        skillsDir = findSkillsDir();
        if (skillsDir == null) {
            log.info("ℹ️ 未找到 skills/ 目录，技能配置为空");
            return;
        }
        reload();
        log.info("📋 技能配置就绪 ({} 个技能，热加载已开启)", skills.size());
    }

    /** 查找 skills 目录（开发期用 classpath，打包后用外部路径） */
    private Path findSkillsDir() {
        try {
            // 尝试 classpath 下的 skills 目录
            URI uri = getClass().getClassLoader().getResource("skills").toURI();
            if (uri != null) {
                Path p = Paths.get(uri);
                if (Files.isDirectory(p)) return p;
            }
        } catch (Exception ignored) {}

        // 备选：项目根目录下的 skills
        Path fallback = Path.of("src", "main", "resources", "skills").toAbsolutePath();
        if (Files.isDirectory(fallback)) return fallback;

        // 再备选：当前目录下的 skills
        Path cwd = Path.of("skills").toAbsolutePath();
        if (Files.isDirectory(cwd)) return cwd;

        return null;
    }

    /**
     * 重新扫描 skills 目录加载所有技能文件
     */
    private synchronized void reload() {
        if (skillsDir == null) return;

        try {
            List<Skill> loaded = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir, "*.txt")) {
                for (Path file : stream) {
                    Skill skill = parseSkillFile(file);
                    if (skill != null) {
                        loaded.add(skill);
                        try {
                            fileMtimes.put(file, Files.getLastModifiedTime(file).toMillis());
                        } catch (IOException ignored) {}
                    }
                }
            }
            skills = loaded;
        } catch (IOException e) {
            log.error("⚠ 技能目录扫描失败: {}", e.getMessage());
        }
    }

    /** 解析单个技能文件 */
    private Skill parseSkillFile(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.isEmpty()) return null;

            // 文件名即技能名
            String name = file.getFileName().toString().replace(".txt", "");

            List<String> keywords = new ArrayList<>();
            StringBuilder instruction = new StringBuilder();
            boolean firstLine = true;

            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

                if (firstLine && trimmed.startsWith("keywords:")) {
                    String kwStr = trimmed.substring("keywords:".length()).trim();
                    for (String kw : kwStr.split(",")) {
                        String k = kw.trim();
                        if (!k.isEmpty()) keywords.add(k);
                    }
                    firstLine = false;
                } else {
                    // 后续行都是规则内容
                    if (firstLine) {
                        // 第一行没有 keywords: 前缀，整个文件都是规则（始终匹配）
                        keywords.add(".*");
                        instruction.append(trimmed);
                    } else {
                        if (instruction.length() > 0) instruction.append("\n");
                        instruction.append(trimmed);
                    }
                    firstLine = false;
                }
            }

            // 如果整个文件都没有 keywords 行，整个文件作为规则，始终匹配
            if (keywords.isEmpty() && instruction.length() > 0) {
                keywords.add(".*");
            }

            if (instruction.length() == 0) return null;

            Skill skill = new Skill();
            skill.name = name;
            skill.instruction = instruction.toString().trim();
            skill.patterns = new ArrayList<>();
            for (String kw : keywords) {
                if (kw.equals(".*")) {
                    skill.patterns.add(Pattern.compile(".*"));
                } else if (kw.matches(".*[\\\\.*+?\\[\\]{}()|^$].*")) {
                    skill.patterns.add(Pattern.compile(kw));
                } else {
                    skill.patterns.add(Pattern.compile(Pattern.quote(kw)));
                }
            }
            return skill;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 检查是否有文件变更，有则热加载
     */
    private void checkReload() {
        if (skillsDir == null) return;

        try {
            long currentScan = System.currentTimeMillis();
            // 每 2 秒最多检查一次目录变更
            if (currentScan - lastDirScan < 2000) return;
            lastDirScan = currentScan;

            // 检查目录下是否有新文件或文件变更
            boolean changed = false;
            Set<Path> currentFiles = new HashSet<>();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(skillsDir, "*.txt")) {
                for (Path file : stream) {
                    currentFiles.add(file);
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    Long cached = fileMtimes.get(file);
                    if (cached == null || mtime > cached) {
                        changed = true;
                    }
                }
            }

            // 有文件被删除？
            if (!changed && currentFiles.size() != fileMtimes.size()) {
                changed = true;
            }

            if (changed) {
                log.info("🔄 检测到技能文件变更，热加载中...");
                reload();
                log.info("📋 技能已更新 ({} 个技能)", skills.size());
            }
        } catch (IOException ignored) {}
    }

    /** 根据用户输入匹配技能，返回所有匹配的指令文本（多技能累加） */
    public String getInstructions(String userInput) {
        checkReload();  // 每次查询都检查热加载
        if (userInput == null || userInput.isEmpty()) return "";
        List<Skill> current = skills;  // volatile 快照

        StringBuilder sb = new StringBuilder();
        for (Skill skill : current) {
            for (Pattern p : skill.patterns) {
                if (p.matcher(userInput).find()) {
                    sb.append(skill.instruction).append("\n");
                    break;
                }
            }
        }
        return sb.toString();
    }

    /** 获取当前输入匹配到的技能名列表 */
    public List<String> getMatchedSkillNames(String userInput) {
        List<Skill> current = skills;
        List<String> names = new ArrayList<>();
        if (userInput == null || userInput.isEmpty()) return names;
        for (Skill skill : current) {
            for (Pattern p : skill.patterns) {
                if (p.matcher(userInput).find()) {
                    names.add(skill.name);
                    break;
                }
            }
        }
        return names;
    }

    /** 获取所有技能名列表（供调试用） */
    public List<String> getSkillNames() {
        List<Skill> current = skills;
        List<String> names = new ArrayList<>();
        for (Skill s : current) names.add(s.name);
        return names;
    }

    private static class Skill {
        String name;
        String instruction;
        List<Pattern> patterns;
    }
}
