package com.example.myhelper.skill;

import com.example.myhelper.capability.CapabilityLookup;
import com.example.myhelper.capability.QdrantCapabilityRepository;
import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.EntityJson;
import com.example.myhelper.persistence.SnapshotParticipant;
import com.example.myhelper.skill.SkillConsolidationService.*;
import com.example.myhelper.world.model.Embedding;
import com.example.myhelper.world.model.WorldEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static com.example.myhelper.persistence.DualWriteStore.Database.*;
import static org.junit.jupiter.api.Assertions.*;

/** 通过真实统一协调器验证业务仓储状态及补偿，不假称真实数据库联调。 */
class StoredSkillRepositoryTest {
    @Test void springWiresProductionRepositoryToSharedStoreAndEmbedding() {
        Fixture f = new Fixture();
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "shared-wiring", Map.of("qdrant.collection", "skills", "qdrant.vector-size", 2)));
            context.getBeanFactory().registerSingleton("dualWriteStore", f.store);
            context.getBeanFactory().registerSingleton("textEmbedding", new Function<String, Embedding>() {
                @Override public Embedding apply(String text) {
                    assertEquals("spring\nsummary", text);
                    f.embeds++;
                    return new Embedding("embed", "v1", List.of(1.0, 0.0));
                }
            });
            context.register(SkillRepositoryConfiguration.class,
                    com.example.myhelper.capability.CapabilityRepositoryConfiguration.class);
            context.refresh();
            assertEquals(0, f.embeds);
            SkillConsolidationService.Repository repository = context.getBean(SkillConsolidationService.Repository.class);
            Candidate candidate = repository.saveCandidateIfAbsent(candidate("spring"));
            repository.saveReview(candidate, approved());
            Published published = repository.publishApproved(candidate);
            assertEquals(1, f.embeds);
            assertEquals(f.graph.entities.get(published.unitId()), f.index.entities.get(published.unitId()));
            CapabilityLookup.Repository reader = context.getBean(CapabilityLookup.Repository.class);
            assertEquals(candidate.evidence().plan(), reader.load(new CapabilityLookup.Ref("skills", published.unitId(), 1))
                    .orElseThrow().plan());
            List<CapabilityLookup.Candidate> hits = reader.search(new CapabilityLookup.Query(
                    "run", 0, "$entry", "spring\nsummary", Map.of()));
            assertEquals(published.unitId(), hits.getFirst().ref().id());
            assertEquals(2, f.embeds);
            assertEquals("embed", f.index.lastSearch.model());
            assertEquals("v1", f.index.lastSearch.modelVersion());
            assertEquals(List.of(1f, 0f), f.index.lastSearch.vector());
        }
    }

    @Test void candidateSourceDedupReturnsOriginalAndRejectsChangedReview() {
        Fixture f = new Fixture();
        Candidate original = candidate("first");
        assertEquals(original, f.repo.saveCandidateIfAbsent(original));
        assertEquals(original, f.repo.saveCandidateIfAbsent(candidate("different")));
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override public void execute() { f.repo.saveReview(candidate("different"), approved()); }
        });
        assertTrue(f.index.entities.isEmpty());
    }

    @Test void approvedPublicationIsReadableByExistingCapabilityRepository() {
        Fixture f = new Fixture();
        Candidate candidate = f.repo.saveCandidateIfAbsent(candidate("publish"));
        f.repo.saveReview(candidate, approved());
        Published published = f.repo.publishApproved(candidate);
        assertEquals(f.graph.entities.get(published.unitId()), f.index.entities.get(published.unitId()));
        var reader = new QdrantCapabilityRepository(f.store,
                new QdrantCapabilityRepository.Config("skills", NEO4J, "embed", "v1", 2, 10, 0),
                new Function<String, List<Float>>() {
                    @Override public List<Float> apply(String value) { return List.of(1f, 0f); }
                });
        assertEquals(candidate.evidence().plan(), reader.load(new CapabilityLookup.Ref("skills", published.unitId(), 1))
                .orElseThrow().plan());
        assertEquals(published, f.repo.publishApproved(candidate));
        assertEquals(1, f.embeds);
        assertNotEquals(StoredSkillRepository.candidateId(candidate.evidence().source()), published.unitId());
    }

    @Test void missingAndRejectedReviewCannotPublish() {
        Fixture f = new Fixture();
        Candidate candidate = f.repo.saveCandidateIfAbsent(candidate("review"));
        assertThrows(IllegalStateException.class, new Executable() {
            @Override public void execute() { f.repo.publishApproved(candidate); }
        });
        f.repo.saveReview(candidate, new Review("reviewer", false, "failed evidence"));
        assertThrows(IllegalStateException.class, new Executable() {
            @Override public void execute() { f.repo.publishApproved(candidate); }
        });
        assertEquals(0, f.embeds);
        assertTrue(f.index.entities.isEmpty());
    }

    @Test void partialVectorFailureCompensatesAndRetriesFrozenPublicationAfterRestart() {
        Fixture f = new Fixture();
        Candidate candidate = f.repo.saveCandidateIfAbsent(candidate("retry"));
        f.repo.saveReview(candidate, approved());
        f.index.failNextSave = true;
        assertThrows(DualWriteStore.WriteFailure.class, new Executable() {
            @Override public void execute() { f.repo.publishApproved(candidate); }
        });
        assertEquals(1, f.graph.entities.size());
        assertTrue(f.index.entities.isEmpty());
        WorldEntity state = f.graph.entities.get(StoredSkillRepository.candidateId(candidate.evidence().source()));
        assertEquals("PUBLISHING", state.data().get("publicationStatus"));
        String frozen = (String) state.data().get("publicationJson");
        StoredSkillRepository restarted = new StoredSkillRepository(f.store, NEO4J, QDRANT,
                new Function<Candidate, Embedding>() {
                    @Override public Embedding apply(Candidate c) { throw new AssertionError("must reuse frozen vector"); }
                });
        Published published = restarted.publishApproved(candidate);
        assertEquals(frozen, f.json.encode(f.graph.entities.get(published.unitId())));
        assertEquals(1, f.embeds);
    }

    @Test void pendingPublicationRejectsConflictingReviewAndPreservesOriginalApproval() {
        Fixture f = new Fixture();
        Candidate candidate = f.repo.saveCandidateIfAbsent(candidate("freeze"));
        f.repo.saveReview(candidate, approved());
        f.index.failNextSave = true;
        assertThrows(DualWriteStore.WriteFailure.class, new Executable() {
            @Override public void execute() { f.repo.publishApproved(candidate); }
        });
        f.repo.saveReview(candidate, approved());
        assertThrows(IllegalStateException.class, new Executable() {
            @Override public void execute() { f.repo.saveReview(candidate, new Review("other", false, "new decision")); }
        });
        assertNotNull(f.repo.publishApproved(candidate));
    }

    @Test void unconfirmedWriteKeepsFrozenPublicationAndRejectsOrdinaryRetryAfterRestart() {
        Fixture f = new Fixture();
        Candidate candidate = f.repo.saveCandidateIfAbsent(candidate("unconfirmed"));
        f.repo.saveReview(candidate, approved());
        f.index.loseNextWriteResponse = true;
        DualWriteStore.WriteFailure failure = assertThrows(DualWriteStore.WriteFailure.class, new Executable() {
            @Override public void execute() { f.repo.publishApproved(candidate); }
        });
        assertFalse(failure.rollbackCompleted());
        WorldEntity state = f.graph.entities.get(StoredSkillRepository.candidateId(candidate.evidence().source()));
        assertEquals("PUBLISHING", state.data().get("publicationStatus"));
        assertEquals(failure.operationId(), state.data().get("publicationStorageOperationId"));
        assertNotNull(state.data().get("publicationJson"));
        StoredSkillRepository restarted = new StoredSkillRepository(f.store, NEO4J, QDRANT,
                new Function<Candidate, Embedding>() {
                    @Override public Embedding apply(Candidate c) { throw new AssertionError("must reuse frozen vector"); }
                });
        assertThrows(IllegalStateException.class, new Executable() {
            @Override public void execute() { restarted.publishApproved(candidate); }
        });
        assertTrue(f.index.entities.isEmpty());
    }

    @Test void embeddingFailureDoesNotFreezeOrPublishCandidate() {
        Fixture f = new Fixture();
        Candidate candidate = f.repo.saveCandidateIfAbsent(candidate("embedding"));
        f.repo.saveReview(candidate, approved());
        f.failEmbedding = true;
        assertThrows(IllegalStateException.class, new Executable() {
            @Override public void execute() { f.repo.publishApproved(candidate); }
        });
        assertEquals("APPROVED", f.graph.entities.get(StoredSkillRepository.candidateId(candidate.evidence().source()))
                .data().get("publicationStatus"));
        assertTrue(f.index.entities.isEmpty());
    }

    @Test void replayDoesNotEraseStatisticsOrReactivateSuspendedDefinition() {
        Fixture f = new Fixture();
        Candidate candidate = f.repo.saveCandidateIfAbsent(candidate("statistics"));
        f.repo.saveReview(candidate, approved());
        Published published = f.repo.publishApproved(candidate);
        WorldEntity old = f.graph.entities.get(published.unitId());
        WorldEntity counted = new WorldEntity(old.id(), old.worldObjectType(), old.contextUnitType(), old.unitType(),
                old.semanticType(), old.name(), old.text(), old.data(), old.bindings(), old.embedding(), old.revision(),
                old.source(), old.evidence(), old.scope(), old.createdAt(), old.updatedAt(), old.chainPositions(),
                SkillUseStatistics.success(old.statistics()));
        f.store.compareAndSet(NEO4J, old, counted);
        assertEquals(published, f.repo.publishApproved(candidate));
        assertEquals(1, f.graph.entities.get(published.unitId()).statistics().successCount());
        Map<String, Object> data = new LinkedHashMap<>(counted.data());
        data.put("publicationStatus", "SUSPENDED");
        WorldEntity suspended = new WorldEntity(counted.id(), null, null, counted.unitType(), null, counted.name(),
                counted.text(), data, counted.bindings(), counted.embedding(), counted.revision(), counted.source(),
                counted.evidence(), counted.scope(), counted.createdAt(), counted.updatedAt(), counted.chainPositions(), counted.statistics());
        f.store.compareAndSet(NEO4J, counted, suspended);
        assertThrows(IllegalStateException.class, new Executable() {
            @Override public void execute() { f.repo.publishApproved(candidate); }
        });
        assertEquals("SUSPENDED", f.graph.entities.get(published.unitId()).data().get("publicationStatus"));
    }

    private static Review approved() { return new Review("independent-reviewer", true, "accepted trace"); }

    private static Candidate candidate(String name) {
        TaskPlan plan = new TaskPlan("goal", List.of(new TaskPlan.Step("step", "echo", TaskPlan.Kind.TOOL,
                "echo", Map.of("message", "hello"), List.of(), Map.of())));
        return new Candidate(new Evidence(new Source("root", 12, "slice"), "acceptance", "exact sample", plan),
                new Description(name, "summary", "tested parameters only"));
    }

    private static final class Fixture {
        final EntityJson json = new EntityJson();
        final Memory graph = new Memory();
        final Memory index = new Memory();
        final DualWriteStore store = new DualWriteStore(graph, index);
        int embeds;
        boolean failEmbedding;
        final StoredSkillRepository repo = new StoredSkillRepository(store, NEO4J, QDRANT,
                new Function<Candidate, Embedding>() {
                    @Override public Embedding apply(Candidate candidate) {
                        embeds++;
                        if (failEmbedding) { throw new IllegalStateException("embedding offline"); }
                        return new Embedding("embed", "v1", List.of(1.0, 0.0));
                    }
                });
    }

    private static final class Memory extends SnapshotParticipant {
        final Map<String, WorldEntity> entities = new LinkedHashMap<>();
        final EntityJson json = new EntityJson();
        boolean failNextSave;
        /** 模拟服务端已写入但客户端丢失响应。 */
        boolean loseNextWriteResponse;
        com.example.myhelper.persistence.StorageQuery.VectorRequest lastSearch;
        @Override public Optional<WorldEntity> read(String id) { return Optional.ofNullable(entities.get(id)); }
        @Override public List<com.example.myhelper.persistence.StorageQuery.Hit> search(
                com.example.myhelper.persistence.StorageQuery.VectorRequest request) {
            lastSearch = request;
            var hits = new java.util.ArrayList<com.example.myhelper.persistence.StorageQuery.Hit>();
            for (WorldEntity entity : entities.values()) {
                hits.add(new com.example.myhelper.persistence.StorageQuery.Hit(entity, 1));
            }
            return hits;
        }
        @Override public boolean writeConditionally(WorldEntity expected, WorldEntity replacement) {
            if (!Objects.equals(entities.get(replacement.id()), expected)) { return false; }
            entities.put(replacement.id(), json.entity(json.encode(replacement)));
            return true;
        }
        @Override protected void save(List<WorldEntity> values) {
            for (WorldEntity value : values) { entities.put(value.id(), json.entity(json.encode(value))); }
            if (loseNextWriteResponse) {
                loseNextWriteResponse = false;
                throw new java.io.UncheckedIOException(new java.io.IOException("response lost"));
            }
            if (failNextSave) { failNextSave = false; throw new IllegalStateException("partial remote failure"); }
        }
        @Override protected void remove(String id) { entities.remove(id); }
    }
}
