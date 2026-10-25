package ai.neargo.shop.food.spi;

/** External provider boundary. Its original request key must be permanently idempotent. */
public interface QueueNotificationPort {
    record Request(
            String entityNo,
            String storeNo,
            String ticketNo,
            String contactRef,
            String originalKey) {}

    record Receipt(String originalKey, String status, String providerRef) {}

    Receipt lookup(Request request);

    Receipt send(Request request);
}
