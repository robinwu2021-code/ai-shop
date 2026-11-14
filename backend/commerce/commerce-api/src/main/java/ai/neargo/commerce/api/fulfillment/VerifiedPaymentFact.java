package ai.neargo.commerce.api.fulfillment;

import ai.neargo.commerce.api.common.*;

import java.time.Instant;

/** Tenant and precision come from trusted payment verification, never an HTTP body. */
public record VerifiedPaymentFact(
        String tenantNo,
        String eventNo,
        String orderNo,
        String paymentNo,
        String platformMerchantNo,
        Money paid,
        Instant paidAt,
        String verificationReference,
        Long paidAtPrecisionMs, String acceptanceFence) {
    public VerifiedPaymentFact(String tenantNo, String eventNo, String orderNo, String paymentNo,
            String platformMerchantNo, Money paid, Instant paidAt, String verificationReference, Long paidAtPrecisionMs) {
        this(tenantNo,eventNo,orderNo,paymentNo,platformMerchantNo,paid,paidAt,verificationReference,paidAtPrecisionMs,null);
    }

    public VerifiedPaymentFact {
        paidAtPrecisionMs = PaymentTimePrecision.normalize(paidAtPrecisionMs);
        if (paidAt != null) PaymentTimePrecision.latestMillis(paidAt, paidAtPrecisionMs);
    }

    public VerifiedPaymentFact(
            String tenantNo,
            String eventNo,
            String orderNo,
            String paymentNo,
            String platformMerchantNo,
            Money paid,
            Instant paidAt,
            String verificationReference) {
        this(
                tenantNo,
                eventNo,
                orderNo,
                paymentNo,
                platformMerchantNo,
                paid,
                paidAt,
                verificationReference,
                1L);
    }
}
