package ai.neargo.shop.event.outbox;

import java.time.Instant;

/** Only the holder of this claim token may acknowledge or renew a delivery. */
public record ClaimedDelivery(DeliveryRef ref, OutboxEnvelope event, String handlerId,
        String claimToken, int attemptCount, int roundAttemptCount, Instant leaseUntil) { }
