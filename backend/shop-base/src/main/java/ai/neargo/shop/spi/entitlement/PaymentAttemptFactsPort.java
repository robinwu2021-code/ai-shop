package ai.neargo.shop.spi.entitlement;

/** Owner-only history, used before staging a price change; absence of a callback is not absence of a charge. */
public interface PaymentAttemptFactsPort {
    boolean hasAnyAttempt(String tenantNo, String orderNo);
}
