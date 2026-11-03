package ai.neargo.commerce.api.port;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

public record PaymentIntentCommand(String tenantNo, String orderNo, String platformMerchantNo, Money amount, Instant expiresAt) {}
