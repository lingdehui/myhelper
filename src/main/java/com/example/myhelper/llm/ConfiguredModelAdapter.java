package com.example.myhelper.llm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.ai.chat.model.ChatModel;

/** Resolves configured clients and owns logical model bindings for each root run. */
public final class ConfiguredModelAdapter implements ModelAdapter, Function<String, ChatModel> {
    public record ModelConfig(
            /** Stable reference to this configured connection. */
            String modelRef,
            /** Client configured by the application for this connection. */
            ChatModel client,
            /** Whether this connection can serve new requests. */
            boolean enabled,
            /** Whether the client and model support tool calling. */
            boolean supportsTools) {
        public ModelConfig {
            requireId(modelRef);
            Objects.requireNonNull(client, "client");
        }
    }

    /** Stable candidate order supplied by application assembly. */
    private final List<ModelConfig> candidates;
    /** 配置引用索引，用于校验根模型并解析客户端。 */
    private final Map<String, ModelConfig> models;
    /** 可选的外部标签与画像读取入口。 */
    private final ModelRoutingProvider routing;
    /** Active sessions indexed by root run identity. */
    private final Map<String, RunSession> runs = new ConcurrentHashMap<>();

    public ConfiguredModelAdapter(List<ModelConfig> configs) {
        this(configs, null);
    }

    public ConfiguredModelAdapter(List<ModelConfig> configs, ModelRoutingProvider routing) {
        candidates = List.copyOf(configs);
        this.routing = routing;
        Map<String, ModelConfig> index = new HashMap<>();
        for (ModelConfig config : candidates) {
            if (index.putIfAbsent(config.modelRef(), config) != null) {
                throw new IllegalArgumentException(LlmTexts.MODEL_CONFIG_DUPLICATE + config.modelRef());
            }
        }
        models = Map.copyOf(index);
    }

    @Override
    public Session openRun(RunRequest request) {
        ModelConfig config = enabledModel(request.modelRef());
        // 模型能力由实际调用协议声明；业务工具为空时仍可能使用规划控制工具。
        if (!compatible(config, request.requirements())) {
            throw new IllegalArgumentException(LlmTexts.MODEL_CONFIG_TOOLS + config.modelRef());
        }
        // 把同一份不可变协议要求交给会话，后续分支不能放宽条件。
        RunSession session = new RunSession(request);
        if (runs.putIfAbsent(request.rootRunId(), session) != null) {
            throw new IllegalStateException(LlmTexts.MODEL_RUN_DUPLICATE + request.rootRunId());
        }
        return session;
    }

    @Override
    public ChatModel apply(String modelRef) {
        return resolve(modelRef);
    }

    @Override
    public ChatModel resolve(String modelRef) {
        return enabledModel(modelRef).client();
    }

    private ModelConfig enabledModel(String modelRef) {
        requireId(modelRef);
        ModelConfig config = models.get(modelRef);
        if (config == null || !config.enabled()) {
            throw new IllegalArgumentException(LlmTexts.MODEL_CONFIG_UNAVAILABLE + modelRef);
        }
        return config;
    }

    private static boolean compatible(ModelConfig config, Set<Requirement> requirements) {
        // 禁用配置不能服务新根运行或新分支。
        if (!config.enabled()) {
            return false;
        }
        // 每项显式协议能力都必须满足，不能用是否有普通工具作为替代条件。
        for (Requirement requirement : requirements) {
            // 保留穷尽分支；以后新增能力时必须补齐对应配置检查。
            boolean supported = switch (requirement) {
                case TOOL_CALLING -> config.supportsTools();
            };
            // 任一能力不满足就排除候选。
            if (!supported) {
                return false;
            }
        }
        // 显式空集合表示本运行没有额外协议能力要求。
        return true;
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(LlmTexts.MODEL_ID_REQUIRED);
        }
    }

    private final class RunSession implements Session {
        /** Identity used to unregister this session at close. */
        private final String runId;
        /** 满足本次运行协议的连接引用，保留配置顺序。 */
        private final List<String> compatibleModels;
        /** Root assignment retained until close. */
        private final Binding root;
        /** Current bindings for this run. */
        private final Map<String, Binding> active = new HashMap<>();
        /** Number of active bindings per model reference. */
        private final Map<String, Integer> references = new HashMap<>();
        /** Original immutable batch indexed by each child identity, including released children. */
        private final Map<String, List<Binding>> batches = new HashMap<>();
        /** Terminal lifecycle flag. */
        private boolean closed;

        private RunSession(RunRequest request) {
            runId = request.rootRunId();
            List<String> compatibleRefs = new ArrayList<>();
            for (ModelConfig candidate : candidates) {
                if (compatible(candidate, request.requirements())) {
                    compatibleRefs.add(candidate.modelRef());
                }
            }
            compatibleModels = List.copyOf(compatibleRefs);
            root = new Binding(request.rootBranchId(), null, request.modelRef(), Assignment.ROOT);
            register(root);
        }

        @Override
        public synchronized Binding root() {
            ensureOpen();
            return root;
        }

        @Override
        public synchronized Binding binding(String branchId) {
            ensureOpen();
            Binding binding = active.get(branchId);
            if (binding == null) {
                throw new IllegalArgumentException(LlmTexts.MODEL_BRANCH_INACTIVE + branchId);
            }
            return binding;
        }

        @Override
        public synchronized List<Binding> fork(Binding parent, List<String> childIds) {
            return commitFork(parent, List.copyOf(childIds), Map.of());
        }

        @Override
        public List<Binding> forkWithTasks(Binding parent, List<BranchRequest> children) {
            List<BranchRequest> requests = List.copyOf(children);
            List<String> ids = new ArrayList<>();
            for (BranchRequest child : requests) {
                ids.add(child.branchId());
            }
            if (routing == null) {
                return fork(parent, ids);
            }
            synchronized (this) {
                List<Binding> previous = validateFork(parent, ids);
                if (previous != null) {
                    return previous;
                }
            }

            // 在会话锁外读取画像。任何读取异常都发生在登记之前。
            Map<String, List<String>> preferences = new HashMap<>();
            for (BranchRequest child : requests) {
                if (child.tasks().isEmpty()) {
                    continue;
                }
                ModelRoutingProvider.Route route = Objects.requireNonNull(
                        routing.route(child), LlmTexts.MODEL_ROUTING_RESULT_REQUIRED);
                if (!route.labels().isEmpty()) {
                    preferences.put(child.branchId(), route.preferredModelRefs());
                }
            }
            // 读取画像期间可能已有其他分叉、释放或关闭。提交时重新核对状态。
            synchronized (this) {
                return commitFork(parent, ids, preferences);
            }
        }

        private List<Binding> validateFork(Binding parent, List<String> ids) {
            ensureOpen();
            Objects.requireNonNull(parent, "parent");
            if (!parent.equals(binding(parent.id()))) {
                throw new IllegalArgumentException(LlmTexts.MODEL_PARENT_MISMATCH + parent.id());
            }
            if (ids.size() < 2 || new HashSet<>(ids).size() != ids.size()) {
                throw new IllegalArgumentException(LlmTexts.MODEL_FORK_CHILDREN);
            }
            for (String id : ids) {
                requireId(id);
                if (root.id().equals(id)) {
                    throw new IllegalArgumentException(LlmTexts.MODEL_FORK_CONFLICT + id);
                }
                List<Binding> previous = batches.get(id);
                if (previous != null) {
                    return replay(parent, ids, previous);
                }
            }
            return null;
        }

        private List<Binding> commitFork(Binding parent, List<String> ids, Map<String, List<String>> preferences) {
            List<Binding> previous = validateFork(parent, ids);
            if (previous != null) {
                return previous;
            }
            List<Binding> result = new ArrayList<>();
            Set<String> available = new LinkedHashSet<>(compatibleModels);
            available.removeAll(references.keySet());
            for (String id : ids) {
                String modelRef = firstAvailable(available, preferences.getOrDefault(id, List.of()));
                if (modelRef == null) {
                    result.add(new Binding(id, parent.id(), parent.modelRef(), Assignment.INHERITED));
                } else {
                    available.remove(modelRef);
                    result.add(new Binding(id, parent.id(), modelRef, Assignment.OTHER_MODEL));
                }
            }

            List<Binding> batch = List.copyOf(result);
            for (Binding child : batch) {
                register(child);
                batches.put(child.id(), batch);
            }
            return batch;
        }

        private List<Binding> replay(Binding parent, List<String> ids, List<Binding> batch) {
            if (ids.size() != batch.size()) {
                throw new IllegalArgumentException(LlmTexts.MODEL_FORK_CONFLICT + ids);
            }
            for (int i = 0; i < ids.size(); i++) {
                Binding child = batch.get(i);
                if (!ids.get(i).equals(child.id()) || !parent.id().equals(child.parentId())
                        || !active.containsKey(child.id())) {
                    throw new IllegalArgumentException(LlmTexts.MODEL_FORK_CONFLICT + ids);
                }
            }
            return batch;
        }

        private String firstAvailable(Set<String> available, List<String> preferredRefs) {
            for (String modelRef : preferredRefs) {
                if (available.contains(modelRef)) {
                    return modelRef;
                }
            }
            if (available.isEmpty()) {
                return null;
            }
            return available.iterator().next();
        }

        private void register(Binding binding) {
            active.put(binding.id(), binding);
            references.put(binding.modelRef(), references.getOrDefault(binding.modelRef(), 0) + 1);
        }

        @Override
        public synchronized boolean release(String branchId) {
            ensureOpen();
            Binding binding = active.get(branchId);
            if (binding == null || root.id().equals(branchId)) {
                return false;
            }
            for (Binding child : active.values()) {
                if (branchId.equals(child.parentId())) {
                    throw new IllegalStateException(LlmTexts.MODEL_BRANCH_HAS_CHILDREN + branchId);
                }
            }
            active.remove(branchId);
            String modelRef = binding.modelRef();
            int remaining = references.get(modelRef) - 1;
            if (remaining == 0) {
                references.remove(modelRef);
            } else {
                references.put(modelRef, remaining);
            }
            return true;
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            active.clear();
            references.clear();
            batches.clear();
            runs.remove(runId, this);
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException(LlmTexts.MODEL_RUN_CLOSED + runId);
            }
        }
    }
}

