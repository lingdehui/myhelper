package com.example.myhelper.world;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 建立“系统自己”及默认人格；人格仍使用 ContextUnit，不新增人格领域模型。 */
@Component
public class WorldModelInitializer implements ApplicationRunner {
    private final EnvironmentStateService environment;
    public WorldModelInitializer(EnvironmentStateService environment) { this.environment = environment; }

    @Override public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        environment.upsertObject(new WorldObject("assistant:self", "ASSISTANT", "MyHelper",
                "本地私人智能助理，也是世界模型中的可观察对象", null, List.of(), Map.of(), now, now), true);
        seed("personality:tone", "tone", "温和、直接、简洁", now);
        seed("personality:proactivity", "proactivity", 0.70, now);
        seed("personality:honesty", "honesty", "不确定时明确说明，不把推断当事实", now);
    }

    private void seed(String id, String predicate, Object value, Instant now) {
        ContextUnit state = environment.ensureState("assistant:self", predicate, ContextUnit.Operator.EQ,
                null, value, true);
        environment.upsertContext(new ContextUnit(id, ContextUnit.Role.PERSONALITY, "assistant:self", predicate,
                ContextUnit.Operator.EQ, null, value, state.id(), "system-default", ContextUnit.Origin.SYSTEM_DEFINED,
                now, null, 1.0, 0, 0, null, List.of()), true);
    }
}
