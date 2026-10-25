package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;

import java.math.BigDecimal;
import java.util.List;

/** Original non-funds debt/quantity corrections. No refund or payment is executed. */
public interface IndustryOrderAmendmentPort {
    record Reduction(
            Long orderItemId,
            String sourceComponentRef,
            BigDecimal quantity,
            String unit,
            String disposition) {}

    record Command(
            Reference reference,
            String actorNo,
            long expectedSubVersion,
            String sourceReturnNo,
            String sourceHash,
            List<Reduction> lines,
            String reason,
            String replacementCartNo) {
        public Command {
            lines = List.copyOf(lines);
        }
    }

    record FeeScopeAdjustment(
            Reference reference,
            String actorNo,
            long expectedSubVersion,
            String feeApplicationNo,
            long targetFeeMinor,
            String sourceHash,
            String reason) {}

    record Line(
            Long orderItemId,
            String sourceComponentRef,
            BigDecimal reducedQty,
            BigDecimal physicalReductionQty,
            String unit,
            long receivableReductionMinor) {}

    record Receipt(
            String amendmentNo,
            Reference reference,
            String status,
            boolean pending,
            String currency,
            Long receivableAdjustmentMinor,
            Long effectiveReceivableMinor,
            long subOrderVersion,
            List<Line> lines,
            String replacementCartNo) {
        public Receipt {
            lines = List.copyOf(lines);
        }
    }

    Receipt amend(Command command, String key);

    Receipt query(Reference reference, String actorNo, String key);

    Receipt adjustFee(FeeScopeAdjustment command, String key);
}
