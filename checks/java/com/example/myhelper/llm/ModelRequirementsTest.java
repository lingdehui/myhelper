package com.example.myhelper.llm;

import com.example.myhelper.agent.AgentCaller;
import com.example.myhelper.orchestration.AgentCallerPlanner;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import static org.junit.jupiter.api.Assertions.*;

/** 模型能力来自规划协议；检查过程中禁止真正发送模型请求。 */
class ModelRequirementsTest {
    @Test
    void plannerRejectsTextOnlyRootEvenWithoutBusinessTools() {
        ConfiguredModelAdapter adapter = adapter();
        AgentCallerPlanner planner = new AgentCallerPlanner(new AgentCaller(adapter, List.of()));
        assertEquals(Set.of(ModelAdapter.Requirement.TOOL_CALLING), planner.requirements());
        ModelAdapter.RunRequest request = new ModelAdapter.RunRequest(
                "run", "root", "plain", List.of(), planner.requirements());
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                adapter.openRun(request);
            }
        });
        // 不兼容根在登记会话前被拒绝，同一运行编号仍可正确装配。
        try (ModelAdapter.Session session = adapter.openRun(new ModelAdapter.RunRequest(
                "run", "root", "A", List.of(), planner.requirements()))) {
            assertEquals("A", session.root().modelRef());
        }
    }

    @Test
    void plannerForkSkipsTextOnlyCandidatesWithoutBusinessTools() {
        ConfiguredModelAdapter adapter = adapter();
        AgentCallerPlanner planner = new AgentCallerPlanner(new AgentCaller(adapter, List.of()));
        ModelAdapter.RunRequest request = new ModelAdapter.RunRequest(
                "run", "root", "A", List.of(), planner.requirements());
        try (ModelAdapter.Session session = adapter.openRun(request)) {
            List<ModelAdapter.Binding> children = session.fork(session.root(), List.of("left", "right"));
            // 目录中的 plain 在 B 前面，但缺少协议能力，不能分给首个子分支。
            assertEquals("B", children.getFirst().modelRef());
            assertEquals(ModelAdapter.Assignment.OTHER_MODEL, children.getFirst().assignment());
            assertEquals("A", children.getLast().modelRef());
            assertEquals(ModelAdapter.Assignment.INHERITED, children.getLast().assignment());
            // 嵌套分叉继续使用相同要求，不会因为其他模型占用而改选 plain。
            List<ModelAdapter.Binding> nested = session.fork(children.getFirst(), List.of("inner1", "inner2"));
            assertEquals("B", nested.getFirst().modelRef());
            assertEquals("B", nested.getLast().modelRef());
        }
    }

    @Test
    void explicitTextProtocolDoesNotInferRequirementsFromBusinessToolNames() {
        ConfiguredModelAdapter adapter = adapter();
        ModelAdapter.RunRequest request = new ModelAdapter.RunRequest(
                "run", "root", "plain", List.of("describedBusinessTool"), Set.of());
        try (ModelAdapter.Session session = adapter.openRun(request)) {
            // 这是显式的纯文本协议；业务工具名单不自行改写它的能力声明。
            assertEquals("plain", session.root().modelRef());
            assertSame(adapter.resolve("plain"), adapter.apply("plain"));
        }
    }

    @Test
    void legacyConstructorIsConservativeAndExplicitRequirementsAreImmutable() {
        ModelAdapter.RunRequest legacy = new ModelAdapter.RunRequest("old", "root", "A", List.of());
        assertEquals(Set.of(ModelAdapter.Requirement.TOOL_CALLING), legacy.requirements());
        Set<ModelAdapter.Requirement> mutable = new HashSet<>();
        mutable.add(ModelAdapter.Requirement.TOOL_CALLING);
        ModelAdapter.RunRequest explicit = new ModelAdapter.RunRequest("new", "root", "A", List.of(), mutable);
        mutable.clear();
        assertEquals(Set.of(ModelAdapter.Requirement.TOOL_CALLING), explicit.requirements());
        assertThrows(UnsupportedOperationException.class, new Executable() {
            @Override
            public void execute() {
                explicit.requirements().clear();
            }
        });
    }

    private static ConfiguredModelAdapter adapter() {
        ChatModel client = new UncalledModel();
        return new ConfiguredModelAdapter(List.of(
                new ConfiguredModelAdapter.ModelConfig("A", client, true, true),
                new ConfiguredModelAdapter.ModelConfig("plain", client, true, false),
                new ConfiguredModelAdapter.ModelConfig("B", client, true, true)));
    }

    private static final class UncalledModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            throw new AssertionError("能力筛选不得触发模型请求");
        }
    }
}
