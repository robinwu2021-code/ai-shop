package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.trade.IndustryFulfillmentPort.Reference;
import ai.neargo.shop.spi.trade.IndustryOrderAmendmentPort.Reduction;

import java.util.List;

/** Read-only authority for persisted original return and completed kitchen disposition facts. */
public interface IndustryAmendmentSourcePort {
    record Evidence(
            Reference reference,
            String returnNo,
            String sourceHash,
            String kitchenStatus,
            String actorNo,
            List<Reduction> lines,
            String replacementCartNo) {
        public Evidence {
            lines = List.copyOf(lines);
        }
    }

    Evidence get(Reference reference, String sourceReturnNo);
}
