package com.example.myhelper.skill;

import com.example.myhelper.orchestration.ExecutionTraceStore.Node;
import com.example.myhelper.orchestration.ExecutionTraceStore.NodeKind;
import com.example.myhelper.orchestration.ExecutionTraceStore.NodeStatus;
import com.example.myhelper.orchestration.ExecutionTraceStore.Snapshot;
import com.example.myhelper.persistence.DualWriteStore;
import com.example.myhelper.persistence.EntityJson;
import com.example.myhelper.persistence.SnapshotParticipant;
import com.example.myhelper.persistence.StorageQuery;
import com.example.myhelper.records.RunArchivePort;
import com.example.myhelper.records.RunArchivePort.ArchiveRef;
import com.example.myhelper.records.RunArchivePort.ArchivedRun;
import com.example.myhelper.records.TaskRecordPort.EventKind;
import com.example.myhelper.records.TaskRecordPort.ExecutionEvent;
import com.example.myhelper.world.model.UnitType;
import com.example.myhelper.world.model.WorldEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchivedSkillChainReliabilityTest {
    @Test
    void scoresDistinctConfirmedUsesWithinTheSameApprovedRange() {
        Fixture fixture = new Fixture();
        fixture.publish(1, "PUBLISHED", "允许 1 至 10");
        ArchivedRun success = archive("r1", 1, 1, NodeStatus.SUCCESS, true, null, false);
        fixture.reliability.record(success);
        fixture.reliability.record(success);
        fixture.reliability.record(archive("r2", 1, 4, NodeStatus.FAILED, true, "PLAN", false));
        fixture.reliability.record(archive("r3", 1, 6, NodeStatus.FAILED, true, "ENVIRONMENT", false));
        fixture.reliability.record(archive("r4", 1, 8, NodeStatus.UNKNOWN, true, null, false));
        fixture.reliability.record(archive("r5", 1, 6, NodeStatus.FAILED, false, null, false));
        fixture.reliability.record(archive("r6", 1, 9, NodeStatus.SUCCESS, true, null, true));
        fixture.reliability.record(archive("r7", 1, 20, NodeStatus.SUCCESS, true, null, false));
        int callsBeforeExactQuery = fixture.range.calls;
        assertEquals(0.5, fixture.reliability.score("chain", 1, Map.of("value", 1L)).orElseThrow());
        assertEquals(callsBeforeExactQuery, fixture.range.calls);
        assertEquals(0.5, fixture.reliability.score("chain", 1, Map.of("value", 5)).orElseThrow());
        assertTrue(fixture.reliability.score("chain", 1, Map.of("value", 20)).isEmpty());
        assertEquals(5, fixture.graph.rows.size(), "发布实体、三个范围事实及一个原始超范围事实");
    }

    @Test
    void neverScoresAnotherRevisionOrAnInactiveOrUnapprovedPublication() {
        Fixture fixture = new Fixture();
        fixture.publish(1, "PUBLISHED", "允许 1 至 10");
        fixture.reliability.record(archive("r1", 1, 1, NodeStatus.SUCCESS, true, null, false));
        Map<String, Object> input = Map.of("value", 1);
        fixture.publish(2, "PUBLISHED", "允许 1 至 10");
        assertTrue(fixture.reliability.score("chain", 1, input).isEmpty());
        assertTrue(fixture.reliability.score("chain", 2, input).isEmpty());
        fixture.reliability.record(archive("r2", 2, 1, NodeStatus.FAILED, true, null, false));
        assertEquals(0, fixture.reliability.score("chain", 2, input).orElseThrow());
        fixture.publish(2, "SUSPENDED", "允许 1 至 10");
        assertTrue(fixture.reliability.score("chain", 2, input).isEmpty());
        fixture.publish(2, "PUBLISHED", "");
        assertTrue(fixture.reliability.score("chain", 2, input).isEmpty());
    }

    @Test
    void savesBeforeRangeCheckAndReusesTheFactAfterModelRecovery() {
        Fixture fixture = new Fixture();
        fixture.publish(1, "PUBLISHED", "允许 1 至 10");
        fixture.range.available = false;
        ArchivedRun run = archive("r1", 1, 1, NodeStatus.SUCCESS, true, null, false);
        fixture.reliability.record(run);
        assertEquals(2, fixture.graph.rows.size());
        Map<String, Object> input = Map.of("value", 1);
        assertTrue(fixture.reliability.score("chain", 1, input).isEmpty());
        fixture.range.available = true;
        fixture.reliability.record(run);
        fixture.reliability.record(run);
        assertEquals(2, fixture.graph.rows.size());
        assertEquals(1.0, fixture.reliability.score("chain", 1, input).orElseThrow());
    }

    private static ArchivedRun archive(String root, long revision, int argument, NodeStatus status,
                                        boolean performed, String category, boolean internal) {
        Map<String, Object> input = Map.of("value", argument);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("performed", performed);
        details.put("arguments", input);
        details.put("outcome", Map.of("status", status.name()));
        if (category != null) {
            details.put("failureCategory", category);
        }
        Node node = new Node("unit", root, null, NodeKind.UNIT, "chain", 1,
                Map.of("namespace", "skills", "revision", revision, "arguments", input), status,
                null, null, Instant.EPOCH, Instant.EPOCH);
        Snapshot graph = new Snapshot(Map.of("unit", node), List.of(), List.of(), true);
        ExecutionEvent verified = new ExecutionEvent(root + "-checked", "task", root, root, "unit", "branch",
                1, EventKind.VERIFIED, details);
        ExecutionEvent end = new ExecutionEvent(root + "-ended", "task", root, root, null, null,
                0, EventKind.RUN_ENDED, Map.of());
        return new ArchivedRun(new ArchiveRef("task", root), RunArchivePort.SCHEMA_VERSION,
                end, graph, true, List.of(), List.of(verified, end), internal);
    }

    private static final class Fixture {
        /** 经 JSON 往返的权威存储替身。 */
        private final Memory graph = new Memory();
        /** 已批准范围的确定性替身。 */
        private final RangeCheck range = new RangeCheck();
        /** 被测真实事实仓储及评分组件。 */
        private final ArchivedSkillChainReliability reliability = new ArchivedSkillChainReliability(
                new DualWriteStore(graph, new Memory()), DualWriteStore.Database.NEO4J, "skills", range);

        private void publish(long revision, String status, String rangeDescription) {
            var publication = new WorldEntity("chain", null, null, UnitType.PLAN_STEP, null,
                    null, null, Map.of("publicationStatus", status, "executionPlan", Map.of("goal", "测试")),
                    List.of(), null, revision, null, List.of(),
                    Map.of("verifiedScope", rangeDescription), null, null);
            graph.rows.put(publication.id(), publication);
        }
    }

    private static final class RangeCheck implements RecordedToolReliability.RangeCheck {
        /** 模拟模型是否可用。 */
        private boolean available = true;
        /** 是否重复对已验收相同入参调用模型。 */
        private int calls;

        @Override
        public boolean matches(Map<String, Object> arguments, Map<String, Object> approved,
                               List<RecordedToolReliability.Example> examples) {
            calls++;
            int value = ((Number) arguments.get("value")).intValue();
            return available && value >= 1 && value <= 10;
        }
    }

    private static final class Memory extends SnapshotParticipant {
        /** 统一存储适配器实际读取的实体。 */
        private final Map<String, WorldEntity> rows = new LinkedHashMap<>();
        /** 保留持久化数值类型和对象形状的真实编解码器。 */
        private final EntityJson json = new EntityJson();

        @Override
        public Optional<WorldEntity> read(String id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public boolean writeConditionally(WorldEntity previous, WorldEntity next) {
            if (!Objects.equals(rows.get(next.id()), previous)) {
                return false;
            }
            save(List.of(next));
            return true;
        }

        @Override
        public StorageQuery.Page query(StorageQuery.PageRequest request) {
            var result = new ArrayList<WorldEntity>();
            for (WorldEntity row : rows.values()) {
                var document = json.parse(json.encode(row));
                boolean matches = true;
                for (StorageQuery.Match filter : request.filter().matches()) {
                    var expected = json.parse(json.encode(filter.values().getFirst()));
                    if (!expected.equals(document.at(filter.pointer()))) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    result.add(row);
                }
            }
            return new StorageQuery.Page(result, null);
        }

        @Override
        protected void save(List<WorldEntity> result) {
            for (WorldEntity row : result) {
                rows.put(row.id(), json.entity(json.encode(row)));
            }
        }

        @Override
        protected void remove(String id) {
            rows.remove(id);
        }
    }
}
