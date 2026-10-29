package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Reference;
import ai.neargo.shop.spi.trade.IndustryChannelOrderPort.Scope;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/** Native channel after-sale authority never requires a local buyer or asserts local payment. */
public interface IndustryChannelAfterSalePort {
    enum Action {
        REFUND_ONLY,
        RETURN_REFUND,
        REJECT
    }

    record Selected(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String sourceLineRef,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String componentRef,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String physicalQuantity) {}

    record Authorize(
            Reference original,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String externalCaseRef,
            @JsonDeserialize(using = IndustryChannelAfterSaleCodecs.ActionValue.class)
                    Action action,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String reasonCode,
            List<Selected> lines) {
        public Authorize {
            lines = lines == null ? null : List.copyOf(lines);
        }
    }

    record Query(
            Scope scope,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String sourceSystem,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String coreOrderNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String externalCaseRef,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String decisionNo,
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class)
                    String revisionNo) {}

    record Line(
            String externalLineRef,
            String parentExternalLineRef,
            String sourceLineRef,
            String componentRef,
            String coreItemRef,
            String physicalQuantity,
            String unit,
            long refundCeilingMinor,
            String originalAllocationReceiptRef) {}

    record Decision(
            Reference original,
            String coreOrderNo,
            String subOrderNo,
            String externalOrderRef,
            String externalFinancialHash,
            String externalCaseRef,
            String decisionNo,
            @JsonDeserialize(using = IndustryChannelAfterSaleCodecs.ActionValue.class)
                    Action action,
            String reasonCode,
            String currency,
            List<Line> lines,
            long refundCeilingMinor,
            String actorNo,
            Instant authorizedAt,
            String originalReceiptRef) {
        public Decision {
            lines = List.copyOf(lines);
        }
    }

    record Facts(
            Query query,
            Decision decision,
            String revisionNo,
            Instant observedAt,
            Instant validUntil,
            String verificationReceiptRef,
            String fundsStatus,
            String inventoryStatus) {}

    /**
     * Called by original authenticated merchant policy, with actor obtained from the security
     * context.
     */
    Facts authorize(Authorize command, String actorNo, String idempotencyKey);

    Facts get(Query query);

    /**
     * Explicit original physical cancellation, distinct from vendor decision and every funds state.
     */
    Facts releaseUnissued(Query query, String actorNo);
}
