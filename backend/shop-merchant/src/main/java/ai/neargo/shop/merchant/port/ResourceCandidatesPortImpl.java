package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.policy.ResourceOccupationGuard;
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
    private final ResourceOccupationGuard occupations;
    private final MerchantMappers.ResourceMapper resources;
    private final MerchantMappers.ResourceBucketMapper buckets;
    private final MerchantMappers.BookingHoldUnitMapper units;
    private final ResourceFactsPort facts;
    private final MerchantMappers.BookingOwnerMapper owners;
    private final MerchantMappers.BookingHoldMapper holds;
    private final ResourceBookingPort booking;
    public ResourceCandidatesPortImpl(ResourceOccupationGuard occupations, MerchantMappers.ResourceMapper resources,
            MerchantMappers.ResourceBucketMapper buckets, MerchantMappers.BookingHoldUnitMapper units,
            ResourceFactsPort facts, MerchantMappers.BookingOwnerMapper owners,
            MerchantMappers.BookingHoldMapper holds, ResourceBookingPort booking) {
        this.occupations = occupations;
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
        return DataScopeContext.executeWithoutScope(() -> {
            var scope = owners.selectScope(s.tenantNo(), s.entityNo(), s.storeNo());
            if (scope == null) { throw new IllegalArgumentException("unknown scoped resource owner"); }
            boolean nativeAvailable = occupations.inspect(scope);
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
            Map<String, MchResourceBucket> bySlot = new HashMap<>();
            for (var row : rows) {
                if (bySlot.put(row.getResourceNo() + "/" + row.getStartAt(), row) != null) {
                    throw new IllegalStateException("duplicate canonical capacity slot");
                }
            }
            List<Capacity> result = new ArrayList<>();
            var from = LocalDateTime.ofInstant(query.from(), ZoneOffset.UTC);
            var to = LocalDateTime.ofInstant(query.to(), ZoneOffset.UTC);
            var first = from.withSecond(0).withNano(0).minusMinutes(from.getMinute() % 5);
            for (var fact : known) {
                for (var start = first; start.isBefore(to); start = start.plusMinutes(5)) {
                    if (result.size() == 10000) { throw new IllegalArgumentException("capacity window too large"); }
                    var row = bySlot.get(fact.resourceNo() + "/" + start);
                    long held = 0;
                    long confirmed = 0;
                    long credit = 0;
                    if (row == null) {
                        row = new MchResourceBucket();
                        row.setResourceNo(fact.resourceNo());
                        row.setStartAt(start);
                        row.setEndAt(start.plusMinutes(5));
                        row.setCapacity(fact.capacity());
                        row.setOccupiedQty(0);
                        row.setCleaningQty(0);
                    } else {
                        var count = buckets.selectBookingCounters(scope, row.getBucketNo());
                        if (count.invalid() != 0 || count.held() != row.getHeldQty()
                                || count.confirmed() != row.getConfirmedQty() || row.getCapacity() != fact.capacity()) {
                            throw new IllegalStateException("resource capacity counters need repair");
                        }
                        held = count.held();
                        confirmed = count.confirmed();
                        credit = excluded.getOrDefault(row.getBucketNo(), 0L);
                    }
                    long used = occupations.used(scope, fact.resourceNo(), start, row, held, confirmed, nativeAvailable);
                    if (credit > held + confirmed || credit < 0) {
                        throw new IllegalStateException("replacement capacity evidence changed");
                    }
                    used -= credit;
                    // This scalar is exclusively the old blocker; actual capacity has its own demand projection.
                    if (fact.openOccupied()) { used = Math.max(used, fact.capacity()); }
                    result.add(new Capacity(fact.resourceNo(), start.toInstant(ZoneOffset.UTC),
                            start.plusMinutes(5).toInstant(ZoneOffset.UTC), fact.capacity(), occupations.reportedUsed(used)));
                }
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
        for (var row : rows) {
            if (row.getConsumedAt() != null || row.getConsumeOccupationUnitNo() != null
                    || row.getConsumeCommandNo() != null) {
                throw new IllegalArgumentException("consumed hold cannot receive replacement credit");
            }
            result.merge(row.getBucketNo(), (long) row.getQty(), Math::addExact);
        }
        return result;
    }
    private void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("candidate facts require no caller transaction");
        }
    }
}
