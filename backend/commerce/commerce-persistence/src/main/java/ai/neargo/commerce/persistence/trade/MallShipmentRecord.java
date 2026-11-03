package ai.neargo.commerce.persistence.trade;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MallShipmentRecord(
    Long id,
    String tenantNo,
    java.time.LocalDateTime createdAt,
    java.time.LocalDateTime updatedAt,
    String createdBy,
    String updatedBy,
    Long version,
    Integer deleted,
    String shipmentNo,
    String orderNo,
    String shipmentItems,
    String addressSnapshot,
    String carrierCode,
    String trackingNo,
    String status,
    Long shippedAt,
    Long signedAt) {}
