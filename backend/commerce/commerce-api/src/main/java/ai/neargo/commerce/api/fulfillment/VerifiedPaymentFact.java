package ai.neargo.commerce.api.fulfillment;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

/** Tenant is resolved by the trusted payment adapter, never from an unverified callback body. */
public record VerifiedPaymentFact(String tenantNo, String eventNo, String orderNo, String paymentNo, String platformMerchantNo, Money paid, Instant paidAt, String verificationReference) {}
