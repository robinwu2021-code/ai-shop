package ai.neargo.shop.merchant.service;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Trusted owner-local command journal, not an industry authorization API or a capacity reservation service.
 * Industry adapters must derive an authorized server scope and stable payload bytes before entering this kernel.
 */
public interface ResourceCommandService {
    /** Owns a short transaction so a later business rollback cannot erase the original accepted intent. */
    Receipt accept(Accept request);

    /** Current scoped authoritative command state; a command number never grants access by itself. */
    Optional<Receipt> find(Scope scope, String commandNo);

    /** Versioned recovery checkpoint; never rewrites the original payload, initiator or a terminal result. */
    Receipt markWaiting(Scope scope, String commandNo, long version, String reason, LocalDateTime nextRetryAt);

    /** Must be acquired before local mutations inside the writable primary READ_COMMITTED business transaction. */
    Execution lockForExecution(Scope scope, String commandNo, long version);

    /** Joins the credential's transaction; head CAS and immutable command result commit with caller-local effects. */
    Receipt completeSuccess(Execution execution, String resultingHoldNo, String resultPayload);

    /** Only after proven rollback or known absence of effects; commit-unknown errors must use find instead. */
    Receipt rejectAfterRollback(Scope scope, String commandNo, long version, String code, String resultPayload);

    /** Explicit already-authorized server scope, resolved to database-canonical parents on every operation. */
    record Scope(String tenantNo, String entityNo, String storeNo) {
    }

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
    record Receipt(String commandNo, String ownerNo, String sourceType, String sourceNo,
            Action action, Status status, long version,
            long expectedOwnerVersion, long sourceRevision, Target target, String frozenPayload,
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
