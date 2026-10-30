package ai.neargo.shop.spi.inventory;

import java.time.Instant;

/** Read-only original inventory evidence. UNKNOWN is never zero, unlimited, or a reservation. */
public interface InventoryAvailabilityPort {
    enum Status {
        KNOWN,
        UNKNOWN,
        UNSUPPORTED
    }

    record Query(String entityNo, String storeNo, String skuNo) {}

    record Data(
            Query query,
            String ownerId,
            String itemId,
            String locationId,
            String unit,
            String availableQuantity,
            String revision) {}

    record Facts(
            Status status, Data data, Instant observedAt, Instant validUntil, String receiptRef) {}

    Facts capture(Query query);
}
