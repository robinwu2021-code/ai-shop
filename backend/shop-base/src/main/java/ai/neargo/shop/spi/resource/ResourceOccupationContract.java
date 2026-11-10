package ai.neargo.shop.spi.resource;

import static ai.neargo.shop.spi.product.ExecutionStockRetentionPort.Outcome.RETAINED;
import static ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation.SCHEMA;

import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort.OperatorPersonnelFact;
import ai.neargo.shop.spi.product.ExecutionStockRetentionAggregateContract.AggregateReceiptV2;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthority;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityLookup;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionReference;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** Independent actual-resource domains; immutable planned BTRC bytes and all legacy contracts remain intact. */
public final class ResourceOccupationContract {
    private ResourceOccupationContract() { }

    public record StartRequest(@NotNull @Valid ExecutionReference reference,
            @NotNull @Valid StartAuthorityLookup source, @NotBlank @Size(max = 128) String requestKey,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String requestHash) {
        public OriginalLookup lookup() { return new OriginalLookup(reference, source, requestKey, requestHash, null); }
        public void validate() {
            reference.validate();
            ExecutionEvidenceContract.require(reference.equals(source.reference())
                    && requestHash.equals(stableRequestHash(reference, source, requestKey)));
        }
    }

    public static String stableRequestHash(ExecutionReference reference, StartAuthorityLookup source, String key) {
        return ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_REQUEST_V1", List.of(reference, source, key));
    }

    public record PlannedOriginal(ExecutionReference reference, String plannedCommandNo, String plannedCommandHash,
            String planHash) {
        public static PlannedOriginal of(ExecutionReference reference) {
            return new PlannedOriginal(reference, reference.plannedResourceCommandNo(),
                    reference.plannedResourceCommandHash(), reference.planHash());
        }
    }

    public record ActualOriginal(String ownerNo, String commandNo, String payloadSchema, String payloadHash) { }

    public record OriginalLookup(@NotNull @Valid ExecutionReference reference,
            @NotNull @Valid StartAuthorityLookup source, @NotBlank @Size(max = 128) String requestKey,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String requestHash, ActualOriginal actual) {
        public void validate() { new StartRequest(reference, source, requestKey, requestHash).validate(); }
    }

    public record CancelOriginal(@NotNull @Valid OriginalLookup original, @NotBlank @Size(max = 32) String cancellationActionNo,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String cancellationActionHash, @NotBlank @Size(max = 64) String cause) { }

    public record GrantWitness(long id, long revision, String storeNo, String roleCode, int deleted) { }
    public record RoleWitness(long id, long revision, String entityNo, String roleCode, String permissions, int deleted) { }
    public record AccessWitness(String canonicalAccountNo, String personnelNo, long accountRevision,
            List<GrantWitness> grants, List<RoleWitness> roles, OperatorPersonnelFact personnel) {
        public AccessWitness { grants = List.copyOf(grants); roles = List.copyOf(roles); }
    }

    public record QualificationWitness(String resourceNo, String personnelNo, long resourceRevision,
            List<ResourceQualificationPort.QualifiedGroup> roleQualifications,
            List<ResourceQualificationPort.ScheduleWitness> continuousSchedules,
            ResourceQualificationPort.EmploymentWitness continuousEmployment) {
        public QualificationWitness {
            roleQualifications = List.copyOf(roleQualifications);
            continuousSchedules = List.copyOf(continuousSchedules);
        }
    }

    public record AcceptedStart(String schema, StartRequest request, PlannedOriginal planned,
            StartAuthority source, ExecutionOriginalEvidence coreOriginal, RetentionLookup retentionLookup,
            AggregateReceiptV2 retention, Instant acceptedAt, String acceptedHash) {
        public AcceptedStart signed() {
            return new AcceptedStart(schema, request, planned, source, coreOriginal, retentionLookup, retention,
                    acceptedAt, ExecutionEvidenceContract.digest("RESOURCE_OCCUPATION_ACCEPTED_V1", this, "acceptedHash"));
        }
        public void validate() {
            request.validate(); source.validate(); coreOriginal.validate();
            ExecutionEvidenceContract.require("RESOURCE_OCCUPATION_ACCEPTED_V1".equals(schema) && equals(signed())
                    && planned.equals(PlannedOriginal.of(request.reference()))
                    && source.original().equals(request.source()) && coreOriginal.reference().equals(request.reference())
                    && retentionLookup.reference().equals(request.reference())
                    && coreOriginal.outcome() == ExecutionOriginalEvidence.EvidenceOutcome.PROVEN
                    && coreOriginal.facts().frozenPlan().equals(source.originalTaskPlanJson())
                    && coreOriginal.facts().prepareResult().actionNo().equals(request.reference().prepareActionNo())
                    && coreOriginal.facts().prepareResult().actionHash().equals(request.reference().prepareActionHash())
                    && coreOriginal.facts().prepareResult().keyHash().equals(request.reference().prepareKeyHash())
                    && retentionLookup.stockOriginal().equals(coreOriginal.facts().stockOriginal())
                    && retentionLookup.confirmationOperationNo().equals(coreOriginal.facts().stockConfirmationOperationNo())
                    && retention.outcome() == RETAINED);
            new ExecutionStockRetentionPreparationObservation(
                    SCHEMA,
                    retention, source.retentionRoster()).validate(retentionLookup);
        }
    }

    /** Original booking unit consumption and physical capacity effects intentionally have different quantities. */
    public record PermanentUnit(String occupationUnitNo, String logicalDemandNo, String origin,
            String sourceGroupRef, String resourceNo, String reservationUnitNo, String holdNo, String bookingOwnerNo,
            String sourceLineRef, Long allocationRevision, String sourceBucketNo, int quantity, Instant consumedAt) { }
    public record InitialSegment(String segmentNo, String logicalDemandNo, String resourceNo, String personnelNo,
            int quantity, List<String> sourceUnits, String sourceUnitsHash, String planJson,
            String qualificationJson, Instant actualStartAt, Instant expectedEndAt) {
        public InitialSegment { sourceUnits = List.copyOf(sourceUnits); }
    }
    public record InitialProjection(String projectionNo, String segmentNo, String logicalDemandNo,
            String resourceNo, String bucketNo, Instant startAt, Instant endAt, int quantity) { }

    public record InitialEffects(List<PermanentUnit> permanentUnits, List<PermanentUnit> bookingConsumption, List<InitialSegment> physicalEffects,
            List<InitialProjection> projections, List<AccessWitness> access,
            List<QualificationWitness> qualifications, String physicalPlanHash) {
        public InitialEffects {
            permanentUnits = permanentUnits.stream().sorted(Comparator.comparing(PermanentUnit::occupationUnitNo)).toList();
            bookingConsumption = bookingConsumption.stream().sorted(Comparator.comparing(PermanentUnit::occupationUnitNo)).toList();
            physicalEffects = physicalEffects.stream().sorted(Comparator.comparing(InitialSegment::segmentNo)).toList();
            projections = projections.stream().sorted(Comparator.comparing(InitialProjection::projectionNo)).toList();
            access = List.copyOf(access); qualifications = List.copyOf(qualifications);
        }
    }

    public record AppliedStart(String schema, PlannedOriginal planned, ActualOriginal actual,
            String resultNo, long resultRevision, String resultHash, String occupationNo,
            Instant startedAt, AcceptedStart accepted, InitialEffects initialEffects,
            String initialEffectsHash, String proofHash) {
        public AppliedStart signed() {
            return new AppliedStart(schema, planned, actual, resultNo, resultRevision, resultHash, occupationNo,
                    startedAt, accepted, initialEffects, initialEffectsHash,
                    ExecutionEvidenceContract.digest("RESOURCE_OCCUPATION_APPLIED_V1", this, "proofHash"));
        }
        public void validate() {
            accepted.validate();
            ExecutionEvidenceContract.require("RESOURCE_OCCUPATION_APPLIED_V1".equals(schema) && equals(signed())
                    && planned.equals(accepted.planned()) && resultRevision > 0
                    && initialEffects.bookingConsumption().equals(initialEffects.permanentUnits().stream()
                            .filter(unit -> "BOOKING".equals(unit.origin())).toList())
                    && initialEffectsHash.equals(ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_EFFECTS_V1", initialEffects))
                    && resultHash.equals(ResourceOccupationContract.resultHash(actual, occupationNo, startedAt, initialEffectsHash)));
        }
    }

    public static String resultHash(ActualOriginal actual, String occupationNo, Instant at, String effectsHash) {
        return ExecutionEvidenceContract.hash("RESOURCE_OCCUPATION_RESULT_V1", List.of(actual, occupationNo, at, effectsHash));
    }

    public enum SealState { NEVER_ACCEPTED_SEALED, CANCELLED_BEFORE_EFFECT }
    public record NoEffectSeal(SealState state, ActualOriginal actualStart, ActualOriginal cancellationJournal,
            String sealNo, long sealRevision, String sealHash, int effectCount,
            String resultNo, long resultRevision, String resultHash) {
        public NoEffectSeal {
            ExecutionEvidenceContract.require(effectCount == 0 && cancellationJournal != null && sealNo != null
                    && sealHash != null && sealRevision > 0 && resultNo != null && resultRevision > 0
                    && resultHash != null && "RESOURCE_OCCUPATION_SEAL_START_V1".equals(cancellationJournal.payloadSchema())
                    && (state == SealState.NEVER_ACCEPTED_SEALED && actualStart == null
                    || state == SealState.CANCELLED_BEFORE_EFFECT && actualStart != null
                    && "RESOURCE_OCCUPATION_START_V1".equals(actualStart.payloadSchema())
                    && actualStart.ownerNo().equals(cancellationJournal.ownerNo())));
        }
    }
    public enum Outcome { APPLIED, ACCEPTED, SEALED_NO_EFFECT, UNKNOWN, PENDING }
    public record OriginalResult(String schema, OriginalLookup original, Outcome outcome, ActualOriginal actual,
            AcceptedStart accepted, AppliedStart applied, NoEffectSeal seal, boolean stopRequired,
            boolean conflictRequired, long observationEpoch, String reason, String proofHash) {
        public OriginalResult signed() {
            return new OriginalResult(schema, original, outcome, actual, accepted, applied, seal, stopRequired,
                    conflictRequired, observationEpoch, reason,
                    ExecutionEvidenceContract.digest("RESOURCE_OCCUPATION_ORIGINAL_V1", this, "proofHash"));
        }
        public void validate() {
            original.validate();
            ExecutionEvidenceContract.require("RESOURCE_OCCUPATION_ORIGINAL_V1".equals(schema) && equals(signed())
                    && switch (outcome) {
                        case APPLIED -> actual != null && accepted != null && applied != null && seal == null;
                        case ACCEPTED -> actual != null && accepted != null && applied == null && seal == null;
                        case SEALED_NO_EFFECT -> seal != null && applied == null && (seal.state() == SealState.NEVER_ACCEPTED_SEALED
                                ? actual == null && accepted == null : actual != null && accepted != null);
                        case UNKNOWN, PENDING -> actual == null && accepted == null && applied == null && seal == null;
                    });
            if (accepted != null) {
                accepted.validate();
                ExecutionEvidenceContract.require(accepted.request().reference().equals(original.reference())
                        && accepted.request().source().equals(original.source())
                        && accepted.request().requestHash().equals(original.requestHash())
                        && accepted.request().requestKey().equals(original.requestKey()));
            }
            if (applied != null) {
                applied.validate();
                ExecutionEvidenceContract.require(applied.actual().equals(actual) && applied.accepted().equals(accepted));
            }
            if (outcome != Outcome.UNKNOWN && outcome != Outcome.PENDING && original.actual() != null)
                ExecutionEvidenceContract.require(original.actual().equals(actual));
        }
    }
    public record StartResult(OriginalResult original) { }
    public record CancellationResult(OriginalResult original) { }
}
