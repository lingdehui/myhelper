package com.example.myhelper.world;

import com.example.myhelper.world.model.WorldEntity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 按本轮授权范围、时点及证据规则裁决状态和历史。 */
public final class ContextService {
    /** 由调用方明确选库的世界仓储。 */
    private final WorldRepository repository;

    public ContextService(WorldRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    /** 当前状态只能由截至本次查询时点的可信、未失效感知得到。 */
    public WorldState.Result current(WorldState.Query request) {
        var budget = new WorldRepository.Budget(request.policy().timeBudget());
        WorldState.Result result = currentAttribute(request, budget);
        if (!"power".equals(request.key().predicate()) || result.status() != WorldState.Status.KNOWN) {
            return result;
        }
        // 断联本身不能证明电源关闭。独立来源在断联之后的新观测仍可证明电源状态。
        var key = new WorldState.Key(request.key().subjectId(), "connectivity");
        var connectivity = currentAttribute(new WorldState.Query(key, request.scope(), request.asOf(), request.policy()), budget);
        if (connectivity.status() == WorldState.Status.INCOMPLETE || connectivity.status() == WorldState.Status.REVISION_UNAVAILABLE) {
            return issue(connectivity.status(), result.current());
        }
        if (connectivity.status() == WorldState.Status.CONFLICT) {
            return new WorldState.Result(WorldState.Status.CONFLICT, null, result.current(), connectivity.conflicting());
        }
        if (connectivity.status() == WorldState.Status.KNOWN && "DISCONNECTED".equals(connectivity.current().value())) {
            WorldState.Evidence power = result.current();
            if (!power.observedAt().isAfter(connectivity.current().observedAt())
                    || power.source().equals(connectivity.current().source())) {
                return issue(WorldState.Status.UNKNOWN, power);
            }
        }
        return result;
    }

    /** 历史按真实观测时间排列，保留当下已过期但修订仍可核查的证据。 */
    public WorldState.History history(WorldState.Query request) {
        var budget = new WorldRepository.Budget(request.policy().timeBudget());
        WorldRepository.Scan scan = repository.scan(request, budget);
        boolean complete = scan.complete();
        List<WorldState.Evidence> observations = new ArrayList<>();
        for (var entry : samples(scan, request)) {
            if (budget.exhausted()) {
                return new WorldState.History(false, observations);
            }
            var resolved = repository.resolve(entry.entity(), request);
            if (resolved.isPresent() && !budget.exhausted()) {
                observations.add(resolved.get());
            } else {
                complete = false;
            }
        }
        return new WorldState.History(complete, observations);
    }

    private WorldState.Result currentAttribute(WorldState.Query request, WorldRepository.Budget budget) {
        WorldRepository.Scan scan = repository.scan(request, budget);
        if (!scan.complete()) {
            return issue(WorldState.Status.INCOMPLETE, null);
        }
        List<Sample> rows = samples(scan, request);
        Map<String, List<Sample>> latest = newestPerSource(rows);
        Map<String, WorldState.Evidence> alternatives = new LinkedHashMap<>();
        Map<String, Optional<WorldState.Evidence>> loaded = new LinkedHashMap<>();
        boolean expired = false;
        boolean missingRevision = false;
        boolean undated = false;
        for (List<Sample> group : latest.values()) {
            for (Sample sample : group) {
                if (budget.exhausted()) {
                    return issue(WorldState.Status.INCOMPLETE, null);
                }
                if (!confidenceAllowed(sample.entity(), request.policy())) {
                    continue;
                }
                if (sample.observedAt() == null) {
                    undated = true;
                    continue;
                }
                switch (freshness(sample, request)) {
                    case EXPIRED -> expired = true;
                    case UNKNOWN -> { }
                    case KNOWN -> {
                        var evidence = resolution(sample, request, loaded);
                        if (evidence.isEmpty()) {
                            missingRevision = true;
                        } else {
                            var value = evidence.get();
                            var previous = alternatives.get(value.state().id());
                            if (previous == null || value.observedAt().isAfter(previous.observedAt())) {
                                alternatives.put(value.state().id(), value);
                            }
                        }
                    }
                    default -> throw new IllegalStateException("无效的证据时效状态");
                }
            }
        }
        if (budget.exhausted()) {
            return issue(WorldState.Status.INCOMPLETE, null);
        }
        if (!missingRevision && !undated && alternatives.size() == 1) {
            return new WorldState.Result(WorldState.Status.KNOWN, alternatives.values().iterator().next(), null, List.of());
        }
        if (alternatives.size() > 1) {
            return new WorldState.Result(WorldState.Status.CONFLICT, null, null, List.copyOf(alternatives.values()));
        }
        WorldState.Evidence lastKnown = lastKnown(rows, request, loaded, budget);
        if (budget.exhausted()) {
            return issue(WorldState.Status.INCOMPLETE, lastKnown);
        }
        if (missingRevision) {
            return issue(WorldState.Status.REVISION_UNAVAILABLE, lastKnown);
        }
        return issue(expired && !undated ? WorldState.Status.EXPIRED : WorldState.Status.UNKNOWN, lastKnown);
    }

    private Optional<WorldState.Evidence> resolution(Sample sample, WorldState.Query request,
                                                    Map<String, Optional<WorldState.Evidence>> loaded) {
        String id = sample.entity().id();
        Optional<WorldState.Evidence> resolved = loaded.get(id);
        if (resolved == null) {
            resolved = repository.resolve(sample.entity(), request);
            loaded.put(id, resolved);
        }
        return resolved;
    }

    private WorldState.Evidence lastKnown(List<Sample> rows, WorldState.Query request,
                                         Map<String, Optional<WorldState.Evidence>> loaded, WorldRepository.Budget budget) {
        WorldState.Evidence latest = null;
        for (Sample sample : rows) {
            if (budget.exhausted() || sample.observedAt() == null
                    || (latest != null && sample.observedAt().isBefore(latest.observedAt()))) {
                break;
            }
            if (!confidenceAllowed(sample.entity(), request.policy())) {
                continue;
            }
            var evidence = resolution(sample, request, loaded);
            if (evidence.isPresent()) {
                if (latest != null && !latest.state().id().equals(evidence.get().state().id())) {
                    return null;
                }
                latest = evidence.get();
            }
        }
        return latest;
    }

    private static List<Sample> samples(WorldRepository.Scan scan, WorldState.Query request) {
        List<Sample> rows = new ArrayList<>();
        for (WorldEntity row : scan.rows()) {
            if (row.source() == null || row.source().isBlank()
                    || (!request.policy().trustedSources().isEmpty() && !request.policy().trustedSources().contains(row.source()))) {
                continue;
            }
            Instant observed = WorldRepository.timestamp(row.data().get("observedAt"));
            if (observed == null || !observed.isAfter(request.asOf())) {
                rows.add(new Sample(row, observed));
            }
        }
        rows.sort(new RecentFirst());
        return rows;
    }

    private static Map<String, List<Sample>> newestPerSource(List<Sample> rows) {
        Map<String, List<Sample>> latest = new LinkedHashMap<>();
        for (Sample sample : rows) {
            var source = sample.entity().source();
            var group = latest.get(source);
            if (group == null) {
                group = new ArrayList<>();
                latest.put(source, group);
            }
            if (group.isEmpty() || sample.observedAt() == null || Objects.equals(group.getFirst().observedAt(), sample.observedAt())) {
                group.add(sample);
            }
        }
        return latest;
    }

    private static WorldState.Status freshness(Sample row, WorldState.Query request) {
        Instant validUntil = WorldRepository.timestamp(row.entity().data().get("validUntil"));
        Duration maxAge = request.policy().maximumAge();
        if (validUntil == null && (maxAge == null || row.entity().data().get("validUntil") != null)) {
            return WorldState.Status.UNKNOWN;
        }
        if (validUntil != null && (!validUntil.isAfter(row.observedAt()) || !validUntil.isAfter(request.asOf()))) {
            return WorldState.Status.EXPIRED;
        }
        if (maxAge != null && Duration.between(row.observedAt(), request.asOf()).compareTo(maxAge) > 0) {
            return WorldState.Status.EXPIRED;
        }
        return WorldState.Status.KNOWN;
    }

    private static boolean confidenceAllowed(WorldEntity row, WorldState.Policy policy) {
        try {
            var confidence = WorldRepository.confidence(row);
            return policy.minimumConfidence() == null || confidence != null && confidence >= policy.minimumConfidence();
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static WorldState.Result issue(WorldState.Status status, WorldState.Evidence lastKnown) {
        return new WorldState.Result(status, null, lastKnown, List.of());
    }

    private record Sample(
            /** 匹配了业务合同及查询范围的原始实体。 */
            WorldEntity entity,
            /** 真实感知时刻，未知时为空。 */
            Instant observedAt) {
    }

    private static final class RecentFirst implements Comparator<Sample> {
        @Override
        public int compare(Sample left, Sample right) {
            if (left.observedAt() == null && right.observedAt() != null) {
                return 1;
            }
            if (right.observedAt() == null && left.observedAt() != null) {
                return -1;
            }
            if (left.observedAt() != null) {
                int time = right.observedAt().compareTo(left.observedAt());
                if (time != 0) {
                    return time;
                }
            }
            return left.entity().id().compareTo(right.entity().id());
        }
    }
}
