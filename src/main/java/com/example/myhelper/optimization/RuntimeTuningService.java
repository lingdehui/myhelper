package com.example.myhelper.optimization;

import com.example.myhelper.config.MyHelperProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 运行时配置覆盖层。
 *
 * <p>它不会改 application.yml，也不会接触模型、密钥、权限或安全阈值。所有候选参数由
 * {@link ConfigCatalogService} 自动发现；此类只负责对已通过目录风险判断、且支持热应用的数值覆盖
 * 持久化和回滚。</p>
 */
@Service
public class RuntimeTuningService {

    private static final Logger log = LoggerFactory.getLogger(RuntimeTuningService.class);

    public enum Parameter {
        TOOL_CACHE_SIZE("myhelper.tool-planner.max-cache-size", 200, 2_000, 50),
        ROUTING_CATEGORY_MIN_SCORE("myhelper.tool-planner.routing.category-min-score", 0.40, 0.70, 0.03),
        ROUTING_DIRECT_TOOL_MIN_SCORE("myhelper.tool-planner.routing.direct-tool-min-score", 0.40, 0.70, 0.03),
        WAKE_WORD_MAX_EDIT_DISTANCE("myhelper.voice.wake-word-max-edit-distance", 0, 3, 1);

        private final String key;
        private final double min;
        private final double max;
        private final double step;

        Parameter(String key, double min, double max, double step) {
            this.key = key;
            this.min = min;
            this.max = max;
            this.step = step;
        }

        public String key() { return key; }
        public double min() { return min; }
        public double max() { return max; }
        public double step() { return step; }

        public static Optional<Parameter> fromKey(String key) {
            for (Parameter parameter : values()) if (parameter.key.equals(key)) return Optional.of(parameter);
            return Optional.empty();
        }
    }

    private final MyHelperProperties props;
    private final ReflectionHotConfigApplier hotConfigApplier;
    private final ConfigExperimentService experiments;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PersistedState state = new PersistedState();

    @Value("${myhelper.meta-optimizer.state-path:data/meta-optimizer/state.json}")
    private String statePath;

    public RuntimeTuningService(MyHelperProperties props, ReflectionHotConfigApplier hotConfigApplier,
                                ConfigExperimentService experiments) {
        this.props = props;
        this.hotConfigApplier = hotConfigApplier;
        this.experiments = experiments;
    }

    @PostConstruct
    public synchronized void load() {
        Path file = stateFile();
        if (!Files.exists(file)) return;
        try {
            PersistedState loaded = objectMapper.readValue(file.toFile(), PersistedState.class);
            state.overrides.putAll(loaded.overrides == null ? Map.of() : loaded.overrides);
            state.audit.addAll(loaded.audit == null ? List.of() : loaded.audit);
            while (state.audit.size() > 200) state.audit.remove(0);
            state.pendingTrial = loaded.pendingTrial;
            log.info("元优化器已加载 {} 个运行时覆盖值、{} 条审计记录", state.overrides.size(), state.audit.size());
        } catch (Exception e) {
            log.warn("元优化器状态文件无法读取，使用基线配置: {}", e.getMessage());
        }
    }

    /** 重启后恢复对普通 @Value 数值字段的热覆盖；记录型配置仍由本服务的读取接口提供。 */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void reapplyHotOverrides() {
        for (Map.Entry<String, Double> entry : state.overrides.entrySet()) {
            if (!Parameter.fromKey(entry.getKey()).isPresent()) {
                hotConfigApplier.apply(entry.getKey(), entry.getValue());
            }
        }
    }

    public synchronized int toolPlannerCacheSize() {
        return (int) Math.round(value(Parameter.TOOL_CACHE_SIZE));
    }

    public synchronized int wakeWordMaxEditDistance() {
        return (int) Math.round(value(Parameter.WAKE_WORD_MAX_EDIT_DISTANCE));
    }

    public synchronized MyHelperProperties.ToolPlanner.Routing effectiveRouting(
            MyHelperProperties.ToolPlanner.Routing base) {
        if (base == null) return null;
        return new MyHelperProperties.ToolPlanner.Routing(base.enabled(), base.categoryTopK(),
                value(Parameter.ROUTING_CATEGORY_MIN_SCORE), base.directToolTopK(),
                value(Parameter.ROUTING_DIRECT_TOOL_MIN_SCORE), base.minCandidateTools(), base.maxCandidateTools());
    }

    public synchronized double value(Parameter parameter) {
        Double override = state.overrides.get(parameter.key());
        return clamp(parameter, override == null ? baseValue(parameter) : override);
    }

    public synchronized Optional<Trial> pendingTrial() {
        return Optional.ofNullable(state.pendingTrial);
    }

    /**
     * 对自动发现的数值参数进行一次实验。只有已通过 YAML 风险分类、且实际 Bean 有热应用目标的参数才会执行。
     * 不支持热应用的参数仍可生成实验副本，但不会被偷偷写入运行中进程。
     */
    public synchronized boolean applyDiscovered(ConfigExperimentService.Experiment experiment,
                                                RuntimeMetricsService.Snapshot baseline) {
        if (experiment == null || state.pendingTrial != null) return false;
        String path = experiment.propertyPath();
        double sandboxValue = experiments.loadCandidate(experiment);
        if (Parameter.fromKey(path).isPresent()) {
            Parameter parameter = Parameter.fromKey(path).orElseThrow();
            return applyInternal(path, value(parameter), clamp(parameter, sandboxValue),
                    experiment.reason(), baseline, experiment.file().toString(), state.overrides.containsKey(path), true);
        }
        if (!hotConfigApplier.supports(path)) return false;
        boolean hadPreviousOverride = state.overrides.containsKey(path);
        double oldValue = hadPreviousOverride ? state.overrides.get(path) : experiment.oldValue();
        return applyInternal(path, oldValue, sandboxValue, experiment.reason(), baseline,
                experiment.file().toString(), hadPreviousOverride, false);
    }

    private boolean applyInternal(String path, double oldValue, double newValue, String reason,
                                  RuntimeMetricsService.Snapshot baseline, String sandboxConfigPath,
                                  boolean hadPreviousOverride, boolean builtInReader) {
        if (Double.compare(oldValue, newValue) == 0) return false;
        state.overrides.put(path, newValue);
        if (!builtInReader && !hotConfigApplier.apply(path, newValue)) {
            if (hadPreviousOverride) state.overrides.put(path, oldValue);
            else state.overrides.remove(path);
            return false;
        }
        Trial trial = new Trial(path, oldValue, newValue, reason, System.currentTimeMillis(), baseline,
                sandboxConfigPath, hadPreviousOverride);
        state.pendingTrial = trial;
        addAudit(new AuditRecord(System.currentTimeMillis(), path, oldValue, newValue, "APPLIED", reason,
                sandboxConfigPath));
        persist();
        log.info("🧠 元优化试验已应用: {} {} → {} ({})", path, oldValue, newValue, reason);
        return true;
    }

    public synchronized void acceptPending(String reason) {
        Trial trial = state.pendingTrial;
        if (trial == null) return;
        addAudit(new AuditRecord(System.currentTimeMillis(), trial.parameterPath(), trial.oldValue(), trial.newValue(),
                "ACCEPTED", reason, trial.sandboxConfigPath()));
        state.pendingTrial = null;
        persist();
    }

    /** 试验不达标时恢复调整前的覆盖值；若旧值等于基线，清除覆盖项。 */
    public synchronized void rollbackPending(String reason) {
        Trial trial = state.pendingTrial;
        if (trial == null) return;
        if (!trial.hadPreviousOverride()) {
            state.overrides.remove(trial.parameterPath());
        } else {
            state.overrides.put(trial.parameterPath(), trial.oldValue());
        }
        if (!Parameter.fromKey(trial.parameterPath()).isPresent()) {
            hotConfigApplier.apply(trial.parameterPath(), trial.oldValue());
        }
        addAudit(new AuditRecord(System.currentTimeMillis(), trial.parameterPath(), trial.newValue(), trial.oldValue(),
                "ROLLED_BACK", reason, trial.sandboxConfigPath()));
        state.pendingTrial = null;
        persist();
        log.info("↩️ 元优化试验已回滚: {} ({})", trial.parameterPath(), reason);
    }

    public synchronized Status status() {
        Map<String, Double> effective = new LinkedHashMap<>();
        for (Parameter parameter : Parameter.values()) effective.put(parameter.key(), value(parameter));
        state.overrides.forEach(effective::putIfAbsent);
        return new Status(effective, state.pendingTrial, List.copyOf(state.audit));
    }

    private double baseValue(Parameter parameter) {
        return switch (parameter) {
            case TOOL_CACHE_SIZE -> props.toolPlanner().maxCacheSize();
            case ROUTING_CATEGORY_MIN_SCORE -> props.toolPlanner().routing().categoryMinScore();
            case ROUTING_DIRECT_TOOL_MIN_SCORE -> props.toolPlanner().routing().directToolMinScore();
            case WAKE_WORD_MAX_EDIT_DISTANCE -> props.voice().wakeWordMaxEditDistance();
        };
    }

    private double clamp(Parameter parameter, double value) {
        double bounded = Math.max(parameter.min(), Math.min(parameter.max(), value));
        if (parameter == Parameter.TOOL_CACHE_SIZE || parameter == Parameter.WAKE_WORD_MAX_EDIT_DISTANCE) {
            return Math.rint(bounded);
        }
        return Math.round(bounded * 100.0) / 100.0;
    }

    private Path stateFile() {
        String configured = statePath == null || statePath.isBlank() ? "data/meta-optimizer/state.json" : statePath;
        return Path.of(configured).toAbsolutePath();
    }

    /** 审计可追溯但必须有界，防止长时间运行让状态文件无限膨胀。 */
    private void addAudit(AuditRecord record) {
        state.audit.add(record);
        while (state.audit.size() > 200) state.audit.remove(0);
    }

    private void persist() {
        Path target = stateFile();
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), state);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("元优化器状态无法持久化: {}", e.getMessage());
        }
    }

    /** Jackson 状态对象：仅包含无敏感配置的数值覆盖与审计摘要。 */
    public static class PersistedState {
        public Map<String, Double> overrides = new LinkedHashMap<>();
        public List<AuditRecord> audit = new ArrayList<>();
        public Trial pendingTrial;
    }

    public record Trial(String parameterPath, double oldValue, double newValue, String reason,
                        long startedAt, RuntimeMetricsService.Snapshot baseline, String sandboxConfigPath,
                        boolean hadPreviousOverride) { }
    public record AuditRecord(long timestamp, String parameterPath, double oldValue, double newValue,
                              String outcome, String reason, String sandboxConfigPath) { }
    public record Status(Map<String, Double> effectiveValues, Trial pendingTrial, List<AuditRecord> audit) { }
}
