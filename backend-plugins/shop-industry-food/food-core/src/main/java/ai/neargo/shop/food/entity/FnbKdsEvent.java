package ai.neargo.shop.food.entity;

import java.time.Instant;

/** Immutable original-event projection with commit-ordered per-store sequence. */
public record FnbKdsEvent(
        String entityNo,
        String storeNo,
        long sequenceNo,
        String eventNo,
        String stationNo,
        String eventType,
        String sourceNo,
        long sourceVersion,
        String payload,
        Instant occurredAt) {}
