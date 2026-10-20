package ai.neargo.shop.spi.entitlement;

import java.time.Instant;
import java.util.List;

/** Pay-only legacy ORDER-coupon holds. Never accepts a raw code, an account debit, or a paid-card use. */
public interface LegacyCouponUsePort {
    Snapshot snapshot(EntitlementContext context,Owned command);
    Estimate estimate(EntitlementContext context,EstimateCommand command);
    Quote quote(EntitlementContext context,Prepare command);
    Operation prepare(EntitlementContext context,Prepare command);
    Operation commit(EntitlementContext context,Complete command);
    Operation release(EntitlementContext context,Complete command);
    Operation reverse(EntitlementContext context,Complete command);
    Operation query(EntitlementContext context,String entityNo,String operationNo);

    record Owned(String userNo,String entityNo,EntitlementOwnerRegistryPort.SourceIdentity origin,
                 String targetInstanceNo,String ownerBatchNo,String ownerPlanHash) {
        public Owned {
            ContractChecks.id(userNo);ContractChecks.id(entityNo);ContractChecks.present(origin);
            ContractChecks.require(origin.sourceKind()==EntitlementOwnerRegistryPort.SourceKind.INSTANCE);
            ContractChecks.id(targetInstanceNo);ContractChecks.id(ownerBatchNo);ContractChecks.hash(ownerPlanHash);
        }
    }
    record Snapshot(String instanceNo,EntitlementDefinition definition,int totalUnits,int remainingUnits,int reservedUnits,
                    EntitlementQueryPort.AssetState state,Instant effectiveFrom,Instant effectiveUntil,
                    Instant lastUsedAt,boolean usableNow) {
        public Snapshot {
            ContractChecks.id(instanceNo);ContractChecks.present(definition);ContractChecks.present(state);
            ContractChecks.require(totalUnits>0 && reservedUnits>=0 && remainingUnits>=reservedUnits && remainingUnits<=totalUnits);
            ContractChecks.require((effectiveFrom==null)==(effectiveUntil==null));
        }
    }
    /** Read-only simulation; contains no checkout identity or credential that can authorize a hold. */
    record EstimateCommand(String userNo,String entityNo,EntitlementOwnerRegistryPort.SourceIdentity origin,
                           String targetInstanceNo,String ownerBatchNo,String ownerPlanHash,String currency,
                           List<LegacyCouponCheckoutSourcePort.MerchantGroup> groups) {
        public EstimateCommand {
            ContractChecks.id(userNo);ContractChecks.id(entityNo);ContractChecks.present(origin);
            ContractChecks.require(origin.sourceKind()==EntitlementOwnerRegistryPort.SourceKind.INSTANCE);
            ContractChecks.id(targetInstanceNo);ContractChecks.id(ownerBatchNo);ContractChecks.hash(ownerPlanHash);ContractChecks.currency(currency);
            groups=List.copyOf(ContractChecks.present(groups));
            ContractChecks.require(!groups.isEmpty() && groups.size()<=200 && groups.stream()
                    .map(LegacyCouponCheckoutSourcePort.MerchantGroup::groupKey).distinct().count()==groups.size());
            long total=0;for(var group:groups) total=ContractChecks.addAmounts(total,group.goodsAmountMinor());
        }
    }
    record Estimate(String instanceNo,String currency,String title,long discountMinor,List<Allocation> allocations,
                    Instant assetEffectiveUntil) {
        public Estimate {
            ContractChecks.id(instanceNo);ContractChecks.currency(currency);ContractChecks.present(assetEffectiveUntil);
            ContractChecks.require(title!=null && !title.isBlank());
            allocations=LegacyCouponUsePort.allocations(allocations,discountMinor);
        }
    }
    record Prepare(String checkoutNo,String operationNo,String userNo,String entityNo) {
        public Prepare {
            ContractChecks.id(checkoutNo); ContractChecks.id(operationNo);
            ContractChecks.id(userNo); ContractChecks.id(entityNo);
        }
    }
    record Complete(String checkoutNo,String operationNo,String entityNo,String decisionNo) {
        public Complete {
            ContractChecks.id(checkoutNo); ContractChecks.id(operationNo);
            ContractChecks.id(entityNo); ContractChecks.id(decisionNo);
        }
    }
    enum State { HELD, COMMITTED, RELEASED, REVERSED }
    record Allocation(String groupKey,String merchantNo,String storeNo,long discountMinor) {
        public Allocation {
            ContractChecks.id(groupKey); ContractChecks.id(merchantNo); ContractChecks.optionalId(storeNo);
            ContractChecks.require(discountMinor>0);
        }
    }
    record Quote(String instanceNo,String currency,long discountMinor,List<Allocation> allocations,
                 String economicHash,Instant availableUntil) {
        public Quote {
            ContractChecks.id(instanceNo); ContractChecks.currency(currency); ContractChecks.hash(economicHash);
            ContractChecks.present(availableUntil); allocations=LegacyCouponUsePort.allocations(allocations,discountMinor);
        }
    }
    /** A cancellation before any hold has unknown discount (NULL), never an invented zero-value use. */
    record Operation(String checkoutNo,String operationNo,String instanceNo,String entityNo,String currency,
                     String economicHash,State status,Long discountMinor,List<Allocation> allocations) {
        public Operation {
            ContractChecks.id(checkoutNo); ContractChecks.id(operationNo); ContractChecks.id(instanceNo);
            ContractChecks.id(entityNo); ContractChecks.currency(currency); ContractChecks.hash(economicHash);
            ContractChecks.present(status);
            if(discountMinor==null) {
                ContractChecks.require(status==State.RELEASED && (allocations==null || allocations.isEmpty()));
                allocations=List.of();
            } else allocations=LegacyCouponUsePort.allocations(allocations,discountMinor);
        }
    }
    private static List<Allocation> allocations(List<Allocation> input,long amount) {
        var result=List.copyOf(ContractChecks.present(input));
        ContractChecks.require(amount>0 && !result.isEmpty()
                && result.stream().map(Allocation::groupKey).distinct().count()==result.size());
        long sum=0;
        for(var part:result) sum=ContractChecks.addAmounts(sum,part.discountMinor());
        ContractChecks.require(sum==amount);
        return result;
    }
}
