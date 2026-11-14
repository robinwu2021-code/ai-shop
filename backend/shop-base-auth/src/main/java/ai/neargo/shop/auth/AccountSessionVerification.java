package ai.neargo.shop.auth;

import java.time.Instant;

public record AccountSessionVerification(
        LoginUser user, String sessionFingerprint, Instant expiresAt, Instant verifiedAt) {}
