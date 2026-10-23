package ai.neargo.shop.food.dto;

import java.time.Instant;

/** Typed response; credential contents are deliberately absent from diagnostic strings. */
public record TableTokenVO(String token, Instant expiresAt) {
    @Override public String toString() { return "TableTokenVO[REDACTED]"; }
}
