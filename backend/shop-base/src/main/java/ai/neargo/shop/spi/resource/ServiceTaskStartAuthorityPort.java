package ai.neargo.shop.spi.resource;

import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.ComponentRouteV2;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.Continuity;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence.Suspension;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.SourceSuspension;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Source-owned current HUMAN gate and separate immutable MACHINE history. Neither replays a saved session. */
public interface ServiceTaskStartAuthorityPort {
    StartAuthority authorizeCurrent(@Valid StartAuthorityLookup original);
    StartAuthorityOriginal queryOriginal(@Valid StartAuthorityLookup original);

    record StartAuthorityLookup(@NotNull @Valid ExecutionReference reference, @NotBlank @Size(max = 32) String submissionNo,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String frozenHash) { }

    record SourceGroup(String sourceLineRef, String requirementNo, int resourceOrdinal, String roleCode,
            String resourceNo, String personnelNo) { }

    record AcceptedStaffRef(String assignmentNo, long assignmentRevision, String requirementNo, int ordinal,
            String roleCode, String resourceNo, String personnelNo, String acceptActionNo, String recordHash,
            String qualificationHash, DelegatedIdentity identity, String canonicalAccountNo,
            OperatorPersonnelFact personnel) { }

    record OwnSlot(String requirementNo, int ordinal, long assignmentRevision) { }

    /** Whole original arrangement is distinct from the selected complete task groups. */
    record BookingOriginal(String appointmentNo, String appointmentItemNo, long arrangementRevision,
            String arrangementHash, String bookingOwnerNo, String holdNo, long leaseRevision,
            long allocationRevision, String wholePlanJson, String wholePlanHash) { }

    record StartAuthority(String schema, StartAuthorityLookup original, long observationVersion,
            String actionNo, String actionHash, String originalTaskPlanSchema, String originalTaskPlanJson,
            String originalTaskPlanHash, String resourcePlanJson, String resourcePlanHash,
            List<SourceGroup> sourceGroups, BookingOriginal booking, List<AcceptedStaffRef> acceptedRoster,
            List<OwnSlot> ownSlots, List<ComponentRouteV2> retentionRoster, Continuity continuity, DelegatedIdentity actor, String canonicalAccountNo,
            OperatorPersonnelFact currentPersonnel, Instant issuedAt, Instant validUntil, String proofHash) {
        public StartAuthority {
            sourceGroups = List.copyOf(sourceGroups);
            acceptedRoster = List.copyOf(acceptedRoster);
            ownSlots = List.copyOf(ownSlots);
            retentionRoster = List.copyOf(retentionRoster);
        }

        public StartAuthority signed() {
            return new StartAuthority(schema, original, observationVersion, actionNo, actionHash,
                    originalTaskPlanSchema, originalTaskPlanJson, originalTaskPlanHash, resourcePlanJson,
                    resourcePlanHash, sourceGroups, booking, acceptedRoster, ownSlots, retentionRoster, continuity, actor,
                    canonicalAccountNo, currentPersonnel, issuedAt, validUntil,
                    ExecutionEvidenceContract.digest("SERVICE_TASK_START_AUTHORITY_V1", this, "proofHash"));
        }

        public void validate() {
            original.reference().validate();
            ExecutionEvidenceContract.require("SERVICE_TASK_START_AUTHORITY_V1".equals(schema)
                    && equals(signed()) && originalTaskPlanHash.equals(original.reference().planHash())
                    && resourcePlanHash.equals(ResourcePlanContract.hash(resourcePlanJson))
                    && issuedAt.isBefore(validUntil) && !acceptedRoster.isEmpty() && !ownSlots.isEmpty());
        }
    }

    enum OriginalState { PROVEN, PENDING, UNKNOWN }
    enum CancellationPurpose { SOURCE_SUSPENSION, CORE_SUSPENSION, EXPLICIT_ABORT }

    /** Cancellation is sticky persisted source evidence; a nonterminal observation never grants permission. */
    record StartAuthorityOriginal(String schema, StartAuthorityLookup original, OriginalState state,
            String actionNo, String actionHash, String originalTaskPlanHash, String semanticFenceHash,
            boolean cancellationRequested, String cancellationActionNo, String cancellationActionHash,
            String cancellationCause, CancellationPurpose cancellationPurpose,
            SourceSuspension sourceSuspension,
            Suspension coreSuspension,
            long observationVersion, String proofHash) {
        public StartAuthorityOriginal signed() {
            return new StartAuthorityOriginal(schema, original, state, actionNo, actionHash, originalTaskPlanHash,
                    semanticFenceHash, cancellationRequested, cancellationActionNo, cancellationActionHash,
                    cancellationCause, cancellationPurpose, sourceSuspension, coreSuspension, observationVersion,
                    ExecutionEvidenceContract.digest("SERVICE_TASK_START_ORIGINAL_V1", this, "proofHash"));
        }

        public void validate() {
            original.reference().validate();
            ExecutionEvidenceContract.require("SERVICE_TASK_START_ORIGINAL_V1".equals(schema)
                    && equals(signed()) && (!cancellationRequested || state == OriginalState.PROVEN
                    && cancellationActionNo != null && cancellationActionHash != null && cancellationCause != null
                    && cancellationPurpose != null));
        }
    }
}
