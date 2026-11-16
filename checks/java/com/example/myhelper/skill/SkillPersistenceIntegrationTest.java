package com.example.myhelper.skill;

import com.example.myhelper.agent.AgentCaller;
import com.example.myhelper.orchestration.ExecutionTraceStore;
import com.example.myhelper.records.RunArchivePort;
import com.example.myhelper.records.TaskRecordPort;
import java.time.Instant;
import com.example.myhelper.orchestration.ToolReliabilityGate;
import com.example.myhelper.persistence.*;
import com.example.myhelper.tools.ToolDirectory;
import com.example.myhelper.world.model.WorldEntity;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import static org.junit.jupiter.api.Assertions.*;

/** 贯通实际参数保存、模型判定、目录先验及现有执行门限；数据库/模型仅在边界替身。 */
class SkillPersistenceIntegrationTest {
    @Test void recordedArgumentsSurviveModelFailureAndReplayedExecutionDoesNotDoubleCount() {
        Fixture f = new Fixture(false);
        ToolParameterStore.Execution execution = f.success("operation", Map.of("value", 1));
        f.parameters.record(execution);
        f.parameters.record(execution);
        ToolReliability.Assessment score = f.reliability.assess("echo", Map.of("value", 1)).orElseThrow();
        assertEquals(1, score.successCount());
        assertEquals(1, score.score());
        assertEquals(0, f.modelCalls);
        assertEquals(1, f.database.rows.size());
        f.approveRange();
        int stored = f.database.rows.size();
        assertTrue(f.reliability.assess("echo", Map.of("value", 2)).isEmpty());
        assertEquals(1, f.modelCalls);
        assertEquals(stored, f.database.rows.size());
        assertEquals(1, f.reliability.assess("echo", Map.of("value", 1)).orElseThrow().successCount());
    }

    @Test void trustedExternalColdStartUsesActualModelRangeDecisionAndConfiguredPrior() {
        Fixture f = new Fixture(true);
        f.approveRange();
        f.modelAvailable = true;
        var decision = new ToolReliabilityGate(f.reliability, 0.85, 5).evaluate("echo", Map.of("value", 2));
        assertTrue(decision.direct());
        assertEquals(0.95, decision.score());
        assertEquals(1, f.modelCalls);
        assertEquals(1, f.database.rows.size());
    }

    @Test void ordinaryToolRequiresFiveDistinctConfirmedExecutions() {
        Fixture f = new Fixture(false);
        for (int i = 0; i < 4; i++) { f.parameters.record(f.success("operation-" + i, Map.of("value", 1))); }
        var gate = new ToolReliabilityGate(f.reliability, 0.85, 5);
        assertFalse(gate.evaluate("echo", Map.of("value", 1)).direct());
        f.parameters.record(f.success("operation-4", Map.of("value", 1)));
        assertTrue(gate.evaluate("echo", Map.of("value", 1)).direct());
        assertEquals(0, f.modelCalls);
    }

    @Test void storageFailureIsNotHiddenByUnavailableModel() {
        Fixture f = new Fixture(false);
        f.database.failWrites = true;
        assertThrows(IllegalStateException.class, new Executable() {
            @Override public void execute() { f.parameters.record(f.success("operation", Map.of("value", 1))); }
        });
        assertTrue(f.database.rows.isEmpty());
        assertEquals(0, f.modelCalls);
    }

    @Test void archiveSavesActualArgumentsBeforeRangeModelAndReplaysWithoutDoubleCounting() {
        Fixture f = new Fixture(false);
        f.approveRange();
        var recorder = new ArchivedToolParameterRecorder(f.parameters, f.rangeCheck);
        var archive = archive("TOOL_BINDING");
        recorder.record(archive);
        assertEquals(1, f.reliability.assess("echo", Map.of("value", 1L)).orElseThrow().successCount());
        assertEquals(2, f.database.rows.size());

        f.modelAvailable = true;
        recorder.record(archive);
        recorder.record(archive);
        assertEquals(1, f.reliability.assess("echo", Map.of("value", 2)).orElseThrow().successCount());
        assertEquals(2, f.database.rows.size());
    }

    @Test void ordinaryToolEvidenceCannotSupplyAnActualVersionBinding() {
        Fixture f = new Fixture(false);
        var recorder = new ArchivedToolParameterRecorder(f.parameters, f.rangeCheck);
        recorder.record(archive("BEFORE"));
        assertTrue(f.database.rows.isEmpty());
    }

    @Test void approvedRangeSelectionUsesFirstExplicitMatchAndKeepsUnmatchedVerdictsDistinct() {
        Fixture f = new Fixture(false);
        f.approveRanges("z-first", "a-second", "third");
        f.modelAvailable = true;
        Map<String, Object> arguments = Map.of("value", 2);

        f.reply("\"MATCH\"", "\"MATCH\"");
        assertEquals("z-first", f.evidence.findInVerifiedRange(f.ref, arguments).orElseThrow().verifiedRangeId());
        assertEquals(1, f.modelCalls);

        f.reply("\"UNKNOWN\"", "\"MATCH\"", "\"MATCH\"");
        assertEquals("a-second", f.evidence.findInVerifiedRange(f.ref, arguments).orElseThrow().verifiedRangeId());
        assertEquals(3, f.modelCalls);

        f.reply("\"NO_MATCH\"", "\"NO_MATCH\"", "\"NO_MATCH\"");
        var noMatch = f.evidence.selectApprovedRange(f.ref, arguments);
        assertEquals(RecordedToolReliability.RangeVerdict.NO_MATCH, noMatch.verdict());
        assertTrue(noMatch.rangeId().isEmpty());

        f.reply("\"NO_MATCH\"", "invalid JSON", "\"NO_MATCH\"");
        var unknown = f.evidence.selectApprovedRange(f.ref, arguments);
        assertEquals(RecordedToolReliability.RangeVerdict.UNKNOWN, unknown.verdict());
        assertTrue(unknown.rangeId().isEmpty());
    }

    @Test void archiveUsesSameRangeOrderAndKeepsExactObservationUntilExplicitMatch() {
        Fixture f = new Fixture(false);
        f.approveRanges("z-first", "a-second");
        f.modelAvailable = true;
        var recorder = new ArchivedToolParameterRecorder(f.parameters, f.rangeCheck);
        var archive = archive("TOOL_BINDING");
        Map<String, Object> arguments = Map.of("value", 1);

        f.reply("\"NO_MATCH\"", "\"UNKNOWN\"");
        recorder.record(archive);
        assertEquals(f.parameters.exactRangeId(arguments),
                f.evidence.findInVerifiedRange(f.ref, arguments).orElseThrow().verifiedRangeId());

        f.reply("\"UNKNOWN\"", "\"MATCH\"");
        recorder.record(archive);
        assertEquals("a-second", f.evidence.findInVerifiedRange(f.ref, arguments).orElseThrow().verifiedRangeId());
        assertEquals(4, f.modelCalls);
        assertEquals(2, f.database.rows.size());
    }

    private static RunArchivePort.ArchivedRun archive(String phase) {
        var key = new RunArchivePort.ArchiveRef("task", "root");
        Map<String, Object> arguments = Map.of("value", 1);
        var node = new ExecutionTraceStore.Node("operation", "root", null, ExecutionTraceStore.NodeKind.TOOL,
                "echo", 1, arguments, ExecutionTraceStore.NodeStatus.SUCCESS, "actual-result", null,
                Instant.EPOCH, Instant.EPOCH);
        var graph = new ExecutionTraceStore.Snapshot(Map.of("operation", node), List.of(), List.of(), true);
        var end = event("end", TaskRecordPort.EventKind.RUN_ENDED, Map.of());
        Map<String, Object> result = Map.of("status", "SUCCESS", "value", "actual-result");
        var binding = event("binding", TaskRecordPort.EventKind.OBSERVED, Map.of("phase", phase,
                "evidence", Map.of("toolName", "echo", "sourceInstance", "instance", "implementationVersion", "v1")));
        var returned = event("returned", TaskRecordPort.EventKind.RETURNED,
                Map.of("tool", "echo", "arguments", arguments, "outcome", result));
        var verified = event("verified", TaskRecordPort.EventKind.VERIFIED, Map.of("performed", true, "outcome", result));
        return new RunArchivePort.ArchivedRun(key, 1, end, graph, true, List.of(),
                List.of(binding, returned, verified, end), false);
    }

    private static TaskRecordPort.ExecutionEvent event(String id, TaskRecordPort.EventKind kind,
                                                        Map<String, Object> details) {
        return new TaskRecordPort.ExecutionEvent(id, "task", "root", "root", "operation", "branch", 1, kind, details);
    }

    private static final class Fixture {
        final ToolDirectory.ToolRef ref = new ToolDirectory.ToolRef("echo", "instance", "v1");
        final Memory database = new Memory();
        final DualWriteStore store = new DualWriteStore(database, new Memory());
        final ToolParameterStore parameters = new ToolParameterStore(store);
        final ToolReliability reliability;
        final ModelToolRangeCheck rangeCheck;
        final RecordedToolReliability evidence;
        final Deque<String> modelReplies = new ArrayDeque<>();
        int modelCalls;
        boolean modelAvailable;

        Fixture(boolean trusted) {
            ChatModel model = new ChatModel() {
                @Override public ChatResponse call(Prompt prompt) {
                    modelCalls++;
                    if (!modelAvailable) { throw new IllegalStateException("model unavailable"); }
                    assertTrue(prompt.getContents().contains("arguments"));
                    String reply = modelReplies.isEmpty() ? "\"MATCH\"" : modelReplies.removeFirst();
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(reply).build())));
                }
            };
            AgentCaller caller = new AgentCaller(new Function<String, ChatModel>() {
                @Override public ChatModel apply(String name) { return model; }
            }, List.of());
            Map<String, ToolDirectory.Prior> priors = trusted ? Map.of("range", new ToolDirectory.Prior(0.95, 20)) : Map.of();
            var directory = new ToolDirectory(List.of(new ToolDirectory.Registration(ref, "known-source", false, false, !trusted, priors)));
            rangeCheck = new ModelToolRangeCheck(caller, "range-model");
            evidence = new RecordedToolReliability(parameters, rangeCheck);
            reliability = new ToolReliability(new RegisteredToolReliabilityLookup(directory, evidence));
        }

        ToolParameterStore.Execution success(String operation, Map<String, Object> arguments) {
            return new ToolParameterStore.Execution(new ToolParameterStore.Scope(ref, "range"), operation,
                    "archive/" + operation, arguments, ToolParameterStore.Outcome.SUCCESS, "actual-result", null, null);
        }

        void approveRange() {
            approveRanges("range");
        }

        void approveRanges(String... ids) {
            var ranges = new ArrayList<ToolParameterStore.ApprovedRange>();
            for (String id : ids) {
                ranges.add(new ToolParameterStore.ApprovedRange(id, Map.of("value", "1 to 10"), "review-" + id));
            }
            assertTrue(parameters.replaceApprovedRanges(ref, List.of(), ranges));
        }

        void reply(String... responses) {
            modelReplies.clear();
            modelReplies.addAll(List.of(responses));
        }
    }

    private static final class Memory extends SnapshotParticipant {
        final Map<String, WorldEntity> rows = new LinkedHashMap<>();
        final EntityJson json = new EntityJson();
        boolean failWrites;
        @Override public Optional<WorldEntity> read(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public boolean writeConditionally(WorldEntity old, WorldEntity replacement) {
            if (failWrites) { throw new IllegalStateException("database unavailable"); }
            if (!Objects.equals(rows.get(replacement.id()), old)) { return false; }
            rows.put(replacement.id(), json.entity(json.encode(replacement)));
            return true;
        }
        @Override public StorageQuery.Page query(StorageQuery.PageRequest request) {
            List<WorldEntity> found = new ArrayList<>();
            for (WorldEntity row : rows.values()) {
                var node = json.parse(json.encode(row));
                boolean accepted = true;
                for (StorageQuery.Match match : request.filter().matches()) {
                    boolean matched = false;
                    for (Object value : match.values()) {
                        matched |= node.at(match.pointer()).equals(json.parse(json.encode(value)));
                    }
                    accepted &= matched;
                }
                if (accepted) { found.add(row); }
            }
            return new StorageQuery.Page(found, null);
        }
        @Override protected void save(List<WorldEntity> values) {
            for (WorldEntity value : values) { rows.put(value.id(), json.entity(json.encode(value))); }
        }
        @Override protected void remove(String id) { rows.remove(id); }
    }
}
