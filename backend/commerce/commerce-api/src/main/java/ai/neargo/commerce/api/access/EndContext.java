package ai.neargo.commerce.api.access;

import ai.neargo.commerce.api.common.*;

import java.time.Instant;

public record EndContext(
        String contextNo,
        ActorContext actor,
        StoreRef store,
        String endCode,
        String entitlementCode,
        String clientId,
        String audience,
        long entitlementVersion,
        Instant issuedAt,
        Instant expiresAt) {}
