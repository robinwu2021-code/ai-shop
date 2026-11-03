package ai.neargo.commerce.api.fulfillment;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

public record VerifiedPaymentFact(String eventNo, String orderNo, String paymentNo, String platformMerchantNo, Money paid, Instant paidAt, String verificationReference) {}
