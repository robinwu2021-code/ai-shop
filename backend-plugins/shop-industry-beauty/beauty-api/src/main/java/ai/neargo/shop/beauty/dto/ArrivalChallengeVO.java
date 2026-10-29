package ai.neargo.shop.beauty.dto;

import java.time.OffsetDateTime;

/** Scoped result; credentials and public arrival codes must not be logged. */
public record ArrivalChallengeVO(String challengeNo, String storeNo, String code,
        OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
    @Override public String toString() { return "ArrivalChallengeVO[REDACTED]"; }
}
