package ai.neargo.shop.pay.points.event;

/**
 * Durable and deduplicated operational attention, independent of the financial transaction outcome.
 */
public interface PointsAttentionService {
    String raise(
            String typeCode, String code, String reference, long expected, long actual, long now);
}
