package ai.neargo.shop.pay.client;

import ai.neargo.shop.spi.entitlement.ContractChecks;

public final class PayCapabilityRequests {
    private PayCapabilityRequests() { }
    public record Status(String tenantNo) {
        public Status { ContractChecks.id(tenantNo); }
    }
}
