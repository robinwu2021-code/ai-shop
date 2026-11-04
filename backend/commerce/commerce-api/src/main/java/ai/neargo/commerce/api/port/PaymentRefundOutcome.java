package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

import java.util.Optional;

/** Legacy outcomes contain no verified completion authority. */
public record PaymentRefundOutcome(
        String refundNo,
        AsyncStatus status,
        Optional<String> providerRefundNo,
        Optional<String> errorCode,
        VerifiedRefundFact verifiedFact) {
    public PaymentRefundOutcome(
            String refundNo,
            AsyncStatus status,
            Optional<String> providerRefundNo,
            Optional<String> errorCode) {
        this(refundNo, status, providerRefundNo, errorCode, null);
    }
}
