package ai.neargo.shop.spi.storedvalue;

import ai.neargo.shop.spi.entitlement.ContractChecks;

/** Read-only owner evidence for implemented stored-value execution, never a consumer-funded operation. */
public interface StoredValueCapabilityPort {
    Status status(String tenantNo);
    record Status(String tenantNo,boolean creditReady,boolean redemptionReady) {
        public Status { ContractChecks.id(tenantNo); }
    }
}
