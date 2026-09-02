package com.example.myhelper.memory.unit;

import com.example.myhelper.memory.graph.UnitNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit 领域对象 ↔ Neo4j {@link UnitNode} 互转（文档 15 v1.7 §3 / §4.1）。
 *
 * <p>复杂字段（notes/params/outputSignature/context引用/failureCauses/explorationRecords）
 * 以 JSON 字符串存储在 UnitNode 中。CONTAINS/DISABLES/FALLBACK 关系属于图级结构，
 * 不在此处转换，由存储层通过 UnitRepository 的关系方法单独维护。</p>
 */
@Component
public class UnitConverter {

    private static final Logger log = LoggerFactory.getLogger(UnitConverter.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Unit → UnitNode（关系字段不处理，由存储层单独维护）。 */
    public UnitNode toNode(Unit unit) {
        UnitNode node = new UnitNode(
                unit.unitId(),
                unit.unitKind().name(),
                unit.matchText(),
                unit.goal(),
                unit.description());
        node.setNotesJson(toJson(unit.notes()));
        node.setParamsJson(toJson(unit.params()));
        node.setOutputSignatureJson(toJson(unit.outputSignature()));
        node.setToolName(unit.toolName());
        node.setDirectExecutionStatus(unit.directExecutionStatus() == null
                ? Unit.DirectExecutionStatus.LEARNING.name() : unit.directExecutionStatus().name());
        node.setRequiredContextIdsJson(toJson(unit.requiredContextIds()));
        node.setExpectedContextIdsJson(toJson(unit.expectedContextIds()));
        node.setSuccessCount(unit.successCount());
        node.setFailureCount(unit.failureCount());
        node.setStability(Unit.calcStability(unit.successCount(), unit.failureCount()));
        node.setQualityScore(unit.qualityScore());
        node.setFailureCausesJson(toJson(unit.failureCauses()));
        node.setExplorationRecordsJson(toJson(unit.explorationRecords()));
        node.setStatus(unit.status() == null ? Unit.UnitStatus.ACTIVE.name() : unit.status().name());
        return node;
    }

    /** UnitNode → Unit（stability 实时重算，不信任存储值）。 */
    public Unit toUnit(UnitNode node) {
        int success = node.getSuccessCount();
        int failure = node.getFailureCount();
        UnitKind kind = parseEnum(UnitKind.class, node.getUnitKind(), UnitKind.PLAN_STEP);
        Unit.UnitStatus status = parseEnum(Unit.UnitStatus.class, node.getStatus(), Unit.UnitStatus.ACTIVE);

        return new Unit(
                node.getUnitId(),
                kind,
                node.getMatchText(),
                node.getGoal(),
                node.getDescription(),
                parseListString(node.getNotesJson()),
                parseMap(node.getParamsJson()),
                parseMap(node.getOutputSignatureJson()),
                node.getToolName(),
                parseEnum(Unit.DirectExecutionStatus.class, node.getDirectExecutionStatus(), Unit.DirectExecutionStatus.LEARNING),
                parseListString(node.getRequiredContextIdsJson()),
                parseListString(node.getExpectedContextIdsJson()),
                success,
                failure,
                Unit.calcStability(success, failure),
                node.getQualityScore(),
                parseListString(node.getFailureCausesJson()),
                parseExplorationRecords(node.getExplorationRecordsJson()),
                status);
    }

    // ===== 序列化辅助 =====

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("⚠️ Unit 字段序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private List<String> parseListString(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<String> v = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return v == null ? new ArrayList<>() : v;
        } catch (Exception e) {
            log.warn("⚠️ 解析 List<String> 失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private Map<String, String> parseMap(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try {
            Map<String, String> v = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
            return v == null ? new HashMap<>() : v;
        } catch (Exception e) {
            log.warn("⚠️ 解析 Map<String,String> 失败: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private List<ExplorationRecord> parseExplorationRecords(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<ExplorationRecord> v = objectMapper.readValue(json, new TypeReference<List<ExplorationRecord>>() {});
            return v == null ? new ArrayList<>() : v;
        } catch (Exception e) {
            log.warn("⚠️ 解析 explorationRecords 失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String name, E fallback) {
        if (name == null) return fallback;
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
