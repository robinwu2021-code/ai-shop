package ai.neargo.commerce.api.port;

/**
 * Query/close the original attempt. A definitive close fences all future creates of this attempt.
 */
public record PaymentQuery(String tenantNo, String orderNo, String paymentAttemptNo) {
    public PaymentQuery(String tenantNo, String orderNo) {
        this(tenantNo, orderNo, orderNo);
    }
}
