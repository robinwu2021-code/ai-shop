package ai.neargo.shop.food.dto;

import java.time.Instant;

/** Typed response; credential contents are deliberately absent from diagnostic strings. */
public record DiningSessionTableVO(String entityNo, String storeNo, String tableNo, Instant expiresAt) {
    @Override public String toString() { return "DiningSessionTableVO[REDACTED]"; }
}
