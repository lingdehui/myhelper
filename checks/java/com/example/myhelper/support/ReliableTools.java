package com.example.myhelper.support;

import com.example.myhelper.orchestration.AgentOrchestrator;
import com.example.myhelper.orchestration.ToolReliabilityGate;
import com.example.myhelper.skill.ToolReliability;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import com.example.myhelper.world.model.ExecutionStatistics;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 仅供旧直接固化检查显式提供已确认统计，禁止作为生产范围查询装配。 */
public final class ReliableTools {
    private ReliableTools() { }

    public static ToolReliabilityGate gate() {
        return new ToolReliabilityGate(new ToolReliability(new VerifiedSamples()), 0.05);
    }

    /** 给模拟工具提供固定执行版本，真实 Spring 回调测试使用实际 Binding。 */
    public static AgentOrchestrator.ActionExecutor bind(AgentOrchestrator.ActionExecutor delegate) {
        return new AgentOrchestrator.ActionExecutor() {
            @Override
            public Optional<ToolRef> resolveTool(String tool) {
                return Optional.of(new ToolRef(tool, "check-tools", "1"));
            }

            @Override
            public AgentOrchestrator.Outcome execute(String tool, Map<String, Object> args,
                                                     AgentOrchestrator.CallContext context) {
                return delegate.execute(tool, args, context);
            }
        };
    }

    private static final class VerifiedSamples implements ToolReliability.Lookup {
        @Override
        public Optional<ToolReliability.Sample> findInVerifiedRange(ToolRef ref, Map<String, Object> args) {
            return findInVerifiedRange(ref.toolName(), args);
        }

        @Override
        public Optional<ToolReliability.Sample> findInVerifiedRange(String tool, Map<String, Object> args) {
            return Optional.of(new ToolReliability.Sample(new ExecutionStatistics(6, 0, List.of()), 0));
        }
    }
}
