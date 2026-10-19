package ai.neargo.shop.trade.dto;

import java.time.Instant;

/** Consent and use progress are different facts; old intents do not expose an operation. */
public record AuthorizationView(String authorizationRef, String status, Instant expiresAt,
                                 String operationNo, String useStatus) {
    public AuthorizationView(String authorizationRef, String status, Instant expiresAt) {
        this(authorizationRef, status, expiresAt, null, null);
    }
}
