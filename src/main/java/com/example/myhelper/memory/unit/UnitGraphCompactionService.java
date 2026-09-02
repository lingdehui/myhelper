package com.example.myhelper.memory.unit;

import com.example.myhelper.config.MyHelperProperties;
import com.example.myhelper.memory.graph.UnitRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 定期从旧 Unit 图中提取重复的直接原子路径，并将父图改写为共享 PLAN_STEP。 */
@Service
public class UnitGraphCompactionService {
    private static final Logger log = LoggerFactory.getLogger(UnitGraphCompactionService.class);
    private static final int MIN_PATH = 2;
    private static final int MAX_PATH = 6;
    private static final int MIN_OCCURRENCES = 2;

    private final UnitRepository repository;
    private final UnitStore unitStore;
    private final MyHelperProperties props;
    private final UnitPathGeneralizer generalizer = new UnitPathGeneralizer();
    private final ObjectMapper mapper = new ObjectMapper();

    public UnitGraphCompactionService(UnitRepository repository, UnitStore unitStore,
                                      MyHelperProperties props) {
        this.repository = repository;
        this.unitStore = unitStore;
        this.props = props;
    }

    @Scheduled(cron = "${myhelper.unit-graph-compaction.cron:0 15 3 * * ?}")
    public void scheduledCompact() {
        if (!props.autonomous().enabled()) return;
        try {
            int rewritten = compactOnce();
            log.info("Unit 图归纳完成: 重写 {} 个父路径", rewritten);
        } catch (Exception e) {
            log.warn("Unit 图归纳失败，本轮停止: {}", e.getMessage());
        }
    }

    /** 每轮只提取一个收益最高的公共路径，下一轮基于新图继续，避免重叠重写。 */
    public int compactOnce() {
        Map<String, List<Edge>> parents = loadDirectAtomicPaths();
        Candidate candidate = bestCandidate(parents);
        if (candidate == null) return 0;

        List<List<String>> arguments = candidate.occurrences().stream()
                .map(o -> o.edges().stream().map(Edge::argumentsJson).toList()).toList();
        UnitPathGeneralizer.Generalized generalized = generalizer.generalize(arguments);
        if (generalized == null) return 0;

        String fragmentId = createFragment(candidate, generalized);
        int rewritten = 0;
        for (int i = 0; i < candidate.occurrences().size(); i++) {
            Occurrence occurrence = candidate.occurrences().get(i);
            String invocation = json(generalized.occurrenceBindings().get(i));
            int changed = repository.replaceInvocationSpan(occurrence.parentId(),
                    occurrence.edges().stream().map(Edge::relationshipId).toList(), fragmentId,
                    occurrence.edges().get(0).order(), encode(invocation));
            if (changed == occurrence.edges().size()) rewritten++;
        }
        return rewritten;
    }

    private String createFragment(Candidate candidate, UnitPathGeneralizer.Generalized generalized) {
        String id = UUID.randomUUID().toString();
        String tools = String.join(" → ", candidate.toolSequence());
        Unit fragment = new Unit(id, UnitKind.PLAN_STEP, "公共能力: " + tools,
                "公共能力: " + tools, "由高频重复 Unit 路径自动提取", List.of("确定性图归纳"),
                generalized.signature(), Map.of(), null, Unit.DirectExecutionStatus.LEARNING,
                List.of(), List.of(), candidate.occurrences().size(), 0, 1.0, 0.0,
                List.of(), List.of(), Unit.UnitStatus.ACTIVE);
        unitStore.save(fragment);
        List<Edge> source = candidate.occurrences().get(0).edges();
        for (int i = 0; i < source.size(); i++) {
            unitStore.linkContains(id, source.get(i).childId(), i + 1, generalized.childArguments().get(i));
        }
        return id;
    }

    private Map<String, List<Edge>> loadDirectAtomicPaths() {
        Map<String, List<Edge>> parents = new LinkedHashMap<>();
        for (String row : repository.findDirectInvocationRowsForCompaction()) {
            String[] p = row.split("\\|", -1);
            if (p.length < 6 || p[4].isBlank()) continue; // 只处理直接 TOOL 路径
            parents.computeIfAbsent(p[0], ignored -> new ArrayList<>()).add(new Edge(
                    p[1], Integer.parseInt(p[2]), p[3], p[4], decode(p[5])));
        }
        parents.values().forEach(v -> v.sort(Comparator.comparingInt(Edge::order)));
        return parents;
    }

    private Candidate bestCandidate(Map<String, List<Edge>> parents) {
        Map<String, List<Occurrence>> groups = new HashMap<>();
        for (Map.Entry<String, List<Edge>> parent : parents.entrySet()) {
            List<Edge> edges = parent.getValue();
            for (int length = MIN_PATH; length <= Math.min(MAX_PATH, edges.size()); length++) {
                for (int start = 0; start + length <= edges.size(); start++) {
                    List<Edge> span = List.copyOf(edges.subList(start, start + length));
                    if (!ordersAreContiguous(span)) continue;
                    String key = span.stream().map(Edge::toolName).reduce((a, b) -> a + "\u001f" + b).orElse("");
                    groups.computeIfAbsent(key, ignored -> new ArrayList<>())
                            .add(new Occurrence(parent.getKey(), span));
                }
            }
        }
        return groups.entrySet().stream()
                .filter(e -> distinctParents(e.getValue()) >= MIN_OCCURRENCES)
                .map(e -> new Candidate(List.of(e.getKey().split("\u001f", -1)), nonOverlapping(e.getValue())))
                .filter(c -> c.occurrences().size() >= MIN_OCCURRENCES)
                .max(Comparator.comparingInt(c -> c.toolSequence().size() * c.occurrences().size()))
                .orElse(null);
    }

    private boolean ordersAreContiguous(List<Edge> edges) {
        for (int i = 1; i < edges.size(); i++) if (edges.get(i).order() != edges.get(i - 1).order() + 1) return false;
        return true;
    }
    private long distinctParents(List<Occurrence> values) { return values.stream().map(Occurrence::parentId).distinct().count(); }
    private List<Occurrence> nonOverlapping(List<Occurrence> values) {
        Map<String, Occurrence> onePerParent = new LinkedHashMap<>();
        values.forEach(v -> onePerParent.putIfAbsent(v.parentId(), v));
        return List.copyOf(onePerParent.values());
    }
    private String encode(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private String decode(String value) {
        if (value == null || value.isBlank()) return "{}";
        try { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (Exception e) { return "{}"; }
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { return "{}"; } }

    private record Edge(String relationshipId, int order, String childId, String toolName, String argumentsJson) {}
    private record Occurrence(String parentId, List<Edge> edges) {}
    private record Candidate(List<String> toolSequence, List<Occurrence> occurrences) {}
}
