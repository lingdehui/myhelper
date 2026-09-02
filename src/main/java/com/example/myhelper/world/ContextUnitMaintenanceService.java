package com.example.myhelper.world;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** 维护 ContextUnit 的活动集合和历史快照，不引入新的领域对象。 */
@Service
public class ContextUnitMaintenanceService {
    private static final Logger log = LoggerFactory.getLogger(ContextUnitMaintenanceService.class);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ContextUnitRepository repository;
    private final EnvironmentStateService environment;
    private final ObjectMapper mapper;

    @Value("${myhelper.context-maintenance.observation-retention-days:30}")
    private int observationRetentionDays;

    public ContextUnitMaintenanceService(ContextUnitRepository repository,
                                         EnvironmentStateService environment,
                                         ObjectMapper mapper) {
        this.repository = repository;
        this.environment = environment;
        this.mapper = mapper;
    }

    @Scheduled(cron = "${myhelper.context-maintenance.cron:0 30 4 * * ?}")
    public void maintain() {
        Instant now = Instant.now();
        try {
            long archived = repository.archiveExpiredObservations(now.toEpochMilli());
            int evicted = environment.evictExpired(now);
            long invalidated = invalidateUnsupportedInferences(now);
            long deleted = repository.deleteArchivedObservationsBefore(
                    now.minus(Math.max(1, observationRetentionDays), ChronoUnit.DAYS).toEpochMilli());
            log.info("ContextUnit 维护完成: 过期={}, 缓存清理={}, 推断失效={}, 历史删除={}",
                    archived, evicted, invalidated, deleted);
        } catch (Exception e) {
            log.warn("ContextUnit 维护失败，本轮不继续删除: {}", e.getMessage());
        }
    }

    private long invalidateUnsupportedInferences(Instant now) {
        long count = 0;
        for (ContextUnitNode node : repository.findActiveByRole(ContextUnit.Role.INFERENCE.name())) {
            List<String> evidenceIds = readIds(node.getEvidenceIdsJson());
            boolean invalid = evidenceIds.isEmpty() || evidenceIds.stream()
                    .map(environment::getContext)
                    .anyMatch(value -> value.isEmpty() || value.get().isExpired(now));
            if (!invalid) continue;
            node.setActive(false);
            node.setUpdatedAt(now.toEpochMilli());
            repository.save(node);
            environment.evictContext(node.getContextId());
            count++;
        }
        return count;
    }

    private List<String> readIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return mapper.readValue(json, STRING_LIST); }
        catch (Exception e) { return List.of(); }
    }
}
