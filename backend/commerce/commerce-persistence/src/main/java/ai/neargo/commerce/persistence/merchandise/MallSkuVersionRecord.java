package ai.neargo.commerce.persistence.merchandise;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MallSkuVersionRecord(
    Long id,
    String tenantNo,
    java.time.LocalDateTime createdAt,
    java.time.LocalDateTime updatedAt,
    String createdBy,
    String updatedBy,
    Long version,
    Integer deleted,
    String skuVersionNo,
    String skuNo,
    Integer revision,
    String status,
    String currency,
    Long listPriceMinor,
    Long salePriceMinor,
    String allowedBusinessTypes,
    String refundPolicySnapshot,
    Long publishedAt) {}
