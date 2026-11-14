package ai.neargo.shop.food.entity;

import java.time.Instant;

/**
 * Persisted authorization; actorNo is a real authenticated bridge identity, not a device number.
 */
public record FnbKdsSubscription(
        String subscriptionNo,
        String entityNo,
        String storeNo,
        String deviceNo,
        String actorNo,
        String stations,
        long deviceVersion,
        Instant expiresAt,
        String status,
        long version,
        String requestedBy,
        String requestKey,
        String requestHash,
        String revokeKey,
        String revokeReason,
        String revokedBy) {}
