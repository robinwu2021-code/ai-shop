package ai.neargo.commerce.persistence.trade;

/** Typed persistence row; UTC audit timestamps, epoch-millisecond business times. */
public record MallOrderItemRecord(
        Long id,
        String tenantNo,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long version,
        Integer deleted,
        String orderItemNo,
        String orderNo,
        String skuNo,
        String skuVersionNo,
        Integer quantity,
        Long unitPriceMinor,
        Long grossMinor,
        Long discountMinor,
        Long dealMinor,
        String skuSnapshot) {}
