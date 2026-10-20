package ai.neargo.shop.spi.entitlement;

/** Read-only, tenant-bound evidence for implemented entitlement owners and their independent runtime opt-ins. */
public interface EntitlementCapabilityPort {
    Status status(String tenantNo);

    record Status(String tenantNo, boolean catalogReady, boolean issueReady, boolean useReady,
                  boolean paidCardReady) {
        public Status { ContractChecks.id(tenantNo); }
    }
}
