package com.example.myhelper.llm;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import static org.junit.jupiter.api.Assertions.*;

class ConfiguredModelAdapterTest {
    @Test
    void allocatesCompatibleModelsAndReusesOnlyReleasedReferences() {
        ChatModel client = new UncalledModel();
        ConfiguredModelAdapter adapter = new ConfiguredModelAdapter(List.of(
                new ConfiguredModelAdapter.ModelConfig("A", client, true, true),
                new ConfiguredModelAdapter.ModelConfig("disabled", client, false, true),
                new ConfiguredModelAdapter.ModelConfig("plain", client, true, false),
                new ConfiguredModelAdapter.ModelConfig("B", client, true, true)));
        ModelAdapter.RunRequest request = new ModelAdapter.RunRequest("run", "root", "A", List.of("tool"));
        ModelAdapter.Session run = adapter.openRun(request);
        try (run) {
            assertThrows(IllegalStateException.class, new Executable() {
                @Override
                public void execute() {
                    adapter.openRun(request);
                }
            });
            ModelAdapter.Binding root = run.root();
            List<String> ids = List.of("left", "right");
            assertThrows(IllegalArgumentException.class, new Executable() {
                @Override
                public void execute() {
                    run.fork(root, List.of("left", " "));
                }
            });
            List<ModelAdapter.Binding> children = run.fork(root, ids);
            ModelAdapter.Binding left = children.getFirst();
            assertEquals("B", left.modelRef());
            assertEquals(ModelAdapter.Assignment.INHERITED, children.getLast().assignment());
            assertEquals("A", children.getLast().modelRef());
            assertSame(client, adapter.resolve(left.modelRef()));
            assertEquals(children, run.fork(root, ids));
            assertEquals("B", run.fork(left, List.of("inner1", "inner2")).getLast().modelRef());
            assertThrows(IllegalStateException.class, new Executable() {
                @Override
                public void execute() {
                    run.release("left");
                }
            });
            assertTrue(run.release("inner1"));
            assertTrue(run.release("inner2"));
            assertTrue(run.release("left"));
            assertFalse(run.release("left"));
            assertThrows(IllegalArgumentException.class, new Executable() {
                @Override
                public void execute() {
                    run.fork(root, ids);
                }
            });
            assertEquals("B", run.fork(root, List.of("next1", "next2")).getFirst().modelRef());
            assertEquals(root, run.binding("root"));
        }
        run.close();
        assertThrows(IllegalStateException.class, run::root);
        try (ModelAdapter.Session reopened = adapter.openRun(request)) {
            assertEquals("A", reopened.root().modelRef());
        }
    }

    @Test
    void appliesPerBranchPreferencesWithinHardConstraintsAndKeepsReplayStable() {
        RecordingRoutes routes = new RecordingRoutes();
        ConfiguredModelAdapter adapter = new ConfiguredModelAdapter(List.of(
                model("A", true, true), model("B", true, true), model("C", true, true),
                model("D", true, true), model("E", true, true),
                model("disabled", false, true), model("plain", true, false)), routes);
        List<ModelAdapter.BranchRequest> children = List.of(
                branch("left", "render"),
                branch("right", "lookup"),
                branch("no-label", "unclassified"),
                new ModelAdapter.BranchRequest("pure-tool", List.of()),
                new ModelAdapter.BranchRequest("shortage", List.of()));

        try (ModelAdapter.Session run = adapter.openRun(new ModelAdapter.RunRequest("run", "root", "A", List.of()))) {
            List<ModelAdapter.Binding> assigned = run.forkWithTasks(run.root(), children);
            assertEquals("D", assigned.get(0).modelRef());
            assertEquals("C", assigned.get(1).modelRef());
            assertEquals("B", assigned.get(2).modelRef());
            assertEquals("E", assigned.get(3).modelRef());
            assertEquals("A", assigned.getLast().modelRef());
            assertEquals(ModelAdapter.Assignment.INHERITED, assigned.getLast().assignment());
            assertEquals(assigned, run.forkWithTasks(run.root(), children));
            assertEquals(3, routes.requests);
        }
    }

    @Test
    void providerFailureDoesNotPublishAnyPartOfTheBatch() {
        RecordingRoutes routes = new RecordingRoutes();
        ConfiguredModelAdapter adapter = new ConfiguredModelAdapter(
                List.of(model("A", true, true), model("B", true, true), model("C", true, true)), routes);
        List<ModelAdapter.BranchRequest> children = List.of(branch("left", "render"), branch("right", "lookup"));

        try (ModelAdapter.Session run = adapter.openRun(new ModelAdapter.RunRequest("run", "root", "A", List.of()))) {
            routes.failLookup = true;
            assertThrows(IllegalStateException.class, new Executable() {
                @Override
                public void execute() {
                    run.forkWithTasks(run.root(), children);
                }
            });
            assertThrows(IllegalArgumentException.class, new Executable() {
                @Override
                public void execute() {
                    run.binding("left");
                }
            });
            routes.failLookup = false;
            List<ModelAdapter.Binding> assigned = run.forkWithTasks(run.root(), children);
            assertEquals("C", assigned.getFirst().modelRef());
            assertEquals("B", assigned.getLast().modelRef());
        }
    }

    private static ConfiguredModelAdapter.ModelConfig model(String ref, boolean enabled, boolean supportsTools) {
        return new ConfiguredModelAdapter.ModelConfig(ref, new UncalledModel(), enabled, supportsTools);
    }

    private static ModelAdapter.BranchRequest branch(String id, String goal) {
        return new ModelAdapter.BranchRequest(id, List.of(new ModelAdapter.RoutingTask(goal, List.of("search"))));
    }

    private static final class RecordingRoutes implements ModelRoutingProvider {
        /** 记录查询次数，验证重放与无模型任务的分支不会查询。 */
        private int requests;
        /** 仅在原子性检查中注入画像读取异常。 */
        private boolean failLookup;

        @Override
        public Route route(ModelAdapter.BranchRequest branch) {
            requests++;
            String goal = branch.tasks().getFirst().goal();
            return switch (goal) {
                case "render" -> new Route(List.of("render"), List.of("unknown", "disabled", "plain", "A", "D", "C"));
                case "lookup" -> {
                    if (failLookup) {
                        throw new IllegalStateException("画像暂不可读");
                    }
                    yield new Route(List.of("lookup"), List.of("D", "C"));
                }
                case "unclassified" -> new Route(List.of(), List.of("E"));
                default -> throw new AssertionError("Unexpected routing goal: " + goal);
            };
        }
    }

    private static final class UncalledModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            throw new AssertionError("Binding and resolution must not send a model request");
        }
    }
}
