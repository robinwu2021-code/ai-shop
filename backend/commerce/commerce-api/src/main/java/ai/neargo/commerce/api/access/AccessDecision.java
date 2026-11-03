package ai.neargo.commerce.api.access;

import java.time.Instant;
import java.util.Optional;
import ai.neargo.commerce.api.common.*;

public record AccessDecision(AccessStatus status, StoreRef store, String entitlementCode, long entitlementVersion, Instant serverNow, Optional<Instant> nextBoundaryAt, Optional<String> denialCode) {}
