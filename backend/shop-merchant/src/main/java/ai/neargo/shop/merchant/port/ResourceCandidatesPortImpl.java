package ai.neargo.shop.merchant.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchResourceBucket;
import ai.neargo.shop.merchant.mapper.MerchantMappers;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Advisory reads include every unreleased hold; expiration never silently manufactures free capacity. */
@Component
public class ResourceCandidatesPortImpl implements ResourceCandidatesPort {
    private final MerchantMappers.ResourceMapper resources;
    private final MerchantMappers.ResourceBucketMapper buckets;
    private final MerchantMappers.BookingHoldUnitMapper units;
    private final ResourceFactsPort facts;
    public ResourceCandidatesPortImpl(MerchantMappers.ResourceMapper resources,
            MerchantMappers.ResourceBucketMapper buckets, MerchantMappers.BookingHoldUnitMapper units,
            ResourceFactsPort facts) {
        this.resources = resources;
        this.buckets = buckets;
        this.units = units;
        this.facts = facts;
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
                    query.resourceNos(), query.from()));
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
            List<Capacity> result = new ArrayList<>();
            for (var row : rows) {
                var count = buckets.selectBookingCounters(scope, row.getBucketNo());
                if (count.invalid() != 0 || count.held() != row.getHeldQty()
                        || count.confirmed() != row.getConfirmedQty()) {
                    throw new IllegalStateException("resource capacity counters need repair");
                }
                result.add(new Capacity(row.getResourceNo(), row.getStartAt().toInstant(ZoneOffset.UTC),
                        row.getEndAt().toInstant(ZoneOffset.UTC), row.getCapacity(),
                        Math.toIntExact(count.held() + count.confirmed())));
            }
            return List.copyOf(result);
        });
    }
    private void outside() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("candidate facts require no caller transaction");
        }
    }
}
