package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.GrantState;
import java.time.Instant;
import java.util.Objects;

/** Observational proof of a persisted original; it is never current permission to start or debit stock. */
public record ExecutionOriginalEvidence(String schema, ExecutionReference reference, EvidenceOutcome outcome,
        String reason, OriginalFacts facts, Instant observedAt, String proofHash) {
    public enum EvidenceOutcome {
        PROVEN, PENDING, UNKNOWN, UNSUPPORTED
    }

    public enum ContinuityState {
        READY_UNCHANGED, PENDING, SUSPENDED
    }

    public enum UnitState {
        BOUND, RESERVED, PERFORMED, VOIDED
    }

    public record PrepareResult(String actionNo, String actionHash, String keyHash, String resultState,
            long resultRevision) {
    }

    public record Continuity(ContinuityState state, long sourceEpoch, long observationVersion,
            Long sourceRevision, String sourceHash, String reasonCode) {
    }

    public record Suspension(String fenceNo, String executionNo, String bindingHash, long intentRevision,
            long sourceEpoch, long observationVersion, Long observedSourceRevision, String observedSourceHash,
            String reasonCode, String proofHash) {
    }

    public record OriginalFacts(String originalBindingJson, String originalBindingBytesHash, long preparedGrantRevision,
            PrepareResult prepareResult, long currentGrantRevision, long coreVersion, long observationEpoch,
            GrantState grantState, UnitState unitState, String currentExecutionNo, int unitAttemptNo,
            int quantity, String uom, String declarationNo, String confirmationNo, long admissionRevision,
            Original stockOriginal, String stockConfirmationOperationNo, String originalStockObservationHash,
            String sourceIntentHash, String planHash, String plannedResourceCommandNo,
                String plannedResourceCommandHash,
            String frozenPlan, Continuity continuity, Suspension suspension, String operatorNamespaceHash,
            OperatorPersonnelFact frozenPersonnel) {
    }

    public ExecutionOriginalEvidence {
        ServiceExecutionContract.exact(schema, "SERVICE_EXECUTION_ORIGINAL_EVIDENCE_V1");
        Objects.requireNonNull(reference, "reference").validate();
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(observedAt, "observedAt");
        ServiceExecutionContract.reason(reason, outcome != EvidenceOutcome.PROVEN);
        if (outcome == EvidenceOutcome.PROVEN) {
            Objects.requireNonNull(facts, "facts");
            ExecutionEvidenceContract.require(facts.quantity() == 1 && "COUNT".equals(facts.uom())
                    && facts.prepareResult().resultRevision() == reference.preparedGrantRevision()
                    && facts.preparedGrantRevision() == reference.preparedGrantRevision()
                    && facts.originalBindingBytesHash().equals(reference.originalBindingBytesHash())
                    && facts.originalBindingBytesHash().equals(
                            ExecutionEvidenceContract.bindingBytesHash(facts.originalBindingJson())));
        } else ExecutionEvidenceContract.require(facts == null);
        if (proofHash != null) ServiceExecutionContract.hash(proofHash);
    }

    public ExecutionOriginalEvidence signed() {
        return new ExecutionOriginalEvidence(schema, reference, outcome, reason, facts, observedAt,
                ExecutionEvidenceContract.digest("SERVICE_EXECUTION_ORIGINAL_EVIDENCE_V1", this, "proofHash"));
    }

    public void validate() {
        ExecutionEvidenceContract.require(equals(signed()));
    }
}
