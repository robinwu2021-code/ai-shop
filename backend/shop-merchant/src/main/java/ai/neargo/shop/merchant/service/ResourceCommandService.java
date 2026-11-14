package ai.neargo.shop.merchant.service;

import static ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.EvidenceOutcome.PROVEN;

import ai.neargo.shop.spi.resource.ResourceOccupationContract.AcceptedStart;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.ActualOriginal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.AppliedStart;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.CancelOriginal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalLookup;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityOriginal;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Trusted owner-local command journal, not an industry authorization API or a capacity reservation service.
 * Industry adapters must derive an authorized server scope and stable payload bytes before entering this kernel.
 */
public interface ResourceCommandService {
    OccupationReceipt acceptOccupation(AcceptedStart request);
    Optional<OccupationReceipt> findOccupation(OriginalLookup original);
    OccupationExecution lockOccupationForExecution(Scope scope, String commandNo, long version);
    Scope requireOccupationExecution(OccupationExecution execution);
    void completeOccupation(OccupationExecution execution, AppliedStart result);
    void sealBeforeEffect(ValidatedCancellation cancellation);
    record ValidatedCancellation(CancelOriginal cancellation,
            StartAuthorityOriginal sourceOriginal,
            ExecutionOriginalEvidence coreOriginal, NativeRejection nativeRejection) {
        public ValidatedCancellation {
            cancellation.original().validate(); coreOriginal.validate();
            ExecutionEvidenceContract.require(coreOriginal.reference().equals(cancellation.original().reference())
                    && coreOriginal.outcome() == PROVEN);
            if (nativeRejection == null) {
                sourceOriginal.validate();
                ExecutionEvidenceContract.require(sourceOriginal.cancellationRequested()
                        && sourceOriginal.original().equals(cancellation.original().source())
                        && sourceOriginal.cancellationActionNo().equals(cancellation.cancellationActionNo())
                        && sourceOriginal.cancellationActionHash().equals(cancellation.cancellationActionHash())
                        && sourceOriginal.cancellationCause().equals(cancellation.cause()));
            } else ExecutionEvidenceContract.require(sourceOriginal == null
                    && nativeRejection.reason().equals(cancellation.cause())
                    && nativeRejection.proofHash().equals(ExecutionEvidenceContract.hash(
                    "RESOURCE_NATIVE_REJECTION_V1", List.of(nativeRejection.actual(), nativeRejection.reason(), nativeRejection.acceptedHash()))));
        }
    }
    record NativeRejection(String reason, ActualOriginal actual,
            String acceptedHash, Instant observedAt, String proofHash) { }

    record OccupationReceipt(Scope scope, ActualOriginal actual,
            String status, long version, String frozenPayload, String resultPayload) { }
    interface OccupationExecution {
        OccupationReceipt command();
        OwnerHead owner();
    }
    /** Owns a short transaction so a later business rollback cannot erase the original accepted intent. */
    Receipt accept(Accept request);

    /** Current scoped authoritative command state; a command number never grants access by itself. */
    Optional<Receipt> find(Scope scope, String commandNo);

    /** Versioned recovery checkpoint; never rewrites the original payload, initiator or a terminal result. */
    Receipt markWaiting(Scope scope, String commandNo, long version, String reason, LocalDateTime nextRetryAt);

    /** Must be acquired before local mutations inside the writable primary READ_COMMITTED business transaction. */
    Execution lockForExecution(Scope scope, String commandNo, long version);

    /** Owner-local guards verify the same live credential without consuming its command or exposing its internals. */
    Scope requireExecution(Execution execution);

    /** Joins the credential's transaction; head CAS and immutable command result commit with caller-local effects. */
    Receipt completeSuccess(Execution execution, String resultingHoldNo, String resultPayload);

    /** Only after proven rollback or known absence of effects; commit-unknown errors must use find instead. */
    Receipt rejectAfterRollback(Scope scope, String commandNo, long version, String code, String resultPayload);

    /** Explicit already-authorized server scope, resolved to database-canonical parents on every operation. */
    record Scope(String tenantNo, String entityNo, String storeNo) {
    }

    /** Journal discriminator is independent of the frozen BOOKING action and receipt bytes. */
    enum Kind { BOOKING_V1, OCCUPATION_V1 }

    /** Existing booking actions only; occupation actions require their own future contract. */
    enum Action {
        RESERVE, PROTECT, CONFIRM, RENEW, RELEASE, EXPIRE, RECONCILE, REPLACE
    }

    /** Terminal journal outcomes do not describe the current lifecycle state of a hold. */
    enum Status {
        ACCEPTED, SUCCEEDED, REJECTED
    }

    /** Frozen expected target revisions; canonical hold identity is resolved before hashing. */
    record Target(String holdNo, long holdVersion, long leaseRevision) {
    }

    /** UTF8 JSON object bytes are stable evidence; callers must not reformat them when replaying a key. */
    record Accept(Scope scope, Action action, String key, String sourceType, String sourceNo,
            long expectedOwnerVersion, long sourceRevision, Target target, String frozenPayload) {
    }

    /** Stable ownership facts protected by the owner root; sourceRevision is null when there is no current head. */
    record OwnerHead(String ownerNo, String currentHoldNo, long ownerVersion, Long sourceRevision) {
    }

    /** Immutable internal recovery evidence; payloads must not be exposed through a public endpoint or logged. */
    record Receipt(Scope scope, String commandNo, String ownerNo, String sourceType, String sourceNo,
            Action action, Status status, long version,
            long expectedOwnerVersion, long sourceRevision, Target target, String payloadHash, String frozenPayload,
            String resultPayload, String rejectionCode, String waitReason, LocalDateTime nextRetryAt,
            String initiatedBy) {
    }

    /**
     * Opaque transaction-bound capability; only owner-issued handles are accepted.
     * Any rollback to a savepoint invalidates existing handles; reacquire before retrying local mutations.
     */
    interface Execution {
        /** Original command snapshot, independent of later head changes. */
        Receipt command();

        /** Root state locked before resource mutations. */
        OwnerHead owner();
    }
}
