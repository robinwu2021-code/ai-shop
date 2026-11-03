package ai.neargo.commerce.api.access;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

public record EndContextToken(String value, Instant expiresAt, long entitlementVersion) {}
