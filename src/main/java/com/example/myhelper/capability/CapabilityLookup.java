package com.example.myhelper.capability;

import com.example.myhelper.llm.LlmTexts;

import com.example.myhelper.orchestration.TaskPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 独立的固化检索与有限内容缓存；每次 search 都调用仓储，不缓存未命中的搜索。 */
public final class CapabilityLookup implements CapabilitySearchPort {
    /** 查询身份保留字：根入口目标，不是计划中的步骤 ID。 */
    public static final String ENTRY_QUERY_ID = "$entry";
    /** 查询身份保留字：当前模型计划的总体目标。 */
    public static final String WHOLE_PLAN_QUERY_ID = "$plan";
    /** HashMap 常规初始桶容量；实际缓存条数由构造参数 capacity 限制。 */
    private static final int INITIAL_CACHE_BUCKETS = 16;
    /** 标准装载因子，控制哈希表扩容；不是命中率阈值。 */
    private static final float CACHE_LOAD_FACTOR = 0.75f;

    /** 仅用于标记检索发生的位置；入口阶段只执行一次。 */
    public enum SearchPhase {
        /** 根用户请求刚进入。 */
        ENTRY,
        /** 模型刚提交一个新计划。 */
        AFTER_PLAN
    }

    /** 数据来源由仓储实现明确指定，不能把搜索当作已有缓存筛选。 */
    private final Repository repository;
    /** 语义候选是否满足目标合同由外部匹配策略决定，不默认按相似度放行。 */
    private final Matcher matcher;
    /** 热点内容条数上限；当前计划另持有自己的不可变绑定。 */
    private final int capacity;
    /** 内容 TTL，避免缓存无限存活。 */
    private final long ttlNanos;
    /** 仅缓存固定版本内容，不缓存执行资格或参数。 */
    private final Map<Ref, Cached> cache = new LinkedHashMap<>(INITIAL_CACHE_BUCKETS, CACHE_LOAD_FACTOR, true);

    // 装配查询源和业务匹配策略，缓存容量及存活期由调用方提供。
    public CapabilityLookup(Repository repository, Matcher matcher, int capacity, java.time.Duration ttl) {
        // 保存数据库检索端口；每次 search 都访问此端口。
        this.repository = Objects.requireNonNull(repository);
        // 保存合同匹配策略，不默认把向量相近当作可执行。
        this.matcher = Objects.requireNonNull(matcher);
        // 内容缓存必须有正容量和正存活时间。
        if (capacity < 1 || ttl.isNegative() || ttl.isZero()) {
            // 参数或协议不符合约定，立即报错，不继续执行。
            throw new IllegalArgumentException("缓存限制必须为正");
        }
        // 保存实际缓存条数上限，与哈希桶初始大小不同。
        this.capacity = capacity;
        // 使用单调时钟纳秒计算过期，避免系统校时影响。
        this.ttlNanos = ttl.toNanos();
    }

    @Override
    // 真实检索实现已启用；外部仓储异常仍由原调用路径上抛。
    public boolean enabled() {
        // 启用不表示一定有候选，也不表示每次检索都会成功。
        return true;
    }

    /** 每个根任务入口只调用一次；不由每轮规划循环重复调用。 */
    @Override
    public Match searchAtEntry(Query query) {
        // 入口只规定调用时机；底层查询与规划后查询复用同一实现。
        return search(query);
    }

    /** 每次模型产生计划后都查仓储；查询来自模型的总目标与各子目标。 */
    @Override
    public PlanMatches searchAfterPlanning(String runId, long revision, TaskPlan plan, Map<String, Object> input) {
        // 用模型给出的总体目标重新查库，保留字区分整条计划查询。
        Match whole = search(new Query(runId, revision, WHOLE_PLAN_QUERY_ID, plan.goal(), input));
        // 建立当前计划独有的子步骤匹配表。
        Map<String, Match> steps = new LinkedHashMap<>();
        // 每个 LLM 子目标都独立查库，而不是只过滤入口候选。
        for (TaskPlan.Step step : plan.steps()) {
            // 把新候选及选中定义绑定到对应步骤，并传递原始步骤合同。
            steps.put(step.id(), search(new Query(runId, revision, step.id(), step.goal(), step.arguments(), step)));
        }
        // 返回本次计划的只读匹配表，供后续执行直接查用。
        return new PlanMatches(whole, Map.copyOf(steps));
    }

    // 先获取新候选，再做业务合同匹配；缓存只用于加载内容。
    private Match search(Query query) {
        // 每次都查询仓储，避免缓存未命中遮蔽新发布能力。
        List<Candidate> candidates = new ArrayList<>(repository.search(query));
        // 按仓储候选顺序检查适用性，不直接按描述执行。
        var remaining = candidates.iterator();
        while (remaining.hasNext()) {
            Candidate candidate = remaining.next();
            // 只有匹配策略明确确认合同适用，才加载固定版本定义。
            if (matcher.matches(query, candidate)) {
                Optional<Definition> definition = load(candidate.ref());
                if (definition.isPresent()) {
                    return new Match(List.copyOf(candidates), definition);
                }
                // 候选在匹配和加载之间失效，跳过并从模型可见候选中移除。
                remaining.remove();
            }
        }
        // 未明确匹配时保留候选供后续模型参考，不伪造可执行能力。
        return new Match(List.copyOf(candidates), Optional.empty());
    }

    /** 执行前重新核对发布/停用状态，缓存不代表本次可执行。 */
    @Override
    public boolean isAvailable(Ref ref) {
        // 实时检查权威发布状态；内容缓存不代表持续允许执行。
        return repository.isAvailable(ref);
    }

    // 加载明确命名空间、ID、revision 的能力内容。
    private Optional<Definition> load(Ref ref) {
        // 只在操作 LRU 表时持锁，不持锁执行数据库查询。
        synchronized (cache) {
            // 读取命中项也会更新 LRU 访问次序，因此同样需要锁。
            Cached value = cache.get(ref);
            // 存在且尚未过期才可复用已冻结的内容。
            if (value != null && System.nanoTime() - value.loadedAt() < ttlNanos) {
                // 返回固定版本内容；执行前资格仍由另一路检查。
                return Optional.of(value.definition());
            }
        }
        // 数据库访问不持有全局缓存锁，避免一个慢查询阻塞所有任务。
        Optional<Definition> loaded = repository.load(ref);
        if (loaded.isEmpty()) {
            return Optional.empty();
        }
        Definition definition = loaded.get();
        // 仓储不能用当前新版本内容冒充所要求的旧版本。
        if (!ref.equals(definition.ref())) {
            // 报告当前运行/服务状态异常，交给上层处理。
            throw new IllegalStateException(LlmTexts.CAPABILITY_REVISION_MISMATCH);
        }
        // 只在操作 LRU 表时持锁，不持锁执行数据库查询。
        synchronized (cache) {
            // 缓存成功读取的版本和加载时刻，不缓存查询空结果或错误。
            cache.put(ref, new Cached(definition, System.nanoTime()));
            // 超过条数上限时淘汰最久未访问项。
            while (cache.size() > capacity) {
                // 访问顺序 LinkedHashMap 的第一项就是最久未访问项。
                cache.remove(cache.keySet().iterator().next());
            }
        }
        // 返回已验证版本的不可变执行定义。
        return loaded;
    }

    /** 查看内容缓存条数；只读统计也要使用同一把缓存锁。 */
    public int cachedCount() {
        // LRU 表不能与其他线程的读取/淘汰操作交错。
        synchronized (cache) {
            // 返回当前条数，不返回可被调用方修改的缓存本身。
            return cache.size();
        }
    }

    // 数据源端口，具体数据库及来源由装配方决定。
    public interface Repository {
        /** 按目标查数据库候选，必须有候选数量限制。 */
        List<Candidate> search(Query query);
        /** 读取固定版本，不能取得时返回空。 */
        Optional<Definition> load(Ref ref);
        /** 查询权威执行资格，异常向上传递，不默认放行。 */
        boolean isAvailable(Ref ref);
    }

    @FunctionalInterface
    // 业务合同适配端口；明确失败或无法确认时不直接执行。
    public interface Matcher {
        /** 参数/范围未能确认时返回 false，保留候选供模型参考。 */
        boolean matches(Query query, Candidate candidate);
    }

    public record Ref(
            /** 仓储命名空间，防止不同来源同 ID 缓存串用。 */
            String namespace,
            /** 世界模型能力编号。 */
            String id,
            /** 固定内容修订。 */
            long revision) {
        // 检查固定版本引用，避免不同来源同名能力串用。
        public Ref {
            // 命名空间不能为空，参与缓存隔离。
            TaskPlan.requireText(namespace, "namespace");
            // 能力必须有可精确读取的稳定身份。
            TaskPlan.requireText(id, "id");
            // 真实能力修订从 1 开始；它与运行入口的计划版本 0 不同。
            if (revision < 1) {
                // 参数或协议不符合约定，立即报错，不继续执行。
                throw new IllegalArgumentException("revision 必须为正");
            }
        }
    }

    public record Query(
            /** 所属任务。 */
            String runId,
            /** 0 为入口，此后为模型规划版本。 */
            long planRevision,
            /** $entry、$plan 或步骤编号。 */
            String stepId,
            /** 原始用户目标或 LLM 生成的任务目标。 */
            String goal,
            /** 当前已知参数；未绑定参数不伪造。 */
            Map<String, Object> arguments,
            /** 子节点原始定义，供匹配器核对工具名、类别和参数来源；入口/整条查询为空。 */
            TaskPlan.Step step) {
        // 查询输入使用不可变快照，未知参数不能在这里凭空补齐。
        public Query {
            // 查询必须有明确目标文本。
            TaskPlan.requireText(goal, "query.goal");
            // 固定本次已知参数，避免执行线程修改正在匹配的数据。
            arguments = TaskPlan.immutableMap(arguments);
        }
        // 提供入口和整条查询的便捷构造；这些查询没有原始子步骤。
        public Query(String runId, long planRevision, String stepId, String goal, Map<String, Object> arguments) {
            // 以空 step 表示入口/整条查询，不伪造普通工具步骤。
            this(runId, planRevision, stepId, goal, arguments, null);
        }
    }

    public record Candidate(
            /** 可精确加载的能力引用。 */
            Ref ref,
            /** 提供给匹配策略和模型的描述。 */
            String description,
            /** 参数、适用范围与审核依据。 */
            Map<String, Object> contract) {
        // 候选只携带引用和合同，不代表已经通过执行门禁。
        public Candidate {
            // 候选或定义必须指向一个明确能力版本。
            Objects.requireNonNull(ref);
            // 冻结候选合同中的嵌套数据，避免多个匹配任务相互改写。
            contract = TaskPlan.immutableMap(contract);
        }
    }

    public record Definition(
            /** 固定修订。 */
            Ref ref,
            /** 已审核的组合执行定义；所有参数从本次输入绑定。 */
            TaskPlan plan) {
        // 固定版本内容必须同时包含身份和可执行计划。
        public Definition {
            // 候选或定义必须指向一个明确能力版本。
            Objects.requireNonNull(ref);
            // 拒绝把没有执行计划的能力当作空流程执行。
            Objects.requireNonNull(plan);
        }
    }

    public record Match(
            /** 本次新检索的候选。 */
            List<Candidate> candidates,
            /** 已经通过匹配策略的固定定义，执行前仍需门禁。 */
            Optional<Definition> selected) { }

    public record PlanMatches(
            /** 本次整个计划的匹配。 */
            Match whole,
            /** 本次计划缓存，仅对应同一个规划版本。 */
            Map<String, Match> steps) { }

    private record Cached(
            /** 不可变能力内容。 */
            Definition definition,
            /** 单调时钟下的加载时刻。 */
            long loadedAt) { }
}
