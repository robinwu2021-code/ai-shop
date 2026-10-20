package ai.neargo.shop.pay.client;

import ai.neargo.shop.spi.entitlement.ContractChecks;

/** Presence and owner identity are explicit: an empty HTTP body must never mean no payment was attempted. */
public final class PayEntitlementResponses {
    private PayEntitlementResponses() { }
    public record PaymentAttemptView(String tenantNo, String orderNo, boolean hasAnyAttempt) {
        public PaymentAttemptView { ContractChecks.id(tenantNo); ContractChecks.id(orderNo); }
    }
    public record CodeInvalidationView(String userNo, String instanceNo, int revokedCount) {
        public CodeInvalidationView {
            ContractChecks.id(userNo); ContractChecks.id(instanceNo); ContractChecks.require(revokedCount >= 0);
        }
    }
}
