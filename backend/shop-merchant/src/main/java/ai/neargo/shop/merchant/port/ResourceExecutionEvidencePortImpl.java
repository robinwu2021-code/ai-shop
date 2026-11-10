package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingOwnerMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceCommandMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationBucketMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationSegmentMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceOccupationUnitMapper;
import ai.neargo.shop.merchant.policy.ResourceOccupationGuard;
import ai.neargo.shop.merchant.policy.ResourceOccupationSchemaVerifier;
import ai.neargo.shop.merchant.service.ResourceCommandService.ValidatedCancellation;
import ai.neargo.shop.spi.resource.ResourceExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.AcceptedStart;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.ActualOriginal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.AppliedStart;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.InitialEffects;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.NoEffectSeal;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalLookup;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.OriginalResult;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.Outcome;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.SealState;
import ai.neargo.shop.spi.trade.ExecutionEvidenceContract;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Independent original reader. No Core, source, HUMAN, retention, or occupation orchestration dependency. */
@Component
@Validated
public class ResourceExecutionEvidencePortImpl implements ResourceExecutionEvidencePort {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final ResourceOccupationSchemaVerifier schema;
    private final ResourceOccupationGuard guard;
    private final BookingOwnerMapper owners;
    private final ResourceCommandMapper commands;
    private final ResourceOccupationMapper occupations;
    private final ResourceOccupationUnitMapper units;
    private final ResourceOccupationSegmentMapper segments;
    private final ResourceOccupationBucketMapper projections;
    private final TransactionTemplate snapshot;

    public ResourceExecutionEvidencePortImpl(ResourceOccupationSchemaVerifier schema, ResourceOccupationGuard guard,
            BookingOwnerMapper owners, ResourceCommandMapper commands, ResourceOccupationMapper occupations,
            ResourceOccupationUnitMapper units, ResourceOccupationSegmentMapper segments,
            ResourceOccupationBucketMapper projections, @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.schema=schema; this.guard=guard; this.owners=owners; this.commands=commands; this.occupations=occupations;
        this.units=units; this.segments=segments; this.projections=projections;
        snapshot = new TransactionTemplate(manager); snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public OriginalResult queryOriginal(OriginalLookup original) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("resource original IO must be outside owner transactions");
        original.validate(); schema.requireAvailable();
        return DataScopeContext.executeWithoutScope(() -> snapshot.execute(status -> load(original)));
    }

    private OriginalResult load(OriginalLookup lookup) {
        var ref = lookup.reference();
        var scope = new ResourceCommandScopeRow(ref.scope().tenantNo(), ref.scope().entityNo(), ref.scope().storeNo());
        var owner = owners.selectBySource(scope, "SERVICE_EXECUTION", ref.executionNo());
        if (owner == null || "UNBOUND".equals(owner.getOccupationAssociationState()))
            return result(lookup, Outcome.UNKNOWN, null, null, null, null, false, false, 0, "ORIGINAL_NOT_OBSERVED");
        require(ref.referenceHash().equals(owner.getExecutionReferenceHash())
                && ref.planHash().equals(owner.getExecutionPlanHash())
                && ref.plannedResourceCommandNo().equals(owner.getPlannedResourceCommandNo())
                && ref.plannedResourceCommandHash().equals(owner.getPlannedResourceCommandHash())
                && JSON.readValue(owner.getExecutionReferenceJson(), ref.getClass()).equals(ref));
        guard.inspectNativeEvidence(scope);
        var start = owner.getInitialStartCommandNo() == null ? null : commands.selectCommand(scope, owner.getInitialStartCommandNo());
        var seals = commands.selectOccupationSeals(scope, owner.getOwnerNo());
        require(seals.size() <= 1);
        AcceptedStart accepted = null;
        ActualOriginal actual = null;
        if (start != null) {
            require("OCCUPATION_V1".equals(start.getCommandKind()) && "START".equals(start.getAction()));
            accepted = JSON.readValue(start.getFrozenPayload(), AcceptedStart.class); accepted.validate();
            actual = new ActualOriginal(owner.getOwnerNo(), start.getCommandNo(), start.getPayloadSchema(), start.getPayloadHash());
            require(accepted.request().reference().equals(ref) && accepted.request().source().equals(lookup.source())
                    && accepted.request().requestKey().equals(lookup.requestKey()) && accepted.request().requestHash().equals(lookup.requestHash())
                    && start.getPayloadHash().equals(ExecutionEvidenceContract.hash(start.getPayloadSchema(), start.getFrozenPayload()))
                    && (lookup.actual() == null || lookup.actual().equals(actual)));
            if ("APPLIED".equals(start.getOccupationOutcome())) {
                var applied = JSON.readValue(start.getResultPayload(), AppliedStart.class); applied.validate();
                require(applied.actual().equals(actual) && applied.accepted().equals(accepted));
                var headers = occupations.selectByOwner(scope, owner.getOwnerNo()); require(headers.size() == 1);
                var header = headers.getFirst();
                require(header.getResultNo().equals(applied.resultNo()) && header.getResultRevision() == applied.resultRevision()
                        && header.getResultHash().equals(applied.resultHash()) && header.getOccupationNo().equals(applied.occupationNo())
                        && header.getStartedAt().toInstant(ZoneOffset.UTC).equals(applied.startedAt())
                        && header.getInitialEffectsHash().equals(applied.initialEffectsHash())
                        && JSON.readValue(header.getInitialEffectsJson(), InitialEffects.class).equals(applied.initialEffects()));
                verifyEffects(scope, applied);
                return result(lookup, Outcome.APPLIED, actual, accepted, applied, null, header.getStopObligation() != 0,
                        header.getConflictObligation() != 0, header.getObservationEpoch(), null);
            }
        }
        if (!seals.isEmpty()) {
            require(lookup.actual() == null || lookup.actual().equals(actual));
            var row = seals.getFirst();
            var validated = JSON.readValue(row.getFrozenPayload(), ValidatedCancellation.class);
            var cancellation = validated.cancellation();
            require(cancellation.original().reference().equals(ref) && cancellation.original().source().equals(lookup.source())
                    && cancellation.original().requestHash().equals(lookup.requestHash())
                    && cancellation.original().requestKey().equals(lookup.requestKey()) && "SUCCEEDED".equals(row.getStatus()));
            var seal = JSON.readValue(row.getResultPayload(), NoEffectSeal.class);
            var journal = new ActualOriginal(row.getOwnerNo(), row.getCommandNo(), row.getPayloadSchema(), row.getPayloadHash());
            require(journal.equals(seal.cancellationJournal()) && Objects.equals(actual, seal.actualStart())
                    && row.getPayloadHash().equals(ExecutionEvidenceContract.hash(row.getPayloadSchema(), row.getFrozenPayload()))
                    && seal.sealHash().equals(ExecutionEvidenceContract.hash("RESOURCE_NO_EFFECT_SEAL_V1", List.of(validated, journal, seal.sealNo())))
                    && seal.resultHash().equals(ExecutionEvidenceContract.hash("RESOURCE_SEAL_RESULT_V1", List.of(seal.sealHash(), seal.resultNo())))
                    && seal.resultNo().equals(row.getOriginalResultNo()) && seal.resultRevision() == row.getOriginalResultRevision()
                    && seal.resultHash().equals(row.getOriginalResultHash()) && seal.sealNo().equals(row.getSealNo())
                    && seal.sealHash().equals(row.getSealHash()) && seal.sealRevision() == row.getSealRevision()
                    && seal.state().name().equals(row.getOccupationOutcome())
                    && Objects.equals(row.getOriginalStartCommandNo(), start == null ? null : start.getCommandNo())
                    && occupations.selectByOwner(scope, owner.getOwnerNo()).isEmpty()
                    && occupations.countOwnerArtifacts(scope, owner.getOwnerNo()) == 0
                    && (start == null && seal.state() == SealState.NEVER_ACCEPTED_SEALED
                    && "SEALED_BEFORE_ACCEPT".equals(owner.getOccupationAssociationState()) && row.getCommandNo().equals(owner.getSealCommandNo())
                    || start != null && "REJECTED".equals(start.getStatus()) && seal.state() == SealState.CANCELLED_BEFORE_EFFECT));
            return result(lookup, Outcome.SEALED_NO_EFFECT, actual, accepted, null, seal, false, false, row.getObservationEpoch(), null);
        }
        require(start != null && "ACCEPTED".equals(start.getStatus())
                && occupations.selectByOwner(scope, owner.getOwnerNo()).isEmpty()
                && occupations.countOwnerArtifacts(scope, owner.getOwnerNo()) == 0);
        return result(lookup, Outcome.ACCEPTED, actual, accepted, null, null, false, false, start.getObservationEpoch(), null);
    }

    private void verifyEffects(ResourceCommandScopeRow scope, AppliedStart applied) {
        var permanent = units.selectOriginal(scope, applied.occupationNo());
        require(permanent.size() == applied.initialEffects().permanentUnits().size());
        for (var expected : applied.initialEffects().permanentUnits()) {
            var row = permanent.stream().filter(u -> u.getOccupationUnitNo().equals(expected.occupationUnitNo())).findFirst().orElseThrow();
            require(row.getDeleted() == 0 && row.getLogicalDemandNo().equals(expected.logicalDemandNo())
                    && row.getQty() == expected.quantity() && row.getOrigin().equals(expected.origin())
                    && row.getConsumedAt().toInstant(ZoneOffset.UTC).equals(expected.consumedAt())
                    && Objects.equals(row.getReservationUnitNo(), expected.reservationUnitNo())
                    && Objects.equals(row.getSourceBucketNo(), expected.sourceBucketNo())
                    && Objects.equals(row.getSourceLineRef(), expected.sourceLineRef())
                    && Objects.equals(row.getHoldNo(), expected.holdNo()) && Objects.equals(row.getBookingOwnerNo(), expected.bookingOwnerNo())
                    && Objects.equals(row.getAllocationRevision(), expected.allocationRevision()));
        }
        var physical = segments.selectScopeOriginals(scope).stream().filter(s -> s.getOccupationNo().equals(applied.occupationNo())
                && s.getCommandNo().equals(applied.actual().commandNo())).toList();
        require(physical.size() == applied.initialEffects().physicalEffects().size());
        for (var expected : applied.initialEffects().physicalEffects()) {
            var row = physical.stream().filter(s -> s.getSegmentNo().equals(expected.segmentNo())).findFirst().orElseThrow();
            require(row.getResourceNo().equals(expected.resourceNo()) && row.getQty() == expected.quantity()
                    && row.getLogicalDemandNo().equals(expected.logicalDemandNo()) && Objects.equals(row.getPersonnelNo(), expected.personnelNo())
                    && row.getSourceUnitsJson().equals(JSON.writeValueAsString(expected.sourceUnits()))
                    && row.getSourceUnitsHash().equals(expected.sourceUnitsHash()) && row.getPlanJson().equals(expected.planJson())
                    && row.getQualificationJson().equals(expected.qualificationJson())
                    && row.getActualStartAt().toInstant(ZoneOffset.UTC).equals(expected.actualStartAt())
                    && row.getExpectedEndAt().toInstant(ZoneOffset.UTC).equals(expected.expectedEndAt()));
        }
        var projected = projections.selectOriginal(scope, applied.occupationNo()).stream()
                .filter(p -> physical.stream().anyMatch(s -> s.getSegmentNo().equals(p.getSegmentNo()))).toList();
        require(projected.size() == applied.initialEffects().projections().size());
        for (var expected : applied.initialEffects().projections()) {
            var row = projected.stream().filter(p -> p.getProjectionNo().equals(expected.projectionNo())).findFirst().orElseThrow();
            require(row.getResourceNo().equals(expected.resourceNo()) && row.getQty() == expected.quantity()
                    && row.getSegmentNo().equals(expected.segmentNo()) && row.getLogicalDemandNo().equals(expected.logicalDemandNo())
                    && row.getBucketNo().equals(expected.bucketNo()) && row.getStartAt().toInstant(ZoneOffset.UTC).equals(expected.startAt())
                    && row.getEndAt().toInstant(ZoneOffset.UTC).equals(expected.endAt()) && "OCCUPIED".equals(row.getCategory()));
        }
    }
    private static OriginalResult result(OriginalLookup lookup, Outcome outcome, ActualOriginal actual, AcceptedStart accepted,
            AppliedStart applied, NoEffectSeal seal, boolean stop, boolean conflict, long epoch, String reason) {
        return new OriginalResult("RESOURCE_OCCUPATION_ORIGINAL_V1", lookup, outcome, actual, accepted, applied, seal,
                stop, conflict, epoch, reason, null).signed();
    }
    private static void require(boolean condition) { if (!condition) throw new IllegalStateException("corrupt actual resource original"); }
}
