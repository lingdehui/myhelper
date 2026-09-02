package com.example.myhelper.world;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 只向模型提供少量当前高可信状态，避免把整张图塞入 prompt。 */
@Component
public class WorldContextAssembler {
    private final EnvironmentStateService environment;

    public WorldContextAssembler(EnvironmentStateService environment) { this.environment = environment; }

    public String forPlanning() {
        var contexts = environment.currentContext(500);
        if (contexts.isEmpty()) return "";
        Map<String, ContextUnit> latest = new LinkedHashMap<>();
        contexts.stream().filter(ContextUnit::isWorldValue)
                .sorted(Comparator.comparing(c -> c.observedAt() == null ? Instant.EPOCH : c.observedAt()))
                .forEach(c -> latest.put(c.subjectId() + "\u0000" + c.predicate(), c));
        StringBuilder out = new StringBuilder("\n--- 当前世界状态（不得把 REQUIREMENT/EXPECTATION 当成事实）---\n");
        for (ContextUnit c : latest.values().stream().limit(30).toList()) {
            ContextUnit state = environment.getState(c.stateId()).orElse(null);
            if (state == null) continue;
            Object value = state.objectId() != null ? state.objectId() : state.literalValue();
            out.append(c.subjectId()).append('.').append(c.predicate()).append(" = ").append(value)
                    .append(" [").append(c.role()).append(", confidence=")
                    .append(String.format("%.2f", c.effectiveConfidence(Instant.now()))).append("]\n");
        }
        return out.toString();
    }
}
