package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.trade.PricingFundingCapabilities;

/** Read-only, tenant-bound evidence for implemented entitlement owners and their independent runtime opt-ins. */
public interface EntitlementCapabilityPort {
    Status status(String tenantNo);

    /** Old readiness booleans cannot be interpreted as exact pricing limits. */
    default PricingFundingCapabilities.Snapshot pricing(PricingFundingCapabilities.Scope scope) {
        throw new UnsupportedOperationException("Exact pricing capability protocol is unavailable");
    }


    record Status(String tenantNo, boolean catalogReady, boolean issueReady, boolean useReady,
                  boolean paidCardReady) {
        public Status { ContractChecks.id(tenantNo); }
    }
}
