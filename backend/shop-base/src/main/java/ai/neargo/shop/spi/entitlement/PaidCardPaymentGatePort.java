package ai.neargo.shop.spi.entitlement;
/** Called before creating any external payment attempt, outside a core write transaction. */
public interface PaidCardPaymentGatePort {
    void requireReady(String tenantNo,String orderNo);
}
