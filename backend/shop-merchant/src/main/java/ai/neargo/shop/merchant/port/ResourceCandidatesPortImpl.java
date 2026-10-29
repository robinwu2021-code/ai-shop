package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchBookingHoldUnit;
import ai.neargo.shop.merchant.entity.MchResourceBucket;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Advisory reads include every unreleased hold; expiration never silently manufactures free capacity. */
@Component
public class ResourceCandidatesPortImpl implements ResourceCandidatesPort {
    private final MerchantMappers.ResourceMapper resources;
    private final MerchantMappers.ResourceBucketMapper buckets;
    private final MerchantMappers.BookingHoldUnitMapper units;
    private final ResourceFactsPort facts;
    private final MerchantMappers.BookingOwnerMapper owners;
    private final MerchantMappers.BookingHoldMapper holds;
    private final ResourceBookingPort booking;
    public ResourceCandidatesPortImpl(MerchantMappers.ResourceMapper resources,
            MerchantMappers.ResourceBucketMapper buckets, MerchantMappers.BookingHoldUnitMapper units,
            ResourceFactsPort facts, MerchantMappers.BookingOwnerMapper owners,
            MerchantMappers.BookingHoldMapper holds, ResourceBookingPort booking) {
        this.resources = resources;
        this.buckets = buckets;
        this.units = units;
        this.facts = facts;
        this.owners = owners;
        this.holds = holds;
        this.booking = booking;
    }
    @Override
    public List<ResourceFactsPort.ResourceFact> candidates(Query query) {
        outside();
        var s = query.scope();
        var ids = DataScopeContext.executeWithoutScope(() -> resources.selectCandidateIds(s.tenantNo(),
                s.entityNo(), s.storeNo(), LocalDateTime.ofInstant(query.at(), ZoneOffset.UTC),
                query.publicOnly(), query.limit()));
        return ids.isEmpty() ? List.of() : facts.getBatch(new ResourceFactsPort.Query(s.tenantNo(), s.entityNo(),
                s.storeNo(), new HashSet<>(ids), query.at()));
    }
    @Override
    public List<Capacity> capacity(CapacityQuery query) {
        outside();
        var s = query.scope();
        var scope = new ResourceCommandScopeRow(s.tenantNo(), s.entityNo(), s.storeNo());
        return DataScopeContext.executeWithoutScope(() -> {
            if (units.hasBookingOrphans(scope)) { throw new IllegalStateException("resource allocation needs repair"); }
            var known = facts.getBatch(new ResourceFactsPort.Query(s.tenantNo(), s.entityNo(), s.storeNo(),
                    query.resourceNos(), query.factAt()));
            if (known.size() != query.resourceNos().size()) {
                throw new IllegalArgumentException("unknown scoped resources");
            }
            var rows = buckets.selectList(Wrappers.<MchResourceBucket>lambdaQuery()
                    .eq(MchResourceBucket::getTenantNo, s.tenantNo()).eq(MchResourceBucket::getEntityNo, s.entityNo())
                    .in(MchResourceBucket::getResourceNo, query.resourceNos())
                    .lt(MchResourceBucket::getStartAt, LocalDateTime.ofInstant(query.to(), ZoneOffset.UTC))
                    .gt(MchResourceBucket::getEndAt, LocalDateTime.ofInstant(query.from(), ZoneOffset.UTC))
                    .last("LIMIT 10001"));
            if (rows.size() > 10000) { throw new IllegalArgumentException("capacity window too large"); }
            var excluded = replacementUnits(query, scope);
            List<Capacity> result = new ArrayList<>();
            for (var row : rows) {
                var count = buckets.selectBookingCounters(scope, row.getBucketNo());
                if (count.invalid() != 0 || count.held() != row.getHeldQty()
                        || count.confirmed() != row.getConfirmedQty()) {
                    throw new IllegalStateException("resource capacity counters need repair");
                }
                long used = count.held() + count.confirmed() - excluded.getOrDefault(row.getBucketNo(), 0L);
                if (used < 0) { throw new IllegalStateException("replacement capacity evidence changed"); }
                result.add(new Capacity(row.getResourceNo(), row.getStartAt().toInstant(ZoneOffset.UTC),
                        row.getEndAt().toInstant(ZoneOffset.UTC), row.getCapacity(), Math.toIntExact(used)));
            }
            return List.copyOf(result);
        });
    }
    private Map<String, Long> replacementUnits(CapacityQuery query, ResourceCommandScopeRow scope) {
        if (query.replacement() == null) { return Map.of(); }
        var replacement = query.replacement();
        var b = replacement.binding();
        var s = query.scope();
        if (!s.tenantNo().equals(b.tenantNo()) || !s.entityNo().equals(b.entityNo())
                || !s.storeNo().equals(b.storeNo())) {
            throw new IllegalArgumentException("replacement scope mismatch");
        }
        var original = booking.reconcile(b).orElseThrow(
                () -> new IllegalArgumentException("original replacement command unavailable"));
        var owner = owners.selectBySource(scope, b.sourceType(), b.sourceNo());
        var target = replacement.target();
        var hold = holds.selectOwnedHold(scope, original.ownerNo(), target.holdNo());
        // Both the original receipt and the current owner/hold must agree; stale history cannot free capacity.
        if (!"SUCCEEDED".equals(original.status()) || owner == null || hold == null
                || !original.ownerNo().equals(owner.getOwnerNo())
                || !target.holdNo().equals(owner.getCurrentHoldNo())
                || owner.getOwnerVersion() != original.expectedOwnerVersion() + 1
                || owner.getSourceRevision() != b.sourceRevision()
                || hold.getSourceRevision() != b.sourceRevision()
                || !List.of("RESERVE", "CONFIRM", "REPLACE").contains(b.action())
                || !List.of("HELD", "CONFIRMED").contains(hold.getStatus())
                || hold.getVersion() != target.holdVersion() || hold.getLeaseRevision() != target.leaseRevision()
                || !b.planHash().equals(hold.getCurrentPlanHash())) {
            throw new IllegalArgumentException("replacement is not the current original hold");
        }
        var rows = units.selectList(Wrappers.<MchBookingHoldUnit>lambdaQuery()
                .eq(MchBookingHoldUnit::getTenantNo, s.tenantNo())
                .eq(MchBookingHoldUnit::getEntityNo, s.entityNo())
                .eq(MchBookingHoldUnit::getHoldNo, target.holdNo())
                .isNull(MchBookingHoldUnit::getReleasedAt).last("LIMIT 10001"));
        if (rows.size() > 10000) { throw new IllegalArgumentException("replacement allocation too large"); }
        Map<String, Long> result = new HashMap<>();
        for (var row : rows) { result.merge(row.getBucketNo(), (long) row.getQty(), Math::addExact); }
        return result;
    }
    private void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("candidate facts require no caller transaction");
        }
    }
}
