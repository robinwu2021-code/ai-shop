package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;

import java.util.Objects;
import java.util.Set;

/** Source owners freeze the real plan before Core IO and retain separate current observations. */
public interface ServiceExecutionSourcePort {
    /** Freezes under current human authority; the command supplies no actor or unit ordinal. */
    StartIntent freeze(FreezeStart request);

    /** Human inspection returns an existing exact original, without minting another intent. */
    StartIntent inspect(StartIntentLookup request);

    /** Original recovery retains persisted identity without impersonating its original employee. */
    StartIntent inspectOriginal(StartIntentLookup request);

    /** Expected revisions reject stale human commands before a permanent fence can be created. */
    record FreezeStart(ExecutionScope scope, String workOrderNo, String taskNo, long expectedTaskRevision,
            long expectedBindingRevision, long expectedScopeRevision, String requestKey) {
        public FreezeStart {
            Objects.requireNonNull(scope, "scope");
            ServiceExecutionContract.reference(workOrderNo, 32);
            ServiceExecutionContract.reference(taskNo, 32);
            ServiceExecutionContract.nonnegative(expectedTaskRevision);
            ServiceExecutionContract.positive(expectedBindingRevision);
            ServiceExecutionContract.positive(expectedScopeRevision);
            ServiceExecutionContract.reference(requestKey, 128);
        }

        @Override
        public String toString() {
            return "FreezeStart[REDACTED]";
        }
    }

    /** The full frozen binding prevents inspection from crossing tasks or original attempts. */
    record StartIntentLookup(ExecutionBinding binding) {
        public StartIntentLookup {
            Objects.requireNonNull(binding, "binding");
        }
    }

    /** Pending observation retains the last durable intent rather than granting or releasing it. */
    enum IntentState {
        FROZEN, EXECUTION_PENDING, PREPARED_OBSERVED, REJECTED, SUSPEND_REQUESTED
    }

    /** Original plan/person evidence is immutable; current continuity and first suspension are separate. */
    record StartIntent(ExecutionBinding binding, IntentState state, long intentRevision, String admissionKind,
            String customerPartyDigest, OperatorPersonnelFact personnel, String operatorNamespaceHash,
            String resourceCommandNo, String resourceCommandHash, String frozenPlan, String frozenPlanHash,
            SourceContinuity continuity, SourceSuspension suspension) {
        public StartIntent {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(state, "state");
            ServiceExecutionContract.positive(intentRevision);
            ServiceExecutionContract.exact(admissionKind, "MANUAL_OFFLINE");
            ServiceExecutionContract.hash(customerPartyDigest);
            Objects.requireNonNull(personnel, "personnel");
            ServiceExecutionContract.hash(operatorNamespaceHash);
            ServiceExecutionContract.reference(resourceCommandNo, 32);
            ServiceExecutionContract.hash(resourceCommandHash);
            ServiceExecutionContract.objectJson(frozenPlan, 262144);
            ServiceExecutionContract.hash(frozenPlanHash);
            if (!binding.planHash().equals(frozenPlanHash)) {
                throw new IllegalArgumentException("frozen original plan mismatch");
            }
            Objects.requireNonNull(continuity, "continuity");
            if (state == IntentState.SUSPEND_REQUESTED && suspension == null) {
                throw new IllegalArgumentException("durable suspension cause required");
            }
            if (suspension != null) {
                ServiceExecutionContract.requireSuspensionBinding(binding, suspension);
                if (suspension.intentRevision() > intentRevision) {
                    throw new IllegalArgumentException("suspension exceeds durable intent revision");
                }
            }
        }

        @Override
        public String toString() {
            return "StartIntent[REDACTED]";
        }
    }

    /** A refresh PENDING is not definitive withdrawal and never overwrites immutable source evidence. */
    record SourceContinuity(long manualEpoch, long observationVersion, String state, Long sourceRevision,
            String sourceHash, String reasonCode) {
        public SourceContinuity {
            ServiceExecutionContract.nonnegative(manualEpoch);
            ServiceExecutionContract.nonnegative(observationVersion);
            if (!Set.of("READY_UNCHANGED", "PENDING", "SUSPENDED").contains(state)) {
                throw new IllegalArgumentException("invalid source continuity");
            }
            ServiceExecutionContract.observed(sourceRevision, sourceHash);
            ServiceExecutionContract.reason(reasonCode, !"READY_UNCHANGED".equals(state));
            if ("SUSPENDED".equals(state)) {
                ServiceExecutionContract.suspensionReason(reasonCode);
            }
            if ("READY_UNCHANGED".equals(state) && (sourceRevision == null || reasonCode != null)) {
                throw new IllegalArgumentException("ready continuity requires complete unchanged evidence");
            }
        }
    }

    /** First persisted contradiction proves a separate current epoch; proofHash never hashes itself. */
    record SourceSuspension(String fenceNo, String executionNo, String bindingHash, long intentRevision,
            long manualEpoch, long observationVersion, Long observedSourceRevision, String observedSourceHash,
            String reasonCode, String proofHash) {
        public SourceSuspension {
            ServiceExecutionContract.reference(fenceNo, 32);
            ServiceExecutionContract.reference(executionNo, 32);
            ServiceExecutionContract.hash(bindingHash);
            ServiceExecutionContract.positive(intentRevision);
            ServiceExecutionContract.nonnegative(manualEpoch);
            ServiceExecutionContract.nonnegative(observationVersion);
            ServiceExecutionContract.observedNullable(observedSourceRevision, observedSourceHash);
            ServiceExecutionContract.suspensionReason(reasonCode);
            ServiceExecutionContract.hash(proofHash);
        }
    }
}
