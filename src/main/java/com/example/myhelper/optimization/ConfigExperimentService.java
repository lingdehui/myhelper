package com.example.myhelper.optimization;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配置沙箱：只复制并修改候选配置文件，永不原地编辑项目配置。
 *
 * <p>副本先通过 YAML 重解析和数值边界校验，随后才允许运行时覆盖层尝试热应用。
 * 这使每一次自我调参都有可查看的“实验配置”，也避免错误候选污染基线文件。</p>
 */
@Service
public class ConfigExperimentService {

    private static final Logger log = LoggerFactory.getLogger(ConfigExperimentService.class);
    private final ConfigCatalogService catalog;
    private final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

    @Value("${myhelper.meta-optimizer.experiment-dir:data/meta-optimizer/experiments}")
    private String experimentDir;

    public ConfigExperimentService(ConfigCatalogService catalog) {
        this.catalog = catalog;
    }

    @PostConstruct
    public void ensureDirectory() {
        try {
            Files.createDirectories(experimentDirectory());
        } catch (IOException e) {
            log.warn("无法创建元优化器实验目录: {}", e.getMessage());
        }
    }

    public Experiment stage(String propertyPath, double candidateValue, String reason) {
        ConfigCatalogService.ConfigParameter parameter = catalog.find(propertyPath)
                .orElseThrow(() -> new IllegalArgumentException("未发现配置参数: " + propertyPath));
        if (parameter.risk() != ConfigCatalogService.Risk.TUNABLE || parameter.type() != ConfigCatalogService.ScalarType.NUMBER) {
            throw new IllegalArgumentException("该参数不可自主试验: " + propertyPath + "（" + parameter.risk() + "）");
        }
        if (!(parameter.rawValue() instanceof Number)) {
            throw new IllegalArgumentException("参数不是数值类型: " + propertyPath);
        }
        Number original = (Number) parameter.rawValue();
        Number candidate = normalise(candidateValue, original, propertyPath);
        try {
            Map<String, Object> copiedConfig = catalog.readSource(parameter);
            setValue(copiedConfig, propertyPath, candidate);
            Path target = experimentDirectory().resolve(System.currentTimeMillis() + "_" + safeName(propertyPath) + ".yml");
            Files.writeString(target, catalog.dump(copiedConfig));
            // 写后重解析，确保副本不会因数据类型/层级错误成为无效 YAML。
            Object verified = yaml.load(Files.readString(target));
            if (!(verified instanceof Map<?, ?>)) throw new IllegalStateException("实验副本不是 YAML 对象");
            log.info("🧪 已生成配置实验副本: {}（{}: {} → {}）", target, propertyPath, original, candidate);
            return new Experiment(propertyPath, original.doubleValue(), candidate.doubleValue(), target, reason);
        } catch (IOException e) {
            throw new IllegalStateException("无法生成配置实验副本: " + e.getMessage(), e);
        }
    }

    /**
     * 从已经落盘并通过 YAML 解析的实验副本读取候选值。
     *
     * <p>热应用以副本文件为事实来源，而不是调用方残留的内存数字；这样审计文件、
     * 实际应用值与后续重启验证使用的是同一份配置。</p>
     */
    @SuppressWarnings("unchecked")
    public double loadCandidate(Experiment experiment) {
        if (experiment == null) throw new IllegalArgumentException("实验配置不能为空");
        Path directory = experimentDirectory().normalize();
        Path file = experiment.file().toAbsolutePath().normalize();
        if (!file.startsWith(directory)) throw new IllegalArgumentException("实验副本不在受控目录中: " + file);
        try {
            Object loaded = yaml.load(Files.readString(file));
            if (!(loaded instanceof Map<?, ?> root)) throw new IllegalArgumentException("实验副本不是 YAML 对象");
            Object current = root;
            for (PathPart part : parsePath(experiment.propertyPath())) {
                if (!(current instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("实验副本缺少参数层级: " + experiment.propertyPath());
                }
                current = ((Map<String, Object>) map).get(part.key());
                if (part.listIndex() != null) current = listItem(current, part.listIndex(), experiment.propertyPath());
            }
            if (!(current instanceof Number number) || !Double.isFinite(number.doubleValue())) {
                throw new IllegalArgumentException("实验副本中的候选值不是有限数值: " + experiment.propertyPath());
            }
            return number.doubleValue();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取实验副本: " + e.getMessage(), e);
        }
    }

    private Number normalise(double candidate, Number original, String propertyPath) {
        if (!Double.isFinite(candidate)) throw new IllegalArgumentException("候选值必须是有限数值: " + propertyPath);
        double base = original.doubleValue();
        if (base >= 0 && base <= 1 && (candidate < 0 || candidate > 1)) {
            throw new IllegalArgumentException("比例/阈值参数必须在 0~1 内: " + propertyPath);
        }
        if (base > 1 && (candidate <= 0 || candidate > Math.max(base * 4, base + 100))) {
            throw new IllegalArgumentException("候选值超出单次安全实验范围: " + propertyPath);
        }
        if (original instanceof Integer || original instanceof Long || original instanceof Short || original instanceof Byte) {
            return (long) Math.rint(candidate);
        }
        return candidate;
    }

    @SuppressWarnings("unchecked")
    private void setValue(Map<String, Object> root, String propertyPath, Number value) {
        List<PathPart> parts = parsePath(propertyPath);
        Object current = root;
        for (int index = 0; index < parts.size() - 1; index++) {
            PathPart part = parts.get(index);
            if (!(current instanceof Map<?, ?> map)) throw new IllegalArgumentException("无效参数层级: " + propertyPath);
            current = ((Map<String, Object>) map).get(part.key());
            if (part.listIndex() != null) current = listItem(current, part.listIndex(), propertyPath);
        }
        PathPart last = parts.get(parts.size() - 1);
        if (!(current instanceof Map<?, ?> map)) throw new IllegalArgumentException("无效参数层级: " + propertyPath);
        Map<String, Object> parent = (Map<String, Object>) map;
        if (last.listIndex() == null) parent.put(last.key(), value);
        else {
            Object list = parent.get(last.key());
            if (!(list instanceof List<?>)) throw new IllegalArgumentException("预期列表参数: " + propertyPath);
            ((List<Object>) list).set(last.listIndex(), value);
        }
    }

    private Object listItem(Object source, int index, String propertyPath) {
        if (!(source instanceof List<?> list) || index < 0 || index >= list.size()) {
            throw new IllegalArgumentException("无效列表参数: " + propertyPath);
        }
        return list.get(index);
    }

    private List<PathPart> parsePath(String path) {
        List<PathPart> parts = new ArrayList<>();
        for (String raw : path.split("\\.")) {
            int bracket = raw.indexOf('[');
            if (bracket < 0) parts.add(new PathPart(raw, null));
            else {
                int end = raw.indexOf(']', bracket);
                if (end < 0) throw new IllegalArgumentException("无效参数路径: " + path);
                parts.add(new PathPart(raw.substring(0, bracket), Integer.parseInt(raw.substring(bracket + 1, end))));
            }
        }
        return parts;
    }

    private Path experimentDirectory() {
        String configured = experimentDir == null || experimentDir.isBlank()
                ? "data/meta-optimizer/experiments" : experimentDir;
        return Path.of(configured).toAbsolutePath();
    }

    private String safeName(String value) { return value.replaceAll("[^a-zA-Z0-9._-]", "_"); }

    private record PathPart(String key, Integer listIndex) { }
    public record Experiment(String propertyPath, double oldValue, double candidateValue, Path file, String reason) { }
}
