package ai.neargo.shop.spi.entitlement;

import java.util.List;

/** Committed pay usage attribution only; balances, lots, credentials and frozen commands stay in pay. */
public interface EntitlementMarketingFactsPort {
    UsageEffects usage(EntitlementContext context,String entityNo,String usageNo,String afterActivityNo,int limit);
    record Attribution(String activityNo,Long amountMinor,Long units) {
        public Attribution {
            ContractChecks.id(activityNo);
            ContractChecks.require(amountMinor!=null || units!=null);
            ContractChecks.require(amountMinor==null || amountMinor>0);
            ContractChecks.require(units==null || units>0);
        }
    }
    record UsageEffects(String tenantNo,String usageNo,String userNo,String entityNo,String storeNo,String assetKind,
                        String assetNo,String currency,Long amountMinor,Integer units,long occurredAt,
                        List<Attribution> records,String nextCursor) {
        public UsageEffects {
            ContractChecks.id(tenantNo); ContractChecks.id(usageNo); ContractChecks.id(userNo);
            ContractChecks.id(entityNo); ContractChecks.id(storeNo); ContractChecks.id(assetNo);
            ContractChecks.currency(currency); ContractChecks.require(occurredAt>0);
            records=List.copyOf(ContractChecks.present(records));
            ContractChecks.optionalId(nextCursor);
        }
    }
}
