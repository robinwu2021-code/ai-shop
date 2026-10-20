package ai.neargo.shop.spi.storedvalue;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePort.RechargeView;

public final class StoredValuePlanRecharges {
    private StoredValuePlanRecharges() { }
    /** Only trusted BFF resolves the customer reference and store display snapshot. */
    public record Create(String userNo, String storeNo, String storeName, String planNo, String versionNo, String referenceHash, String customerNo) {
        public Create(String userNo,String storeNo,String storeName,String planNo,String versionNo,String referenceHash){this(userNo,storeNo,storeName,planNo,versionNo,referenceHash,userNo);}
        public Create {
            if(customerNo==null)customerNo=userNo;
            ContractChecks.id(customerNo);
            ContractChecks.hash(referenceHash); if(userNo!=null)ContractChecks.id(userNo); ContractChecks.id(storeNo); ContractChecks.id(planNo); ContractChecks.id(versionNo);
            ContractChecks.require(storeName != null && !storeName.isBlank() && storeName.length() <= 100);
        }
    }
    public record Replay(String storeNo,String planNo,String versionNo,String referenceHash) {
        public Replay {ContractChecks.id(storeNo);ContractChecks.id(planNo);ContractChecks.id(versionNo);ContractChecks.hash(referenceHash);}
    }
    public record ReplayResult(boolean found,RechargeView recharge) { }
    public record Funding(String rechargeNo, String userNo, String entityNo, String storeNo, String currency,
            String planNo, String versionNo, String planName, long paidAmountMinor, long bonusAmountMinor,
            RedemptionScope redemptionScope,String customerNo) {
        public Funding {if(customerNo==null)customerNo=userNo;ContractChecks.id(customerNo);}
        public Funding(String rechargeNo,String userNo,String entityNo,String storeNo,String currency,String planNo,String versionNo,String planName,long paidAmountMinor,long bonusAmountMinor,RedemptionScope scope){this(rechargeNo,userNo,entityNo,storeNo,currency,planNo,versionNo,planName,paidAmountMinor,bonusAmountMinor,scope,userNo);}
    }
}
