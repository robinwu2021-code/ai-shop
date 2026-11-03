package ai.neargo.commerce.api.access;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

public record EndContext(String contextNo, ActorContext actor, StoreRef store, String entitlementCode, String clientId, String audience, long entitlementVersion, Instant issuedAt, Instant expiresAt) {}
