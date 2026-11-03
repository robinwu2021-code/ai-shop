package ai.neargo.commerce.api.port;

import java.time.Instant;
import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record PaymentOutcome(String orderNo, Optional<String> paymentNo, String platformMerchantNo, Money amount, AsyncStatus status, Optional<Instant> paidAt, Optional<String> errorCode) {}
