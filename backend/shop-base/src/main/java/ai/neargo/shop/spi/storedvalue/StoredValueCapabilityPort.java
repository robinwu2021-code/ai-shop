package ai.neargo.shop.spi.storedvalue;

import ai.neargo.shop.spi.trade.PricingFundingCapabilities;

import ai.neargo.shop.spi.entitlement.ContractChecks;

/** Read-only owner evidence for implemented stored-value execution, never a consumer-funded operation. */
public interface StoredValueCapabilityPort {
    Status status(String tenantNo);

    /** Old readiness booleans cannot be interpreted as exact pricing limits. */
    default PricingFundingCapabilities.Snapshot pricing(PricingFundingCapabilities.Scope scope) {
        throw new UnsupportedOperationException("Exact pricing capability protocol is unavailable");
    }

    record Status(String tenantNo,boolean creditReady,boolean redemptionReady) {
        public Status { ContractChecks.id(tenantNo); }
    }
}
