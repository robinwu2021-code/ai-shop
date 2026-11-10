package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingHoldMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingHoldUnitMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingOwnerMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceBucketMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceCommandMapper;
import ai.neargo.shop.merchant.policy.ResourceDemandNormalizer;
import ai.neargo.shop.spi.resource.ResourceBookingExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort.Binding;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * Snapshot reads the actual persisted allocation generation, never an inferred arrangement
 * revision.
 */
@Component
@Validated
public class ResourceBookingExecutionEvidencePortImpl
        implements ResourceBookingExecutionEvidencePort {
    private final BookingOwnerMapper owners;
    private final BookingHoldMapper holds;
    private final BookingHoldUnitMapper units;
    private final ResourceBucketMapper buckets;
    private final ResourceCommandMapper commands;
    private final TransactionTemplate snapshot;

    public ResourceBookingExecutionEvidencePortImpl(
            BookingOwnerMapper owners,
            BookingHoldMapper holds,
            BookingHoldUnitMapper units,
            ResourceBucketMapper buckets,
            ResourceCommandMapper commands,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        this.owners = owners;
        this.holds = holds;
        this.units = units;
        this.buckets = buckets;
        this.commands = commands;
        snapshot = new TransactionTemplate(manager);
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public Evidence queryOriginal(Lookup lookup) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException(
                    "booking execution evidence requires no caller transaction");
        lookup.validate();
        return DataScopeContext.executeWithoutScope(() -> snapshot.execute(status -> read(lookup)));
    }

    private Evidence read(Lookup lookup) {
        var ref = lookup.reference();
        var s = ref.scope();
        var scope = owners.selectScope(s.tenantNo(), s.entityNo(), s.storeNo());
        require(scope != null);
        var command = commands.selectCommand(scope, lookup.binding().commandNo());
        require(command != null);
        var owner = owners.selectOwner(scope, command.getOwnerNo());
        require(owner != null);
        var actualBinding =
                new Binding(
                        command.getTenantNo(),
                        command.getEntityNo(),
                        command.getStoreNo(),
                        command.getCommandNo(),
                        owner.getSourceType(),
                        owner.getSourceNo(),
                        command.getSourceRevision(),
                        command.getAction(),
                        command.getPayloadHash(),
                        ResourcePlanContract.hash(command.getFrozenPayload()));
        require(
                lookup.binding().equals(actualBinding)
                        && "BOOKING_V1".equals(command.getCommandKind())
                        && "SUCCEEDED".equals(command.getStatus())
                        && lookup.holdNo().equals(owner.getCurrentHoldNo())
                        && lookup.wholePlanJson().equals(command.getFrozenPayload()));
        var hold = holds.selectOwnedHold(scope, owner.getOwnerNo(), lookup.holdNo());
        require(
                hold != null
                        && "CONFIRMED".equals(hold.getStatus())
                        && hold.getExecutionStartedAt() == null
                        && hold.getLeaseRevision() == lookup.leaseRevision()
                        && hold.getPlanRevision() > 0
                        && hold.getCurrentPlanHash().equals(lookup.wholePlanHash())
                        && hold.getSourceRevision().equals(owner.getSourceRevision())
                        && hold.getSourceRevision() == lookup.binding().sourceRevision());
        var whole = ResourcePlanContract.decode(lookup.wholePlanJson());
        var facts =
                whole.demands().stream()
                        .map(ResourcePlanContract.Demand::resourceNo)
                        .distinct()
                        .map(
                                resource ->
                                        new ResourceFactsPort.ResourceFact(
                                                resource,
                                                ResourceFactsPort.Type.ROOM,
                                                s.storeNo(),
                                                null,
                                                resource,
                                                Integer.MAX_VALUE,
                                                false))
                        .toList();
        var expected =
                ResourceDemandNormalizer.normalize(
                                whole.demands().stream()
                                        .map(
                                                d ->
                                                        new ResourceDemandNormalizer.Demand(
                                                                d.resourceNo(),
                                                                d.sourceLineRef(),
                                                                d.quantity(),
                                                                d.intervals().stream()
                                                                        .map(
                                                                                i ->
                                                                                        new ResourceDemandNormalizer
                                                                                                .Interval(
                                                                                                i
                                                                                                        .startAt(),
                                                                                                i
                                                                                                        .endAt()))
                                                                        .toList()))
                                        .toList(),
                                facts)
                        .units()
                        .stream()
                        .map(
                                u ->
                                        new Unit(
                                                u.resourceNo(),
                                                u.sourceLineRef(),
                                                u.startAt(),
                                                u.endAt(),
                                                u.quantity()))
                        .sorted(UNIT_ORDER)
                        .toList();
        var allocations = new ArrayList<Allocation>();
        for (var row : units.selectWholeOccupationOriginal(scope, lookup.holdNo())) {
            var bucket = buckets.selectOccupationBucket(scope, row.getBucketNo());
            require(
                    bucket != null
                            && row.getDeleted() == 0
                            && row.getReleasedAt() == null
                            && row.getAllocationRevision().equals(hold.getPlanRevision()));
            allocations.add(
                    new Allocation(
                            row.getUnitNo(),
                            row.getBucketNo(),
                            bucket.getResourceNo(),
                            row.getSourceLineRef(),
                            bucket.getStartAt().toInstant(ZoneOffset.UTC),
                            bucket.getEndAt().toInstant(ZoneOffset.UTC),
                            row.getQty(),
                            row.getAllocationRevision(),
                            row.getConsumedAt() != null
                                    || row.getConsumeCommandNo() != null
                                    || row.getConsumeOccupationUnitNo() != null));
        }
        require(
                expected.equals(
                        allocations.stream()
                                .map(
                                        a ->
                                                new Unit(
                                                        a.resourceNo(),
                                                        a.sourceLineRef(),
                                                        a.startAt(),
                                                        a.endAt(),
                                                        a.quantity()))
                                .sorted(UNIT_ORDER)
                                .toList()));
        var result =
                new Evidence(
                                lookup,
                                owner.getOwnerNo(),
                                hold.getPlanRevision(),
                                allocations,
                                Instant.now(),
                                null)
                        .signed();
        result.validate();
        return result;
    }

    private record Unit(String resource, String group, Instant start, Instant end, int quantity) {}

    private static final Comparator<Unit> UNIT_ORDER =
            Comparator.comparing(Unit::resource)
                    .thenComparing(Unit::group)
                    .thenComparing(Unit::start)
                    .thenComparing(Unit::end)
                    .thenComparingInt(Unit::quantity);

    private static void require(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.CONFLICT);
    }
}
