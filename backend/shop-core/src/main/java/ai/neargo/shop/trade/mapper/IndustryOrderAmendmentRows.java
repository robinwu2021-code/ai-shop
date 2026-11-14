package ai.neargo.shop.trade.mapper;

import java.math.BigDecimal;

public final class IndustryOrderAmendmentRows {
    private IndustryOrderAmendmentRows() {}

    public enum ReductionColumn {
        QUANTITY,
        PHYSICAL,
        CANCEL
    }

    public record Journal(
            Long id,
            String tenantNo,
            String entityNo,
            String storeNo,
            String orderNo,
            String subOrderNo,
            String amendmentNo,
            String operationKey,
            String requestHash,
            String kind,
            String sourceReturnNo,
            String sourceHash,
            String commandSnapshot,
            String evidenceSnapshot,
            String inventorySnapshot,
            String status,
            String actorNo,
            long expectedSubVersion,
            String currency,
            long receivableDeltaMinor,
            String replacementCartNo,
            Long netDeltaMinor,
            Long taxDeltaMinor) {}

    public record Line(
            Long orderItemId,
            String sourceComponentRef,
            BigDecimal quantity,
            BigDecimal physicalReductionQty,
            BigDecimal cancelQty,
            String unit,
            String disposition,
            long receivableReductionMinor,
            long netReductionMinor,
            long taxReductionMinor) {}

    public record FeeApplication(
            String quoteNo, String ruleNo, String revisionNo, String scopeNo, long feeMinor) {}

    public record FeeLine(Long orderItemId, String sourceComponentRef, long amountMinor) {}

    public record Applied(
            String amendmentNo,
            String sourceHash,
            String kind,
            String inventorySnapshot,
            long receivableDeltaMinor) {}

    public record ReturnDraft(
            String tenantNo,
            String entityNo,
            String storeNo,
            String orderNo,
            String subOrderNo,
            String amendmentNo,
            String operationKey,
            String requestHash,
            String kind,
            String sourceReturnNo,
            String sourceHash,
            String commandSnapshot,
            String evidenceSnapshot,
            String status,
            String actorNo,
            long expectedSubVersion,
            String currency,
            long receivableDeltaMinor,
            String replacementCartNo) {}

    public record Debt(
            String tenantNo,
            String entityNo,
            String storeNo,
            String orderNo,
            String subOrderNo,
            String amendmentNo,
            String currency,
            long amountMinor,
            String sourceHash,
            String reason,
            String actorNo,
            Long netDeltaMinor,
            Long taxDeltaMinor) {}
}
