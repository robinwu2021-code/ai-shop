package ai.neargo.commerce.api.port;

import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record PaymentRefundOutcome(String refundNo, AsyncStatus status, Optional<String> providerRefundNo, Optional<String> errorCode) {}
