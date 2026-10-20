package ai.neargo.shop.spi.storedvalue;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;

public final class StoredValuePlanRecharges {
    private StoredValuePlanRecharges() { }
    /** Only trusted BFF resolves the customer reference and store display snapshot. */
    public record Create(String userNo, String storeNo, String storeName, String planNo, String versionNo, String referenceHash) {
        public Create {
            ContractChecks.hash(referenceHash); ContractChecks.id(userNo); ContractChecks.id(storeNo); ContractChecks.id(planNo); ContractChecks.id(versionNo);
            ContractChecks.require(storeName != null && !storeName.isBlank() && storeName.length() <= 100);
        }
    }
    public record Replay(String storeNo,String planNo,String versionNo,String referenceHash) {
        public Replay {ContractChecks.id(storeNo);ContractChecks.id(planNo);ContractChecks.id(versionNo);ContractChecks.hash(referenceHash);}
    }
    public record ReplayResult(boolean found,RechargeView recharge) { }
    public record Funding(String rechargeNo, String userNo, String entityNo, String storeNo, String currency,
            String planNo, String versionNo, String planName, long paidAmountMinor, long bonusAmountMinor,
            RedemptionScope redemptionScope) { }
}
