package ai.neargo.commerce.api.access;

import ai.neargo.commerce.api.common.*;

import java.time.Instant;

public record EndContextToken(String value, Instant expiresAt, long entitlementVersion) {
    @Override
    public String toString() {
        return "EndContextToken[redacted]";
    }
}
