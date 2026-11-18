package com.example.myhelper.capability;

import com.example.myhelper.capability.CapabilityLookup.Candidate;
import com.example.myhelper.capability.CapabilityLookup.Definition;
import com.example.myhelper.capability.CapabilityLookup.Match;
import com.example.myhelper.capability.CapabilityLookup.Query;
import com.example.myhelper.capability.CapabilityLookup.Ref;
import com.example.myhelper.orchestration.TaskPlan;
import com.example.myhelper.skill.SkillChainReliability;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

/** 实际检索与重新选择组件，仓储和链评分只用本地替身。 */
class ChainCapabilityLookupTest {
    @Test
    void highestApplicableChainScoreWinsOverVectorSimilarity() {
        Repository repository = new Repository(List.of(candidate("semantic-first", 0.99), candidate("reliable", 0.7)));
        Scores scores = new Scores(Map.of("semantic-first", 0.5, "reliable", 0.9));
        CapabilityLookup lookup = lookup(repository, scores);
        Match match = lookup.searchAtEntry(query(Map.of("room", "living")));
        assertEquals("reliable", match.selected().orElseThrow().ref().id());
        assertEquals(List.of("reliable", "semantic-first"), ids(match.candidates()));
        assertEquals(1, repository.searches);
        assertEquals(2, scores.calls.size());
    }

    @Test
    void revokedAndOutOfScopeChainsCannotWinWithHighScores() {
        Candidate outOfScope = new Candidate(new Ref("skills", "wrong-room", 1), "goal",
                Map.of("room", "bedroom"), 0.99);
        Repository repository = new Repository(List.of(candidate("revoked", 1), outOfScope, candidate("usable", 0.7)));
        repository.unavailable.add("revoked");
        Scores scores = new Scores(Map.of("revoked", 1.0, "wrong-room", 1.0, "usable", 0.6));
        Match match = lookup(repository, scores).searchAtEntry(query(Map.of("room", "living")));
        assertEquals("usable", match.selected().orElseThrow().ref().id());
        assertFalse(ids(match.candidates()).contains("revoked"));
        assertEquals(List.of("usable"), scores.calls);
    }

    @Test
    void evidencePrecedesUnknownAndTiesUseSemanticThenStableIdentity() {
        Repository repository = new Repository(List.of(candidate("unknown", 1), candidate("z", 0.8),
                candidate("a", 0.8), candidate("low-semantic", 0.3)));
        Scores scores = new Scores(Map.of("z", 0.2, "a", 0.2, "low-semantic", 0.2));
        CapabilityLookup lookup = lookup(repository, scores);
        Match match = lookup.searchAtEntry(query(Map.of()));
        assertEquals(List.of("a", "z", "low-semantic", "unknown"), ids(match.candidates()));
        Match next = lookup.select(query(Map.of()), match, Set.of(new Ref("skills", "a", 1)));
        assertEquals("z", next.selected().orElseThrow().ref().id());
        assertEquals(1, repository.searches, "同批候选重新选择不能重复生成向量或检索");
    }

    @Test
    void unresolvedBindingsAreNotScoredUntilActualArgumentsArrive() {
        Repository repository = new Repository(List.of(candidate("method", 0.8)));
        Scores scores = new Scores(Map.of("method", 0.9));
        CapabilityLookup lookup = lookup(repository, scores);
        TaskPlan.Step unresolved = new TaskPlan.Step("step", "goal", TaskPlan.Kind.TASK, null,
                Map.of("room", "wrong-placeholder"), List.of(),
                Map.of("room", new TaskPlan.InputBinding(TaskPlan.TASK_INPUT_SOURCE, "/room")));
        Query initial = new Query("run", 1, "step", "goal", unresolved.arguments(), unresolved);
        Match found = lookup.searchAtEntry(initial);
        assertTrue(found.selected().isEmpty());
        assertTrue(scores.calls.isEmpty(), "未知来源不能用参数字面部分给分");
        Map<String, Object> actual = Map.of("room", "living");
        TaskPlan.Step resolved = new TaskPlan.Step("step", "goal", TaskPlan.Kind.TASK, null,
                actual, List.of(), Map.of());
        Match selected = lookup.select(new Query("run", 1, "step", "goal", actual, resolved), found, Set.of());
        assertEquals("method", selected.selected().orElseThrow().ref().id());
        assertEquals(actual, scores.lastArguments);
        assertEquals(1, repository.searches);
    }

    @Test
    void cachedDefinitionDoesNotKeepRevokedChainEligibleAndScoresStayFresh() {
        Repository repository = new Repository(List.of(candidate("first", 0.9), candidate("second", 0.8)));
        Scores scores = new Scores(Map.of("first", 0.9, "second", 0.7));
        CapabilityLookup lookup = lookup(repository, scores);
        Query query = query(Map.of());
        Match found = lookup.searchAtEntry(query);
        assertEquals("first", found.selected().orElseThrow().ref().id());
        scores.values.put("second", 1.0);
        assertEquals("second", lookup.select(query, found, Set.of()).selected().orElseThrow().ref().id());
        repository.unavailable.add("second");
        Match current = lookup.select(query, found, Set.of());
        assertEquals("first", current.selected().orElseThrow().ref().id());
        assertFalse(ids(current.candidates()).contains("second"));
        assertEquals(1, repository.searches);
    }

    @Test
    void exactVersionDeduplicationUsesBestSemanticScoreOnce() {
        Repository repository = new Repository(List.of(candidate("same", 0.1), candidate("same", 0.95),
                candidate("another", 0.8)));
        Scores scores = new Scores(Map.of());
        Match found = lookup(repository, scores).searchAtEntry(query(Map.of()));
        assertEquals(List.of("same", "another"), ids(found.candidates()));
        assertEquals(0.95, found.candidates().getFirst().semanticScore());
        assertEquals(List.of("same", "another"), scores.calls);
    }

    @Test
    void springOnlyEnablesLookupWithAnExplicitMatcherAndUsesProvidedChainScorer() {
        Repository repository = new Repository(List.of(candidate("semantic", 1), candidate("reliable", 0.5)));
        try (AnnotationConfigApplicationContext disabled = new AnnotationConfigApplicationContext()) {
            disabled.getBeanFactory().registerSingleton("repository", repository);
            disabled.register(CapabilityRepositoryConfiguration.class);
            disabled.refresh();
            assertTrue(disabled.getBeansOfType(CapabilitySearchPort.class).isEmpty());
        }
        Scores scores = new Scores(Map.of("semantic", 0.1, "reliable", 0.9));
        try (AnnotationConfigApplicationContext enabled = new AnnotationConfigApplicationContext()) {
            enabled.getBeanFactory().registerSingleton("repository", repository);
            enabled.getBeanFactory().registerSingleton("matcher", new ContractMatcher());
            enabled.getBeanFactory().registerSingleton("chainScorer", scores);
            enabled.register(CapabilityRepositoryConfiguration.class);
            enabled.refresh();
            CapabilitySearchPort port = enabled.getBean(CapabilitySearchPort.class);
            assertEquals("reliable", port.searchAtEntry(query(Map.of())).selected().orElseThrow().ref().id());
        }
    }

    private static CapabilityLookup lookup(Repository repository, Scores scores) {
        return new CapabilityLookup(repository, new ContractMatcher(), 8, Duration.ofMinutes(1), scores);
    }

    private static Query query(Map<String, Object> arguments) {
        return new Query("run", 0, CapabilityLookup.ENTRY_QUERY_ID, "goal", arguments);
    }

    private static Candidate candidate(String id, double semanticScore) {
        return new Candidate(new Ref("skills", id, 1), "goal", Map.of(), semanticScore);
    }

    private static List<String> ids(List<Candidate> candidates) {
        List<String> ids = new ArrayList<>();
        for (Candidate candidate : candidates) {
            ids.add(candidate.ref().id());
        }
        return ids;
    }

    private static final class ContractMatcher implements CapabilityLookup.Matcher {
        @Override
        public boolean matches(Query query, Candidate candidate) {
            Object room = candidate.contract().get("room");
            return room == null || room.equals(query.arguments().get("room"));
        }
    }

    private static final class Scores implements SkillChainReliability {
        /** 场景给定的独立链级证据。 */
        private final Map<String, Double> values;
        /** 检查只对已适用的固定版本查询，不负责生产审计。 */
        private final List<String> calls = new ArrayList<>();
        /** 最近一次评分收到的完整参数。 */
        private Map<String, Object> lastArguments;
        private Scores(Map<String, Double> values) {
            this.values = new HashMap<>(values);
        }
        @Override
        public OptionalDouble score(String skillId, long revision, Map<String, Object> arguments) {
            assertEquals(1, revision);
            calls.add(skillId);
            lastArguments = arguments;
            Double value = values.get(skillId);
            return value == null ? OptionalDouble.empty() : OptionalDouble.of(value);
        }
    }

    private static final class Repository implements CapabilityLookup.Repository {
        /** 本次向量召回展开后的固定候选。 */
        private final List<Candidate> candidates;
        /** 模拟权威停用状态，内容缓存不改变此状态。 */
        private final Set<String> unavailable = new HashSet<>();
        /** 区分重新选择与新的语义检索。 */
        private int searches;
        private Repository(List<Candidate> candidates) {
            this.candidates = List.copyOf(candidates);
        }
        @Override
        public List<Candidate> search(Query query) {
            searches++;
            return candidates;
        }
        @Override
        public Optional<Definition> load(Ref ref) {
            TaskPlan.Step step = new TaskPlan.Step("action", "goal", TaskPlan.Kind.TOOL, "read",
                    Map.of(), List.of(), Map.of());
            return Optional.of(new Definition(ref, new TaskPlan("goal", List.of(step))));
        }
        @Override
        public boolean isAvailable(Ref ref) {
            return !unavailable.contains(ref.id());
        }
    }
}
