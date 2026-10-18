package ai.neargo.shop.spi.entitlement;

/** Trusted service identity and idempotency metadata; never bind directly from a public request. */
public record EntitlementContext(String tenantNo, String actorNo, String requestId, String payloadHash) {
    public EntitlementContext {
        ContractChecks.id(tenantNo);
        ContractChecks.require(tenantNo.length() <= 32);
        ContractChecks.id(actorNo);
        ContractChecks.id(requestId);
        ContractChecks.hash(payloadHash);
    }
}
