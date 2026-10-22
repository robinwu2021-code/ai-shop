package ai.neargo.shop.food.dto;

import java.time.Instant;

/** Typed response; credential contents are deliberately absent from diagnostic strings. */
public record DiningSessionVO(String sessionNo, String accessToken, Instant expiresAt, String checkNo) {
    @Override public String toString() { return "DiningSessionVO[REDACTED]"; }
}
