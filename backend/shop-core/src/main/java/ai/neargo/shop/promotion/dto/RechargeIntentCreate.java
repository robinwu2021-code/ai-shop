package ai.neargo.shop.promotion.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;

public record RechargeIntentCreate(String customerRef, String activityNo, String versionNo, String tierNo,
                                   String storeNo) {
    public RechargeIntentCreate {
        ContractChecks.id(customerRef); ContractChecks.id(activityNo); ContractChecks.id(versionNo);
        ContractChecks.id(tierNo); ContractChecks.id(storeNo);
    }
    @Override public String toString() { return "RechargeIntentCreate[customerRef=REDACTED]"; }
}
