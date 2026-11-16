package com.example.myhelper.tools;

import com.example.myhelper.orchestration.ToolReliabilityGate;
import com.example.myhelper.skill.RegisteredToolReliabilityLookup;
import com.example.myhelper.skill.RegisteredToolReliabilityLookup.VerifiedEvidence;
import com.example.myhelper.skill.RegisteredToolReliabilityLookup.VerifiedEvidenceLookup;
import com.example.myhelper.skill.ToolReliability;
import com.example.myhelper.tools.ToolDirectory.Registration;
import com.example.myhelper.tools.ToolDirectory.ToolRef;
import com.example.myhelper.world.model.ExecutionStatistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisteredToolReliabilityLookupTest {
    /** 当前装配的第三方进程工具，接入方式独立于 MCP 和 Home Assistant。 */
    private static final ToolRef ACTIVE_TOOL = new ToolRef("transform", "vendor-cli", "2.1");
    /** 证据适用的完整参数组合，便于区分仅命中部分参数的请求。 */
    private static final Map<String, Object> VERIFIED_ARGUMENTS = Map.of("format", "json", "strict", true);
    /** 目录先验和参数证据共同引用的范围编号。 */
    private static final String VERIFIED_RANGE = "strict-json";
    /** 由宿主登记的第三方原子工具。 */
    private static final Registration THIRD_PARTY_TOOL = new Registration(
            ACTIVE_TOOL, "upstream-cli", false, false, false,
            Map.of(VERIFIED_RANGE, new ToolDirectory.Prior(0.9375, 16)));

    @Test
    void registeredThirdPartyToolSkipsOnlyTheMinimumSuccessCount() {
        VerifiedEvidence coldStart = new VerifiedEvidence(
                ACTIVE_TOOL, ExecutionStatistics.empty(), 0, VERIFIED_RANGE);
        ToolReliability reliability = reliability(THIRD_PARTY_TOOL, coldStart);
        ToolReliability.Assessment assessment = reliability.assess(
                ACTIVE_TOOL.toolName(), VERIFIED_ARGUMENTS).orElseThrow();
        assertTrue(assessment.trustedAtomicTool());
        assertEquals(0, assessment.successCount());
        assertEquals(0.9375, assessment.score(), 1e-12);
        assertTrue(new ToolReliabilityGate(reliability, 0.85, 5)
                .evaluate(ACTIVE_TOOL.toolName(), VERIFIED_ARGUMENTS).direct());
        assertFalse(new ToolReliabilityGate(reliability, 0.9375, 5)
                .evaluate(ACTIVE_TOOL.toolName(), VERIFIED_ARGUMENTS).direct());
    }

    @Test
    void eachSourceExclusionKeepsTheActualSuccessRequirement() {
        List<Registration> excluded = List.of(
                new Registration(ACTIVE_TOOL, "generated", true, false, false),
                new Registration(ACTIVE_TOOL, "consolidated", false, true, false),
                new Registration(ACTIVE_TOOL, "project", false, false, true));
        VerifiedEvidence evidence = new VerifiedEvidence(
                ACTIVE_TOOL, new ExecutionStatistics(4, 0, List.of()), 0, VERIFIED_RANGE);

        for (Registration registration : excluded) {
            ToolReliability reliability = reliability(registration, evidence);
            ToolReliability.Assessment assessment = reliability.assess(
                    ACTIVE_TOOL.toolName(), VERIFIED_ARGUMENTS).orElseThrow();
            assertFalse(assessment.trustedAtomicTool(), registration.source());
            assertEquals(4, assessment.successCount());
            assertEquals(1.0, assessment.score());
            assertFalse(new ToolReliabilityGate(reliability, 0.85, 5)
                    .evaluate(ACTIVE_TOOL.toolName(), VERIFIED_ARGUMENTS).direct(), registration.source());
        }
    }

    @Test
    void unknownNameOrUnverifiedParameterCombinationHasNoAssessment() {
        VerifiedEvidence evidence = new VerifiedEvidence(
                ACTIVE_TOOL, ExecutionStatistics.empty(), 0, VERIFIED_RANGE);
        ToolReliability reliability = reliability(THIRD_PARTY_TOOL, evidence);

        assertTrue(reliability.assess("unregistered", VERIFIED_ARGUMENTS).isEmpty());
        assertTrue(reliability.assess(ACTIVE_TOOL.toolName(), Map.of("format", "json")).isEmpty());
        assertTrue(reliability.assess(
                ACTIVE_TOOL.toolName(), Map.of("format", "json", "strict", false)).isEmpty());
    }

    @Test
    void verifiedRangeCannotBorrowAnotherRangesPrior() {
        VerifiedEvidence unconfiguredRange = new VerifiedEvidence(
                ACTIVE_TOOL, ExecutionStatistics.empty(), 0, "unconfigured-range");
        ToolReliability reliability = reliability(THIRD_PARTY_TOOL, unconfiguredRange);

        assertTrue(reliability.assess(ACTIVE_TOOL.toolName(), VERIFIED_ARGUMENTS).isEmpty());
    }

    @Test
    void consolidatedToolRejectsPositivePriorWeight() {
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new Registration(ACTIVE_TOOL, "consolidated", false, true, false,
                        Map.of(VERIFIED_RANGE, new ToolDirectory.Prior(0.9, 1)));
            }
        });
    }

    @Test
    void evidenceForAnotherNameInstanceOrVersionIsRejected() {
        List<ToolRef> mismatches = List.of(
                new ToolRef("other-transform", ACTIVE_TOOL.sourceInstance(), ACTIVE_TOOL.implementationVersion()),
                new ToolRef(ACTIVE_TOOL.toolName(), "another-vendor-cli", ACTIVE_TOOL.implementationVersion()),
                new ToolRef(ACTIVE_TOOL.toolName(), ACTIVE_TOOL.sourceInstance(), "2.0"));

        for (ToolRef other : mismatches) {
            VerifiedEvidence evidence = new VerifiedEvidence(
                    other, new ExecutionStatistics(8, 0, List.of()), 0, VERIFIED_RANGE);
            assertTrue(reliability(THIRD_PARTY_TOOL, evidence)
                    .assess(ACTIVE_TOOL.toolName(), VERIFIED_ARGUMENTS).isEmpty(), other.toString());
        }
    }

    @Test
    void activeSnapshotDoesNotAllowAnAmbiguousName() {
        Registration anotherBinding = new Registration(
                new ToolRef(ACTIVE_TOOL.toolName(), "another-vendor-cli", "2.0"),
                "another-upstream", false, false, false);
        assertThrows(IllegalArgumentException.class, new Executable() {
            @Override
            public void execute() {
                new ToolDirectory(List.of(THIRD_PARTY_TOOL, anotherBinding));
            }
        });
    }

    private static ToolReliability reliability(Registration registration, VerifiedEvidence evidence) {
        ToolDirectory directory = new ToolDirectory(List.of(registration));
        return new ToolReliability(new RegisteredToolReliabilityLookup(directory, new EvidenceForArguments(evidence)));
    }

    private record EvidenceForArguments(VerifiedEvidence evidence) implements VerifiedEvidenceLookup {
        @Override
        public Optional<VerifiedEvidence> findInVerifiedRange(ToolRef ref, Map<String, Object> arguments) {
            assertEquals(ACTIVE_TOOL, ref);
            if (!VERIFIED_ARGUMENTS.equals(arguments)) {
                return Optional.empty();
            }
            return Optional.of(evidence);
        }
    }
}
