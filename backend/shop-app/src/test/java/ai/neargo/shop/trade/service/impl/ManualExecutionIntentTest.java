package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.SourceSuspension;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.IntentState;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.SourceContinuity;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.StartIntent;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** State and proof correlation remain owner checks even after a record has parsed successfully. */
class ManualExecutionIntentTest {
    private static final String HASH = "a".repeat(64);
    private final ExecutionBinding binding = new ExecutionBinding(new ExecutionScope("MAIN", "entity", "store"),
            "BEAUTY", "operation", "order", "child", "line", 1, "work", 1, "task", 0, "binding", 1,
            "execution", 1, "submission", "fence", 1, 3, 1, HASH, "definition", HASH, HASH, HASH);

    @Test
    void rejectedAndAlreadyObservedIntentCannotCreateAnotherGrant() {
        for (var state : new IntentState[] {IntentState.REJECTED, IntentState.PREPARED_OBSERVED}) {
            assertThrows(BizException.class, () -> ServiceExecutionServiceImpl.requireLiveIntent(
                    intent(state, 1, 3, 1, HASH), binding));
        }
    }

    @Test
    void readyLabelCannotSubstituteChangedSourceOrOlderEpochOrDifferentFenceRevision() {
        assertThrows(BizException.class, () -> ServiceExecutionServiceImpl.requireLiveIntent(
                intent(IntentState.FROZEN, 1, 3, 2, HASH), binding));
        assertThrows(BizException.class, () -> ServiceExecutionServiceImpl.requireLiveIntent(
                intent(IntentState.FROZEN, 1, 3, 1, "b".repeat(64)), binding));
        assertThrows(BizException.class, () -> ServiceExecutionServiceImpl.requireLiveIntent(
                intent(IntentState.FROZEN, 1, 2, 1, HASH), binding));
        assertThrows(BizException.class, () -> ServiceExecutionServiceImpl.requireLiveIntent(
                intent(IntentState.FROZEN, 2, 3, 1, HASH), binding));
    }

    @Test
    void unchangedSourceCanRemainReadyAfterAnOrdinaryRefreshEpoch() {
        assertDoesNotThrow(() -> ServiceExecutionServiceImpl.requireLiveIntent(
                intent(IntentState.FROZEN, 1, 4, 1, HASH), binding));
        assertDoesNotThrow(() -> ServiceExecutionServiceImpl.requireLiveIntent(
                intent(IntentState.EXECUTION_PENDING, 1, 3, 1, HASH), binding));
    }

    @Test
    void suspensionMustBeCommittedAndNewerThanTheOriginalFence() {
        assertThrows(BizException.class, () -> ServiceExecutionServiceImpl.requireCommittedSuspension(
                suspended(IntentState.SUSPEND_REQUESTED, 1, 1, "SUSPENDED"), binding));
        assertThrows(BizException.class, () -> ServiceExecutionServiceImpl.requireCommittedSuspension(
                suspended(IntentState.FROZEN, 2, 2, "SUSPENDED"), binding));
        assertThrows(BizException.class, () -> ServiceExecutionServiceImpl.requireCommittedSuspension(
                suspended(IntentState.SUSPEND_REQUESTED, 2, 2, "PENDING"), binding));
        assertDoesNotThrow(() -> ServiceExecutionServiceImpl.requireCommittedSuspension(
                suspended(IntentState.SUSPEND_REQUESTED, 2, 2, "SUSPENDED"), binding));
    }

    private StartIntent suspended(IntentState state, long revision, long proofRevision, String continuity) {
        var draft = new SourceSuspension(binding.sourceFenceNo(), binding.executionNo(),
                ServiceExecutionContract.bindingHash(binding), proofRevision, 4, 2, 2L, "b".repeat(64),
                "SOURCE_REVISION_CONFLICT", "0".repeat(64));
        var proof = new SourceSuspension(draft.fenceNo(), draft.executionNo(), draft.bindingHash(),
                draft.intentRevision(), draft.manualEpoch(), draft.observationVersion(), draft.observedSourceRevision(),
                draft.observedSourceHash(), draft.reasonCode(), ServiceExecutionContract.suspensionHash(binding, draft));
        var original = intent(IntentState.FROZEN, 1, 3, 1, HASH);
        return new StartIntent(binding, state, revision, original.admissionKind(), original.customerPartyDigest(),
                original.personnel(), original.operatorNamespaceHash(), original.resourceCommandNo(),
                original.resourceCommandHash(), original.frozenPlan(), original.frozenPlanHash(),
                new SourceContinuity(4, 3, continuity, 2L, "b".repeat(64), "SOURCE_REVISION_CONFLICT"), proof);
    }

    private StartIntent intent(IntentState state, long revision, long epoch, long sourceRevision, String sourceHash) {
        return new StartIntent(binding, state, revision, "MANUAL_OFFLINE", HASH,
                new OperatorPersonnelFact("person", "assignment", 1, 1, 1, Instant.EPOCH, null),
                HASH, "command", HASH, "{}", HASH,
                new SourceContinuity(epoch, 1, "READY_UNCHANGED", sourceRevision, sourceHash, null), null);
    }
}
