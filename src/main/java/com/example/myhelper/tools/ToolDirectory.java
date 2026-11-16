package com.example.myhelper.tools;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.ai.tool.ToolCallback;

/** 由宿主装配的活动工具登记快照，供其他模块按调用名解析实际实现。 */
public final class ToolDirectory {
    /** 每个调用名在本快照中只绑定一个实现。 */
    private final Map<String, Registration> registrations;

    public ToolDirectory(Collection<Registration> registrations) {
        Map<String, Registration> byName = new LinkedHashMap<>();
        for (Registration registration : Objects.requireNonNull(registrations, "registrations")) {
            String name = registration.ref().toolName();
            if (byName.putIfAbsent(name, registration) != null) {
                throw new IllegalArgumentException("工具调用名重复登记：" + name);
            }
        }
        this.registrations = Map.copyOf(byName);
    }

    public Optional<Registration> find(String toolName) {
        requireText(toolName, "toolName");
        return Optional.ofNullable(registrations.get(toolName));
    }

    public record ToolRef(
            /** 当前目录中唯一的工具调用名。 */
            String toolName,
            /** 当前工具实现所属的接入实例标识，独立于接入协议。 */
            String sourceInstance,
            /** 本次绑定的不可变实现版本。 */
            String implementationVersion) {
        public ToolRef {
            requireText(toolName, "toolName");
            requireText(sourceInstance, "sourceInstance");
            requireText(implementationVersion, "implementationVersion");
        }
    }

    /** 由宿主在注册实际回调时同时提供其固定实现身份。 */
    public record Binding(
            /** 本次装配的固定实现身份。 */
            ToolRef ref,
            /** 以该身份注册的实际回调。 */
            ToolCallback callback) {
        public Binding {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(callback, "callback");
            if (!ref.toolName().equals(callback.getToolDefinition().name())) {
                throw new IllegalArgumentException("回调与工具绑定的调用名不一致：" + ref.toolName());
            }
        }
    }

    public record Registration(
            /** 与实际执行目标一致的身份。 */
            ToolRef ref,
            /** 宿主核对过的实现来源，例如上游项目或制品标识。 */
            String source,
            /** 实现是否由大模型生成。 */
            boolean modelGenerated,
            /** 是否为已固化的组合能力。 */
            boolean consolidated,
            /** 实现是否由本项目开发。 */
            boolean projectDeveloped,
            /** 同一实现内以已验证范围编号索引的配置先验。 */
            Map<String, Prior> priorsByRange) {
        public Registration(ToolRef ref, String source, boolean modelGenerated,
                            boolean consolidated, boolean projectDeveloped) {
            this(ref, source, modelGenerated, consolidated, projectDeveloped, Map.of());
        }

        public Registration {
            Objects.requireNonNull(ref, "ref");
            requireText(source, "source");
            priorsByRange = Map.copyOf(priorsByRange);
            for (Map.Entry<String, Prior> entry : priorsByRange.entrySet()) {
                requireText(entry.getKey(), "verifiedRangeId");
                if (consolidated && entry.getValue().weight() > 0) {
                    throw new IllegalArgumentException("固化组合的先验权重必须为零");
                }
            }
        }

        public boolean trustedAtomicTool() {
            return !modelGenerated && !consolidated && !projectDeveloped;
        }

        public Optional<Prior> priorFor(String verifiedRangeId) {
            requireText(verifiedRangeId, "verifiedRangeId");
            return Optional.ofNullable(priorsByRange.get(verifiedRangeId));
        }
    }

    public record Prior(
            /** 初始可靠度，范围为 0 到 1。 */
            double successRate,
            /** 等效样本数，零表示没有先验。 */
            long weight) {
        public Prior {
            if (!Double.isFinite(successRate) || successRate < 0 || successRate > 1 || weight < 0) {
                throw new IllegalArgumentException("先验成功率必须介于 0 和 1 之间，权重必须为非负整数");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
