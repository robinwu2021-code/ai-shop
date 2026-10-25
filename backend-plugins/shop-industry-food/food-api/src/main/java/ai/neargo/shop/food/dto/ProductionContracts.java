package ai.neargo.shop.food.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Confirmed quantity is inventory issue evidence, never measured physical usage or inferred cost.
 */
public final class ProductionContracts {
    private ProductionContracts() {}

    /**
     * Dates select business days; to is exclusive. Service validates the bounded query in both
     * deployments.
     */
    public record ListQuery(
            Integer page,
            Integer size,
            LocalDate from,
            LocalDate to,
            String skuNo,
            String itemRef,
            String status) {}

    public record AdjustmentReq(Long version, String kind, BigDecimal qty, String reason) {}

    public record ReverseReq(Long version, String reason) {}

    public record LineVO(
            int lineNo,
            String componentPath,
            String itemRef,
            BigDecimal theoryQty,
            String unit,
            String recipeRevisionNo,
            String inventoryReceiptRef,
            BigDecimal inventoryIssuedQty,
            Long costMinor,
            String currency,
            String status) {}

    public record ConsumptionVO(
            String consumptionNo,
            String sourceEventNo,
            String purpose,
            String status,
            String requestHash,
            String inventoryOperationRef,
            LocalDate businessDate,
            LocalDateTime occurredAt,
            List<LineVO> lines,
            String quantityEvidence,
            boolean consumptionPending,
            Long totalCostMinor,
            String costCurrency,
            long version) {
        public ConsumptionVO {
            lines = List.copyOf(lines);
        }
    }
}
