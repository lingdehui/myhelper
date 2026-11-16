package com.example.myhelper.skill;

import com.example.myhelper.gate.SkillWriteGate;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.EntityJson;
import com.example.myhelper.skill.SkillConsolidationService.Candidate;
import com.example.myhelper.skill.SkillConsolidationService.Published;
import com.example.myhelper.skill.SkillConsolidationService.Review;
import com.example.myhelper.skill.SkillConsolidationService.Source;
import com.example.myhelper.world.model.Embedding;
import com.example.myhelper.world.model.UnitType;
import com.example.myhelper.world.model.WorldEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 候选、独立审核及发布的生产仓储；数据库访问全部经统一存储。
 * 应用共享一个实例；跨库发布沿用 DualWriteStore 的单写者约束。
 * 条件写保护候选状态，不能据此宣称跨进程发布或跨库原子可见。
 */
public final class StoredSkillRepository implements SkillConsolidationService.Repository {
    /** 已登记但尚未确认完成或恢复的跨库写入。 */
    private static final String PUBLICATION_WRITE_ATTEMPT = "publicationWriteAttempt";
    /** 统一数据库入口，也负责发布失败时两库快照补偿。 */
    private final DualWriteStore store;
    /** 调用方明确指定的权威状态与发布实体数据库。 */
    private final DualWriteStore.Database authority;
    /** 调用方明确指定的向量索引数据库。 */
    private final DualWriteStore.Database index;
    /** 外部配置的实际嵌入生成器，生成失败不会宣布发布成功。 */
    private final Function<Candidate, Embedding> embed;
    /** 实体序列化同时固定待发布载荷，失败重试不重新生成内容。 */
    private final EntityJson json = new EntityJson();
    /** 候选合同不含额外时间类型，按已有 record 还原。 */
    private final ObjectMapper mapper = new ObjectMapper();
    /** 保存前沿用技能写入校验。 */
    private final SkillWriteGate gate = new SkillWriteGate();

    public StoredSkillRepository(DualWriteStore store, DualWriteStore.Database authority,
                                 DualWriteStore.Database index, Function<Candidate, Embedding> embed) {
        // 显式装配依赖，不从 WorldEntity 类型或 embedding 推导位置。
        this.store = Objects.requireNonNull(store);
        // 当前候选条件写需要 Neo4j，索引发布需要 Qdrant。
        if (authority != DualWriteStore.Database.NEO4J || index != DualWriteStore.Database.QDRANT) {
            // 不将缺失条件写能力静默降为覆盖写。
            throw new IllegalArgumentException("技能仓储需要显式 NEO4J 权威来源和 QDRANT 索引目标");
        }
        // 保存业务层已经选择的来源与目标。
        this.authority = authority;
        // 存储层只执行这个参数。
        this.index = index;
        // 真实嵌入由应用提供，不能用空向量冒充索引就绪。
        this.embed = Objects.requireNonNull(embed);
    }

    @Override
    public Optional<Candidate> findCandidate(Source source) {
        // 来源身份固定，不需要用分页首条近似去重。
        Optional<WorldEntity> state = store.read(authority, candidateId(source));
        // 不存在就是尚未生成候选。
        if (state.isEmpty()) {
            // 保留 Repository 的缺失语义。
            return Optional.empty();
        }
        // 还原实际入库内容，验证来源没有串号。
        Candidate candidate = decodeCandidate(state.get());
        // 稳定 ID 冲突或损坏必须报告。
        if (!source.equals(candidate.evidence().source())) {
            // 不能把另一来源的候选交给调用方。
            throw new IllegalStateException("候选来源与存储身份不一致");
        }
        // 返回已保存候选而不是重新生成的描述。
        return Optional.of(candidate);
    }

    @Override
    public synchronized Candidate saveCandidateIfAbsent(Candidate candidate) {
        // 沿用必填字段校验，再固定嵌套 JSON 内容。
        gate.checkCandidate(candidate);
        // 模型输出和调用方后续修改不能改变已登记候选。
        Candidate fixed = mapper.convertValue(json.parse(json.encode(candidate)), Candidate.class);
        // 同一来源始终使用同一候选 ID。
        Map<String, Object> data = new LinkedHashMap<>();
        // 内容通过统一 JSON 合同保存。
        data.put("candidate", mapper.convertValue(fixed, Map.class));
        // 明确业务状态，候选不会被能力检索当作已发布技能。
        data.put("publicationStatus", "CANDIDATE");
        // 区分业务记录用途，不引入新的世界实体类型。
        data.put("recordKind", "SKILL_CANDIDATE");
        // 首次创建与内容保存由数据库单实体事务完成。
        store.writeIfAbsent(authority, entity(candidateId(fixed.evidence().source()), fixed, data, null, Instant.now()));
        // 即使并发生成了不同描述，也返回真正赢得首次创建的候选。
        return findCandidate(fixed.evidence().source()).orElseThrow();
    }

    @Override
    public synchronized void saveReview(Candidate candidate, Review review) {
        // 审核针对完整候选，不能只凭来源身份批准另一个描述。
        gate.checkReview(candidate, review);
        // 有界重读解决并发条件更新，冲突不覆盖其他状态。
        for (int attempt = 0; attempt < 32; attempt++) {
            // 每次使用当前完整快照参与 CAS。
            WorldEntity old = requireCandidate(candidate);
            // 相同审核的重试保持幂等。
            if (same(old.data().get("review"), review)) {
                // 发布期间重复送达同一审核不改动版本。
                return;
            }
            // 发布载荷固定之后不允许审核与实际发布内容脱节。
            if (old.data().containsKey("publicationJson")) {
                // 新审核需要另一个候选/修订，不覆盖已经冻结的发布。
                throw new IllegalStateException("候选已进入发布，不能替换审核");
            }
            // 只改变审核及状态，保留候选、引用和其他业务字段。
            Map<String, Object> data = new LinkedHashMap<>(old.data());
            // 审核结论与依据同一原子状态保存。
            data.put("review", mapper.convertValue(review, Map.class));
            // 拒绝审核不能进入可发布分支。
            data.put("publicationStatus", review.approved() ? "APPROVED" : "REJECTED");
            // 完整快照比较覆盖同 revision 下的字段变化。
            if (store.compareAndSet(authority, old, update(old, data))) {
                // 只有实际提交成功才返回。
                return;
            }
        }
        // 持续争用交给调用方重试，不伪造审核已保存。
        throw new IllegalStateException("候选审核并发冲突，请重试");
    }

    @Override
    public synchronized Published publishApproved(Candidate candidate) {
        // 本实例串行发布，保持协调器已有单写者补偿边界。
        gate.checkCandidate(candidate);
        // 冻结一次发布载荷，包括向量；失败恢复沿用原载荷。
        WorldEntity state = stagePublication(candidate);
        // 整个实体及其引用都通过统一编解码还原。
        WorldEntity publication = json.entity((String) state.data().get("publicationJson"));
        // 已完成发布之后统计可能增加；重复发布不得重新覆盖这些事实。
        if ("PUBLISHED".equals(state.data().get("publicationStatus"))) {
            // 核对当前发布定义仍在两边，不把后续统计更新当作需要重放的旧快照。
            checkPublishedDefinition(publication, authority);
            // 索引定义也须仍可读，缺失由显式修复流程处理。
            checkPublishedDefinition(publication, index);
            // 返回同一发布版本，不执行任何覆盖写。
            return new Published(publication.id(), publication.revision());
        }
        if (state.data().containsKey(PUBLICATION_WRITE_ATTEMPT)) {
            throw new IllegalStateException("已有发布写入待核对：" + state.data().get(PUBLICATION_WRITE_ATTEMPT));
        }
        String writeAttempt = null;
        // 已发布重放只在两边仍完整时直接返回。
        if (!storedPublicationMatches(publication)) {
            // 先登记本次写入，提交结果未知或进程退出时保留阻止重写的凭据。
            writeAttempt = UUID.randomUUID().toString();
            Map<String, Object> pending = new LinkedHashMap<>(state.data());
            pending.put(PUBLICATION_WRITE_ATTEMPT, writeAttempt);
            WorldEntity writing = update(state, pending);
            if (!store.compareAndSet(authority, state, writing)) {
                throw new IllegalStateException("发布派发状态已变化，请重新读取");
            }
            // 同一批明确列出两个目标，调用统一提交及失败补偿。
            Map<DualWriteStore.Database, List<WorldEntity>> targets = new LinkedHashMap<>();
            // 权威发布内容仍由上层明确选择存储归属。
            targets.put(authority, List.of(publication));
            // 索引保存同一完整实体，不丢失弱引用。
            targets.put(index, List.of(publication));
            // 失败时保留 PUBLISHING 及固定载荷，异常原样向上报告。
            try {
                store.writeBatch(targets);
            } catch (DualWriteStore.WriteFailure failure) {
                try {
                    Map<String, Object> outcome = new LinkedHashMap<>(writing.data());
                    if (failure.rollbackCompleted()) {
                        outcome.remove(PUBLICATION_WRITE_ATTEMPT);
                    } else {
                        outcome.put("publicationStorageOperationId", failure.operationId());
                    }
                    if (!store.compareAndSet(authority, writing, update(writing, outcome))) {
                        throw new IllegalStateException("发布写入状态已变化");
                    }
                } catch (RuntimeException recordFailure) {
                    failure.addSuppressed(recordFailure);
                }
                throw failure;
            }
            // 只有实际读回两边才能将候选标为发布完成。
            if (!storedPublicationMatches(publication)) {
                // 不能把部分写入或查询缺失视为索引就绪。
                throw new IllegalStateException("发布写入后未能读回完整双库实体");
            }
        }
        // 发布完成标识使用条件更新，不覆盖其他审核状态。
        for (int attempt = 0; attempt < 32; attempt++) {
            // 状态提交失败后重试仍读取相同冻结载荷。
            WorldEntity current = requireCandidate(candidate);
            // 防止载荷被非协作写入者替换。
            if (!Objects.equals(current.data().get("publicationJson"), state.data().get("publicationJson"))) {
                // 此时不做无条件回滚，避免抹掉其他已知状态。
                throw new IllegalStateException("发布载荷在提交期间发生变化");
            }
            // 重复提交直接返回原 Unit 身份和修订。
            if ("PUBLISHED".equals(current.data().get("publicationStatus"))) {
                // revision 是发布实体修订，而不是候选状态版本。
                return new Published(publication.id(), publication.revision());
            }
            if (!Objects.equals(current.data().get(PUBLICATION_WRITE_ATTEMPT), writeAttempt)) {
                throw new IllegalStateException("发布确认不属于本次写入");
            }
            // 同一状态中保存索引就绪结果。
            Map<String, Object> data = new LinkedHashMap<>(current.data());
            // 不在两库写入前宣布成功。
            data.put("publicationStatus", "PUBLISHED");
            data.remove(PUBLICATION_WRITE_ATTEMPT);
            // 成功后方可向上层交付发布版本。
            if (store.compareAndSet(authority, current, update(current, data))) {
                // 重试和首次发布使用相同返回身份。
                return new Published(publication.id(), publication.revision());
            }
        }
        // 两库内容可能已存在；保留可重试状态并明确报告。
        throw new IllegalStateException("发布确认并发冲突，请重试");
    }

    private WorldEntity stagePublication(Candidate candidate) {
        // 有界重试避免持续争用无限阻塞工作线程。
        for (int attempt = 0; attempt < 32; attempt++) {
            // 核对完整候选，包含已验收计划与描述。
            WorldEntity old = requireCandidate(candidate);
            // 只有独立批准的同一候选才能发布。
            Review review = mapper.convertValue(old.data().get("review"), Review.class);
            // 缺审核和明确拒绝均不能放行。
            if (review == null || !review.approved()) {
                // 保存候选并不隐含发布许可。
                throw new IllegalStateException("候选尚未审核通过");
            }
            // 已冻结载荷不重复调用嵌入服务。
            if (old.data().containsKey("publicationJson")) {
                // 包括前次双写或最后确认失败后的恢复。
                return old;
            }
            // 精确嵌入失败在任何发布写入之前向上报告。
            Embedding embedding = Objects.requireNonNull(embed.apply(candidate), "发布嵌入不能为空");
            // 与现有能力读取仓储共享 executionPlan/publicationStatus 合同。
            Map<String, Object> publishedData = new LinkedHashMap<>();
            // 原计划与参数绑定完整保存。
            publishedData.put("executionPlan", mapper.convertValue(candidate.evidence().plan(), Map.class));
            // 发布状态由本业务仓储设置，存储层不解释。
            publishedData.put("publicationStatus", "PUBLISHED");
            // 保留审核和来源证据，便于追溯。
            publishedData.put("candidateId", old.id());
            // 以原始字段保存验收依据，不伪造 EntityRef 类型。
            publishedData.put("acceptanceRef", candidate.evidence().acceptanceRef());
            // 独立审核决定随固定发布载荷保存。
            publishedData.put("review", old.data().get("review"));
            // 失败限制也属于能力定义，不仅供模型生成时使用。
            publishedData.put("limitations", candidate.description().limitations());
            // 稳定来源生成独立发布身份，不覆盖候选记录。
            WorldEntity publication = entity("skill-published:" + sourceKey(candidate.evidence().source()),
                    candidate, publishedData, embedding, Instant.now());
            // 状态和固定完整载荷在单实体中一并提交。
            Map<String, Object> data = new LinkedHashMap<>(old.data());
            // 失败重放必须继续这个载荷，不能重新随机生成时间或向量。
            data.put("publicationJson", json.encode(publication));
            // 不提前宣称已发布。
            data.put("publicationStatus", "PUBLISHING");
            // 固化此次尝试的权威候选状态。
            WorldEntity next = update(old, data);
            // 审核若并发改变，当前向量不能绕过最新决定。
            if (store.compareAndSet(authority, old, next)) {
                // 后续双写复用此完整快照。
                return next;
            }
        }
        // 不能因并发失去一致审核依据仍继续发布。
        throw new IllegalStateException("发布准备并发冲突，请重试");
    }

    private boolean storedPublicationMatches(WorldEntity publication) {
        // 先读权威内容再读索引，两边都必须匹配冻结载荷。
        Optional<WorldEntity> authoritative = store.read(authority, publication.id());
        // 所有读取经统一来源参数，不直接访问客户端。
        Optional<WorldEntity> indexed = store.read(index, publication.id());
        // 同名不同内容不能用发布重试覆盖。
        if ((authoritative.isPresent() && !same(authoritative.get(), publication))
                || (indexed.isPresent() && !same(indexed.get(), publication))) {
            // 防止稳定 ID 冲突或后续状态更新被无条件覆盖。
            throw new IllegalStateException("发布身份已有不同内容，拒绝覆盖");
        }
        // 部分缺失可用固定载荷按统一双写重试。
        return authoritative.isPresent() && indexed.isPresent();
    }

    private void checkPublishedDefinition(WorldEntity expected, DualWriteStore.Database source) {
        // 缺失不返回一个虚假的可用版本。
        WorldEntity actual = store.read(source, expected.id()).orElseThrow();
        // 发布资格、身份和定义必须匹配，统计及其他追加事实不被旧快照覆盖。
        if (actual.revision() != expected.revision() || actual.unitType() != UnitType.PLAN_STEP
                || !"PUBLISHED".equals(actual.data().get("publicationStatus"))
                || !Objects.equals(actual.data().get("candidateId"), expected.data().get("candidateId"))
                || !same(actual.data().get("executionPlan"), expected.data().get("executionPlan"))) {
            // 停用、修订或内容变更不能靠旧发布请求重新启用。
            throw new IllegalStateException("已发布定义发生变化，拒绝旧发布重放");
        }
    }

    private WorldEntity requireCandidate(Candidate candidate) {
        // 仅查询明确的权威来源。
        WorldEntity stored = store.read(authority, candidateId(candidate.evidence().source())).orElseThrow(
                new java.util.function.Supplier<IllegalStateException>() {
                    @Override
                    public IllegalStateException get() {
                        // 不允许凭一个未保存的候选直接审核或发布。
                        return new IllegalStateException("候选尚未保存");
                    }
                });
        // 比较 JSON 结构，避免数字反序列化或字段顺序改变比较结论。
        if (!same(decodeCandidate(stored), candidate)) {
            // 禁止审核批准 A 后用同来源的 B 发布。
            throw new IllegalArgumentException("请求与已保存的完整候选不一致");
        }
        // 返回参与条件写的完整当前状态。
        return stored;
    }

    private Candidate decodeCandidate(WorldEntity state) {
        // 发布实体与候选状态不能被当成同一种持久合同。
        if (!"SKILL_CANDIDATE".equals(state.data().get("recordKind"))) {
            // 精确 ID 冲突必须显式报告。
            throw new IllegalStateException("存储实体不是技能候选记录");
        }
        // 还原全部候选字段，不重新生成计划或描述。
        return Objects.requireNonNull(mapper.convertValue(state.data().get("candidate"), Candidate.class));
    }

    private boolean same(Object left, Object right) {
        // 对象字段顺序不影响完整结构比较。
        return json.parse(json.encode(left)).equals(json.parse(json.encode(right)));
    }

    private WorldEntity update(WorldEntity old, Map<String, Object> data) {
        // 只更改业务状态版本、数据与更新时间，保留全部其他实体字段及引用。
        return new WorldEntity(old.id(), old.worldObjectType(), old.contextUnitType(), old.unitType(), old.semanticType(),
                old.name(), old.text(), data, old.bindings(), old.embedding(), Math.incrementExact(old.revision()),
                old.source(), old.evidence(), old.scope(), old.createdAt(), Instant.now(), old.chainPositions(), old.statistics());
    }

    private WorldEntity entity(String id, Candidate candidate, Map<String, Object> data, Embedding embedding, Instant now) {
        // 候选和发布均沿用组合能力类型；类型不决定数据库位置。
        return new WorldEntity(id, null, null, UnitType.PLAN_STEP, null,
                candidate.description().name(), candidate.description().summary(), data, List.of(), embedding, 1,
                candidate.evidence().source().rootRunId(), List.of(),
                Map.of("verifiedScope", candidate.evidence().verifiedScope()), now, now, List.of(), null);
    }

    public static String candidateId(Source source) {
        // 暴露稳定身份便于业务诊断，不暴露数据库查询语句。
        return "skill-candidate:" + sourceKey(source);
    }

    private static String sourceKey(Source source) {
        // 使用结构化编码防止分隔符歧义，固定来源同一 ID。
        return UUID.nameUUIDFromBytes(new EntityJson().encode(Objects.requireNonNull(source))
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
