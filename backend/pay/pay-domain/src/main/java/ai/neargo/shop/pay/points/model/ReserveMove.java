package ai.neargo.shop.pay.points.model;

/** Per-redemption reserve counters preserve the exact liability behind the aggregate B reservation. */
public record ReserveMove(String reserveNo, String positionNo, long expectedVersion, long originalMinor,
                          long remainingDelta, long consumedDelta, long releasedDelta) { }
