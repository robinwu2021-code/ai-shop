package ai.neargo.shop.pay.client;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Scope;

public final class PayCapabilityRequests {
    private PayCapabilityRequests() { }
    public record Pricing(Scope scope) {
        public Pricing { ContractChecks.present(scope); }
    }
    public record Status(String tenantNo) {
        public Status { ContractChecks.id(tenantNo); }
    }
}
