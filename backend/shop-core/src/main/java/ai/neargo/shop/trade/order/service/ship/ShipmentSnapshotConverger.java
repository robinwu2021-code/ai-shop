package ai.neargo.shop.trade.order.service.ship;

/** Consumes trusted mapped logistics facts without treating carrier signature as buyer receipt. */
public interface ShipmentSnapshotConverger {
    /** Ignore superseded generations and mismatched waybills before any package transition. */
    void converge(String shipmentNo, long version, String waybillNo, String status, Long signedAt,
                  Long signedNodeAt, Long firstTraceAt, String source);

    default void converge(String shipmentNo, long version, String waybillNo, String status, Long signedAt,
                          Long signedNodeAt, Long firstTraceAt) {
        converge(shipmentNo, version, waybillNo, status, signedAt, signedNodeAt, firstTraceAt, null);
    }
}
