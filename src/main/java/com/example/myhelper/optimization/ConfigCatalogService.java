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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 所有 YAML 配置叶子的自发现目录。
 *
 * <p>目录与“是否允许自动调节”分离：任何参数都可被发现和审计，但包含密钥、连接、路径、
 * 权限等风险字段的参数永远不会进入自主试验。这样新增配置无需修改元优化器源码。</p>
 */
@Service
public class ConfigCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ConfigCatalogService.class);
    private static final List<String> CONFIG_SUFFIXES = List.of(".yml", ".yaml");

    private final AtomicReference<List<ConfigParameter>> parameters = new AtomicReference<>(List.of());
    private final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));

    @Value("${myhelper.meta-optimizer.config-root:src/main/resources}")
    private String configRoot;

    @PostConstruct
    public void refresh() {
        Path root = rootPath();
        if (!Files.isDirectory(root)) {
            log.warn("元优化器配置目录不存在，无法建立参数目录: {}", root);
            parameters.set(List.of());
            return;
        }
        List<ConfigParameter> found = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isYaml)
                    .sorted()
                    .forEach(path -> found.addAll(readFile(root, path)));
        } catch (IOException e) {
            log.warn("扫描元优化器配置目录失败: {}", e.getMessage());
        }
        found.sort(Comparator.comparing(ConfigParameter::path));
        parameters.set(List.copyOf(found));
        log.info("🧠 元优化器已发现 {} 个配置参数（{} 个可实验）", found.size(),
                found.stream().filter(parameter -> parameter.risk() == Risk.TUNABLE).count());
    }

    public List<ConfigParameter> list() { return parameters.get(); }

    public Optional<ConfigParameter> find(String propertyPath) {
        if (propertyPath == null || propertyPath.isBlank()) return Optional.empty();
        return parameters.get().stream().filter(parameter -> parameter.path().equals(propertyPath)).findFirst();
    }

    /** 读取源配置为可变 Map，供实验副本服务修改；绝不返回或改写源文件。 */
    @SuppressWarnings("unchecked")
    Map<String, Object> readSource(ConfigParameter parameter) throws IOException {
        Object loaded = yaml.load(Files.readString(parameter.sourceFile()));
        if (loaded instanceof Map<?, ?> map) return new LinkedHashMap<>((Map<String, Object>) map);
        return new LinkedHashMap<>();
    }

    String dump(Map<String, Object> content) { return yaml.dump(content); }
    Path rootPath() { return Path.of(configRoot == null || configRoot.isBlank() ? "src/main/resources" : configRoot).toAbsolutePath(); }

    private List<ConfigParameter> readFile(Path root, Path source) {
        try {
            Object loaded = yaml.load(Files.readString(source));
            if (!(loaded instanceof Map<?, ?> map)) return List.of();
            List<ConfigParameter> found = new ArrayList<>();
            flatten(source, root.relativize(source).toString().replace('\\', '/'), "", map, found);
            return found;
        } catch (Exception e) {
            log.warn("跳过无法解析的配置文件 {}: {}", source, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private void flatten(Path source, String relativeSource, String prefix, Object value,
                         List<ConfigParameter> out) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                flatten(source, relativeSource, prefix.isEmpty() ? key : prefix + "." + key, entry.getValue(), out);
            }
            return;
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                flatten(source, relativeSource, prefix + "[" + index + "]", list.get(index), out);
            }
            return;
        }
        ScalarType type = scalarType(value);
        Risk risk = classify(prefix, type);
        out.add(new ConfigParameter(prefix, relativeSource, source, type, risk, displayValue(value, risk), value));
    }

    private boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return CONFIG_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    private ScalarType scalarType(Object value) {
        if (value instanceof Boolean) return ScalarType.BOOLEAN;
        if (value instanceof Number) return ScalarType.NUMBER;
        return ScalarType.STRING;
    }

    private Risk classify(String propertyPath, ScalarType type) {
        String key = propertyPath.toLowerCase(Locale.ROOT);
        if (key.matches(".*(api[-_]?key|password|access[-_]?token|secret|credential|private[-_]?key).*")) {
            return Risk.PROTECTED_SECRET;
        }
        if (key.matches(".*(url|uri|host|port|base-url|model|collection|vector-size|window-size|path|directory|import|allowed-domains).*")) {
            return Risk.RESTART_OR_EXTERNAL;
        }
        if (key.matches(".*(delete|remove|cleanup|permission|allow|deny|enabled|authentication).*")) {
            return Risk.REQUIRES_APPROVAL;
        }
        return type == ScalarType.NUMBER ? Risk.TUNABLE : Risk.OBSERVE_ONLY;
    }

    private String displayValue(Object value, Risk risk) {
        return risk == Risk.PROTECTED_SECRET ? "<redacted>" : String.valueOf(value);
    }

    public enum ScalarType { NUMBER, BOOLEAN, STRING }
    public enum Risk { TUNABLE, OBSERVE_ONLY, REQUIRES_APPROVAL, RESTART_OR_EXTERNAL, PROTECTED_SECRET }
    public record ConfigParameter(String path, String source, Path sourceFile, ScalarType type,
                                  Risk risk, String displayValue, Object rawValue) { }
}
